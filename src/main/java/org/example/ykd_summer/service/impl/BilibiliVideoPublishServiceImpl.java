package org.example.ykd_summer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.ykd_summer.dto.VideoMetadata;
import org.example.ykd_summer.dto.VideoPublishResult;
import org.example.ykd_summer.service.BilibiliVideoPublishService;
import org.example.ykd_summer.store.BilibiliCookieStore;
import org.example.ykd_summer.web.ConsoleEvent;
import org.example.ykd_summer.web.ConsoleEventBus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站视频稿件发布服务实现（Playwright 浏览器操控）。
 * <p>
 * 流程：
 * <ol>
 *   <li>用 data/bilibili_browser_state.json 恢复浏览器登录态（无需重新扫码）</li>
 *   <li>打开草稿编辑页（标题/简介/标签/分区/封面已由 API 写入草稿）</li>
 *   <li>补填页面端必填项（创作声明，API 无法写入）</li>
 *   <li>点击"立即投稿"</li>
 *   <li>等待发布结果，提取 BV 号</li>
 * </ol>
 * 风控兜底：若弹出 geetest 验证，headed 浏览器会展示验证界面，
 * 等待用户手动完成（最长 90 秒），超时则返回失败并附草稿链接。
 */
@Slf4j
@Service
public class BilibiliVideoPublishServiceImpl implements BilibiliVideoPublishService {

    private static final String DRAFT_EDIT_URL =
            "https://member.bilibili.com/platform/upload/video/frame?type=draft&draftId=";
    private static final String ARCHIVES_URL =
            "https://member.bilibili.com/x/web/archives?status=is_pubing,pubed,not_pubed&pn=1&ps=1&coop=1&interactive=1";
    private static final Pattern BV_PATTERN = Pattern.compile("\"bvid\"\\s*:\\s*\"(BV[0-9A-Za-z]+)\"");
    private static final Pattern BV_IN_URL = Pattern.compile("(BV[0-9A-Za-z]{10})");

    /** 发布总等待上限（含人工验证时间） */
    private static final int PUBLISH_WAIT_MS = 90_000;

    private final BilibiliCookieStore cookieStore;
    private final ConsoleEventBus eventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient();

    /** 是否使用无头模式（false=弹出可见浏览器，便于排查和人工过验证） */
    @Value("${bilibili.video.publish-headless:false}")
    private boolean publishHeadless;

    public BilibiliVideoPublishServiceImpl(BilibiliCookieStore cookieStore, ConsoleEventBus eventBus) {
        this.cookieStore = cookieStore;
        this.eventBus = eventBus;
    }

    @Override
    public VideoPublishResult publishDraft(long draftAid, VideoMetadata metadata) {
        String draftUrl = DRAFT_EDIT_URL + draftAid;
        Path stateFile = Path.of(System.getProperty("user.dir"), "data", "bilibili_browser_state.json");
        if (!Files.exists(stateFile)) {
            log.warn("浏览器状态文件不存在: {}，请重新扫码登录", stateFile);
            return VideoPublishResult.fail("浏览器登录状态不存在，请重新扫码登录后再试", draftUrl);
        }

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        try {
            playwright = Playwright.create();
            browser = launchBrowser(playwright);
            if (browser == null) {
                return VideoPublishResult.fail("无法启动浏览器", draftUrl);
            }
            context = browser.newContext(new Browser.NewContextOptions()
                    .setStorageStatePath(stateFile)
                    .setViewportSize(1440, 900));
            // 用 cookie 文件中的最新值覆盖浏览器状态（browser_state 是登录时的快照，
            // bili_jct 等可能已过期，会导致发布时 "csrf 校验失败"）
            injectFreshCookies(context);
            Page page = context.newPage();
            page.setDefaultTimeout(10_000);

            // 1. 打开草稿编辑页
            log.info("发布流程: 打开草稿编辑页 {}", draftUrl);
            eventBus.publish(ConsoleEvent.step("BilibiliPublish", "打开草稿编辑页..."));
            page.navigate(draftUrl, new Page.NavigateOptions().setTimeout(30_000));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            // 等待编辑器渲染（标题输入框出现）
            page.waitForSelector("input", new Page.WaitForSelectorOptions().setTimeout(15_000));
            page.waitForTimeout(2500);

            // 2. 清理新手引导条/弹窗（"投稿模板功能升级"等，避免误点和遮挡）
            dismissGuidePopups(page);

            // 3. 补填创作声明（API 草稿不覆盖此项，页面必填）
            eventBus.publish(ConsoleEvent.step("BilibiliPublish", "补填创作声明..."));
            trySelectDeclaration(page);

            // 4. 再次清理可能残留的弹窗（防止误操作打开了对话框）
            dismissGuidePopups(page);

            // 5. 点击"立即投稿"
            eventBus.publish(ConsoleEvent.step("BilibiliPublish", "点击立即投稿..."));
            if (!clickPublishButton(page)) {
                saveDebugScreenshot(page, "publish_btn_not_found");
                eventBus.publish(ConsoleEvent.step("BilibiliPublish", "❌ 未找到发布按钮"));
                return VideoPublishResult.fail("未找到发布按钮，请手动完成发布", draftUrl);
            }
            log.info("发布流程: 已点击投稿按钮，等待结果...");

            // 4. 等待发布结果（成功跳转 / 验证弹窗 / 失败提示）
            String bvid = waitForPublishResult(page, draftAid);
            if (bvid != null) {
                log.info("发布成功: bvid={}", bvid);
                eventBus.publish(ConsoleEvent.step("BilibiliPublish", "✅ 发布成功! bvid=" + bvid));
                return VideoPublishResult.ok(bvid);
            }

            // 未提取到 BV 号，检查是否仍卡在编辑页（失败）还是已跳转（成功但无法确认）
            String currentUrl = page.url();
            log.info("发布后页面 URL: {}", currentUrl);
            if (!currentUrl.contains("draftId=" + draftAid)) {
                // 页面已跳离草稿编辑页，大概率发布成功，尝试从稿件列表补取 BV
                String fetched = fetchLatestBvid();
                if (fetched != null) {
                    return VideoPublishResult.ok(fetched);
                }
                return new VideoPublishResult(true, null, "发布已提交（未能确认 BV 号）", null);
            }
            saveDebugScreenshot(page, "publish_stuck");
            String reason = lastPublishError != null
                    ? "发布被拒: " + lastPublishError
                    : "点击投稿后页面未跳转，可能触发验证或表单校验未通过";
            return VideoPublishResult.fail(reason, draftUrl);

        } catch (Exception e) {
            log.error("Playwright 发布流程异常", e);
            return VideoPublishResult.fail("发布流程异常: " + e.getMessage(), draftUrl);
        } finally {
            closeQuietly(context, browser, playwright);
        }
    }

    // ==================== 页面操作 ====================

    /**
     * 补填创作声明（页面必填项，API 草稿无法写入）。
     * <p>
     * 多策略定位下拉框：placeholder → 标签关联 → 通用下拉类名。
     * 展开后优先选含"无/不涉及/原创"语义的选项，否则点第一个选项。
     * 失败不阻塞（发布按钮的表单校验会兜底提示）。
     */
    private void trySelectDeclaration(Page page) {
        try {
            // ---- 定位并点击下拉触发器（多策略） ----
            Locator trigger = findDeclarationTrigger(page);
            if (trigger == null) {
                log.info("创作声明: 未找到下拉框，可能已填写或页面结构变化");
                return;
            }
            trigger.scrollIntoViewIfNeeded();
            page.waitForTimeout(300);
            trigger.click();
            page.waitForTimeout(1000);
            saveDebugScreenshot(page, "declaration_opened");

            // ---- 点击选项（优先语义匹配，兜底第一个） ----
            if (clickDeclarationOption(page)) {
                page.waitForTimeout(500);
                return;
            }
            // 未找到选项，按 Escape 收起下拉
            page.keyboard().press("Escape");
            log.warn("创作声明: 下拉已展开但未找到可点选项");
        } catch (Exception e) {
            log.warn("创作声明: 选择失败（不阻塞）: {}", e.getMessage());
        }
    }

    /** 多策略定位创作声明下拉触发器 */
    private Locator findDeclarationTrigger(Page page) {
        String[] strategies = {
                // 策略1: placeholder 定位（截图中可见 placeholder="请选择符合您视频内容的创作声明"）
                "input[placeholder*='创作声明']",
                "[placeholder*='创作声明']",
                // 策略2: 标签文本关联（"创作声明" label 后面的下拉元素）
                "xpath=//*[normalize-space(text())='创作声明']/following::*[contains(@class,'select') or contains(@class,'trigger') or contains(@class,'input') or @role='combobox'][1]",
                // 策略3: 当前显示"请选择..."占位文本的元素
                "xpath=//*[contains(text(),'请选择符合您视频内容')][1]"
        };
        for (String sel : strategies) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    log.info("创作声明: 定位到下拉框, selector={}", sel);
                    return loc;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 在展开的下拉面板中点击一个选项 */
    private boolean clickDeclarationOption(Page page) {
        // 创作声明下拉的已知选项（来自实际页面截图）：
        // 内容无需标注 / 含AI生成内容 / 含虚构演绎内容 / 内容含营销信息 / 个人观点，仅供参考 / 内容为转载
        // 普通用户自制视频应选"内容无需标注"
        String[] preferredTexts = {"内容无需标注", "无需标注", "不涉及", "无声明"};
        for (String text : preferredTexts) {
            try {
                // 用精确文本匹配，getByText 只命中可见元素（下拉已展开）
                Locator opt = page.getByText(text, new Page.GetByTextOptions().setExact(false)).first();
                if (opt.count() > 0 && opt.isVisible()) {
                    String optText = opt.textContent();
                    opt.click();
                    log.info("创作声明: 已选择 \"{}\"", optText != null ? optText.trim() : text);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        // 兜底：仅在"下拉浮层容器"内找第一个条目，避免误点页面其他元素
        String[] fallbackSelectors = {
                "xpath=(//div[contains(@class,'select-dropdown') or contains(@class,'dropdown-menu') or contains(@class,'popper') or @role='listbox']//*[self::li or self::div][string-length(normalize-space(text()))>2])[1]"
        };
        for (String sel : fallbackSelectors) {
            try {
                Locator opt = page.locator(sel).first();
                if (opt.count() > 0 && opt.isVisible()) {
                    String optText = opt.textContent();
                    // 防御：不点含"模板""升级"等引导文案的元素
                    if (optText != null && (optText.contains("模板") || optText.contains("升级"))) {
                        continue;
                    }
                    opt.click();
                    log.info("创作声明: 已选择(浮层兜底) \"{}\"", optText != null ? optText.trim() : "");
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 将 cookie 文件中的最新 Cookie 注入浏览器上下文。
     * <p>
     * bilibili_browser_state.json 是扫码登录时的快照，其中 bili_jct（csrf 令牌）
     * 可能已被 B站轮换失效。发布时前端会用浏览器 cookie 中的 csrf 提交封面，
     * 旧令牌会导致 "封面上传失败: csrf 校验失败"。
     * 注入 cookie 文件（API 流程验证过可用）中的最新值可解决。
     */
    private void injectFreshCookies(BrowserContext context) {
        try {
            String cookieStr = cookieStore.load();
            if (cookieStr == null || cookieStr.isBlank()) {
                log.warn("cookie 文件为空，跳过注入");
                return;
            }
            java.util.List<com.microsoft.playwright.options.Cookie> cookies = new java.util.ArrayList<>();
            for (String pair : cookieStr.split(";")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank()) {
                    com.microsoft.playwright.options.Cookie c =
                            new com.microsoft.playwright.options.Cookie(kv[0].trim(), kv[1].trim());
                    c.setDomain(".bilibili.com");
                    c.setPath("/");
                    cookies.add(c);
                }
            }
            context.addCookies(cookies);
            log.info("已向浏览器上下文注入最新 Cookie ({} 个)", cookies.size());
        } catch (Exception e) {
            log.warn("注入 Cookie 失败（不阻塞）: {}", e.getMessage());
        }
    }

    /**
     * 关闭新手引导条和模态弹窗。
     * <p>
     * B站编辑器对新账号会显示引导气泡（"投稿模板功能升级"等）和可能的模态框，
     * 这些元素会拦截点击事件，必须在交互前清理。
     */
    private void dismissGuidePopups(Page page) {
        // 1. 关闭模态弹窗（一键填写等）：点右上角 ×
        String[] modalCloseSelectors = {
                "xpath=//div[contains(@class,'modal') or contains(@class,'dialog') or @role='dialog']//*[normalize-space(text())='×' or contains(@class,'close')][1]",
                ".dialog-close", ".modal-close", ".bili-modal-close"
        };
        for (String sel : modalCloseSelectors) {
            try {
                Locator close = page.locator(sel).first();
                if (close.count() > 0 && close.isVisible()) {
                    close.click();
                    log.info("已关闭模态弹窗: {}", sel);
                    page.waitForTimeout(400);
                }
            } catch (Exception ignored) {
            }
        }
        // 2. 关闭引导气泡：找可见的 × 关闭按钮（在引导文案附近的）
        String[] guideCloseSelectors = {
                "xpath=//*[contains(text(),'投稿模板功能升级')]/ancestor::*[contains(@class,'popover') or contains(@class,'tooltip') or contains(@class,'guide') or position()<=6][1]//*[normalize-space(text())='×'][1]",
                "xpath=//*[contains(text(),'信息填完后')]/ancestor::*[contains(@class,'popover') or contains(@class,'tooltip') or contains(@class,'guide') or position()<=6][1]//*[normalize-space(text())='×'][1]"
        };
        for (String sel : guideCloseSelectors) {
            try {
                Locator close = page.locator(sel).first();
                if (close.count() > 0 && close.isVisible()) {
                    close.click();
                    log.info("已关闭引导气泡: {}", sel);
                    page.waitForTimeout(300);
                }
            } catch (Exception ignored) {
            }
        }
        // 3. Escape 兜底收起浮层
        try {
            page.keyboard().press("Escape");
        } catch (Exception ignored) {
        }
    }

    /**
     * 点击"立即投稿"按钮。
     * <p>
     * 关键障碍是 B站新手引导气泡（"信息填完后，就可投稿！"）正好悬浮在按钮上方，
     * Playwright 普通点击会被拦截。策略：
     * <ol>
     *   <li>JS 移除所有 popover/tooltip/guide 遮挡层</li>
     *   <li>Playwright force 点击精确文本按钮</li>
     *   <li>JS 直接派发 click 事件（完全绕过 actionability 检查）</li>
     * </ol>
     */
    private boolean clickPublishButton(Page page) {
        // 第一步：JS 移除所有浮层遮挡（引导气泡、tooltip 等）
        removeOverlayGuides(page);

        // 第二步：Playwright force 点击（绕过遮挡检查）
        String[] texts = {"立即投稿", "提交稿件"};
        for (String text : texts) {
            try {
                Locator btn = page.getByText(text, new Page.GetByTextOptions().setExact(true)).last();
                if (btn.count() > 0 && btn.isVisible()) {
                    btn.scrollIntoViewIfNeeded();
                    page.waitForTimeout(300);
                    btn.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                    log.info("发布按钮已 force 点击: {}", text);
                    page.waitForTimeout(2000);
                    handleConfirmDialog(page);
                    return true;
                }
            } catch (Exception e) {
                log.debug("force 点击失败({}): {}", text, e.getMessage());
            }
        }

        // 第三步：JS 直接点击（最后的兜底，绕过一切检查）
        try {
            Object result = page.evaluate(
                    "() => {"
                            + "  const isVisible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);"
                            + "  const all = [...document.querySelectorAll('button,div,span,a')];"
                            + "  for (const t of ['立即投稿', '提交稿件']) {"
                            + "    const exact = all.filter(e => e.textContent.trim() === t && isVisible(e));"
                            + "    if (exact.length) {"
                            + "      const el = exact[exact.length - 1];"
                            + "      el.scrollIntoView({block: 'center'});"
                            + "      el.click();"
                            + "      return t;"
                            + "    }"
                            + "  }"
                            + "  return null;"
                            + "}");
            if (result != null && !"null".equals(String.valueOf(result))) {
                log.info("发布按钮已通过 JS 点击: {}", result);
                page.waitForTimeout(2000);
                handleConfirmDialog(page);
                return true;
            }
        } catch (Exception e) {
            log.debug("JS 点击失败: {}", e.getMessage());
        }
        log.warn("未找到可点击的发布按钮");
        return false;
    }

    /**
     * JS 移除页面上的浮层遮挡（引导气泡/tooltip/popover）。
     * 这些气泡会拦截对发布按钮的点击。
     */
    private void removeOverlayGuides(Page page) {
        try {
            Object removed = page.evaluate(
                    "() => {"
                            + "  const sels = '[class*=\"popover\"],[class*=\"tooltip\"],[class*=\"guide\"],"
                            + "[class*=\"bubble\"],[class*=\"popper\"],[class*=\"float\"]';"
                            + "  let n = 0;"
                            + "  document.querySelectorAll(sels).forEach(e => {"
                            + "    const t = e.textContent || '';"
                            + "    if (t.includes('信息填完后') || t.includes('投稿模板') || t.includes('一键填写稿件')) {"
                            + "      e.remove(); n++;"
                            + "    }"
                            + "  });"
                            + "  return n;"
                            + "}");
            log.info("已移除 {} 个引导浮层", removed);
        } catch (Exception e) {
            log.debug("移除引导浮层失败: {}", e.getMessage());
        }
    }

    /**
     * 处理点击投稿后可能出现的确认弹窗（如"确认投稿？"）。
     */
    private void handleConfirmDialog(Page page) {
        String[] selectors = {
                "div[role='dialog'] button:has-text('确认')",
                "div[role='dialog'] button:has-text('确定')",
                "xpath=//div[contains(@class,'modal') or contains(@class,'dialog')]//button[contains(.,'确认') or contains(.,'确定') or contains(.,'投稿')][1]"
        };
        for (String sel : selectors) {
            try {
                Locator btn = page.locator(sel).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    btn.click(new Locator.ClickOptions().setForce(true));
                    log.info("已点击确认弹窗按钮: {}", sel);
                    page.waitForTimeout(1000);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 等待发布结果：成功跳转 / geetest 验证 / 失败提示。
     *
     * @return BV 号，未提取到返回 null
     */
    private String waitForPublishResult(Page page, long draftAid) {
        long deadline = System.currentTimeMillis() + PUBLISH_WAIT_MS;
        boolean geetestNotified = false;
        boolean errorNotified = false;
        long lastHeartbeat = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            // 每 15 秒输出一次心跳日志（URL + 页面是否含成功文案），便于诊断
            if (System.currentTimeMillis() - lastHeartbeat > 15_000) {
                lastHeartbeat = System.currentTimeMillis();
                try {
                    String html = page.content();
                    log.info("等待发布结果中... url={}, 含投递成功={}", page.url(), html.contains("投递成功"));
                } catch (Exception ignored) {
                }
            }
            // 检查页面 URL 中直接出现 BV（投稿成功跳转）
            String url = page.url();
            Matcher urlMatch = BV_IN_URL.matcher(url);
            if (urlMatch.find()) {
                return urlMatch.group(1);
            }

            // 投稿成功页（URL 含 success 但仍在 /upload/ 路径下）
            if (url.contains("success")) {
                log.info("页面跳转到投稿成功页: {}", url);
                String content = page.content();
                Matcher m = BV_PATTERN.matcher(content);
                if (m.find()) {
                    return m.group(1);
                }
                return fetchLatestBvid();
            }

            // 检查错误弹窗（如 "封面上传失败: csrf 校验失败"）
            if (!errorNotified) {
                String errorText = detectErrorDialog(page);
                if (errorText != null) {
                    errorNotified = true;
                    lastPublishError = errorText;
                    log.warn("检测到错误弹窗: {}", errorText);
                    saveDebugScreenshot(page, "error_dialog");
                    // 关闭错误弹窗，避免遮挡后续操作
                    handleConfirmDialog(page);
                }
            }

            // 检查 geetest 验证弹窗
            if (!geetestNotified && isGeetestVisible(page)) {
                geetestNotified = true;
                log.warn("检测到 geetest 安全验证弹窗，等待人工完成（最长 {} 秒）...", PUBLISH_WAIT_MS / 1000);
                saveDebugScreenshot(page, "geetest");
            }

            // 检查成功提示元素（逐个文本匹配，避免逗号列表在 text 引擎下的兼容问题）
            if (isPublishSuccessVisible(page)) {
                String content = page.content();
                Matcher m = BV_PATTERN.matcher(content);
                if (m.find()) {
                    return m.group(1);
                }
                return fetchLatestBvid();
            }

            // 点击后可能出现确认弹窗，循环中持续处理
            handleConfirmDialog(page);

            // 页面已跳离草稿编辑页（无错误提示）→ 可能成功，去查 BV
            if (!url.contains("draftId=" + draftAid) && !url.contains("/upload/")) {
                log.info("页面已跳离编辑页: {}", url);
                String bvid = fetchLatestBvid();
                if (bvid != null) {
                    return bvid;
                }
            }

            page.waitForTimeout(1000);
        }
        log.warn("等待发布结果超时");
        return null;
    }

    /**
     * 检测投稿是否成功（页面显示"稿件投递成功"等文案）。
     * <p>
     * 双保险：先逐个文本做可见性匹配（逗号选择器列表在 text 引擎下不可靠），
     * 再对整页 HTML 做字符串包含检查兜底。
     */
    private boolean isPublishSuccessVisible(Page page) {
        String[] successTexts = {"稿件投递成功", "投递成功", "投稿成功", "稿件提交成功", "已进入审核"};
        for (String text : successTexts) {
            try {
                Locator el = page.getByText(text, new Page.GetByTextOptions().setExact(false)).first();
                if (el.count() > 0 && el.isVisible()) {
                    log.info("检测到发布成功提示(元素): {}", text);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        // HTML 包含检查兜底
        try {
            String html = page.content();
            if (html.contains("投递成功") || html.contains("投稿成功") || html.contains("稿件提交成功")) {
                log.info("检测到发布成功提示(HTML)");
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 发布过程中检测到的错误信息（用于失败时回传真实原因） */
    private volatile String lastPublishError;

    /**
     * 检测页面上的错误提示弹窗（如 "封面上传失败: csrf 校验失败"）。
     *
     * @return 错误文本，无错误弹窗返回 null
     */
    private String detectErrorDialog(Page page) {
        String[] errorTexts = {"上传失败", "校验失败", "投稿失败", "发布失败"};
        for (String text : errorTexts) {
            try {
                Locator error = page.getByText(text, new Page.GetByTextOptions().setExact(false)).first();
                if (error.count() > 0 && error.isVisible()) {
                    String content = error.textContent();
                    return content != null ? content.trim() : text;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 检测 geetest 验证弹窗是否可见。
     */
    private boolean isGeetestVisible(Page page) {
        try {
            return page.locator(
                    ".geetest_panel, .geetest_box, iframe[src*='geetest'], [class*='geetest']"
            ).first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过创作中心稿件列表 API 查询最新稿件的 BV 号。
     */
    private String fetchLatestBvid() {
        try {
            String cookie = cookieStore.load();
            if (cookie == null || cookie.isBlank()) {
                return null;
            }
            Request request = new Request.Builder()
                    .url(ARCHIVES_URL)
                    .header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://member.bilibili.com/platform/home")
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(request).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.warn("查询稿件列表失败: HTTP {}", resp.code());
                    return null;
                }
                Matcher m = BV_PATTERN.matcher(body);
                if (m.find()) {
                    log.info("从稿件列表提取到 BV 号: {}", m.group(1));
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            log.warn("查询稿件列表异常: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 浏览器管理 ====================

    private Browser launchBrowser(Playwright playwright) {
        // 1) 系统 Chrome
        try {
            return playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(publishHeadless)
                    .setSlowMo(50)
                    .setChannel("chrome"));
        } catch (Exception e) {
            log.debug("系统 Chrome 不可用，尝试 Edge...");
        }
        // 2) 系统 Edge
        try {
            return playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(publishHeadless)
                    .setSlowMo(50)
                    .setChannel("msedge"));
        } catch (Exception e) {
            log.debug("系统 Edge 不可用，尝试内置 Chromium...");
        }
        // 3) 内置 Chromium
        Path chromiumDir = findChromiumDir();
        if (chromiumDir != null) {
            Path exe = chromiumDir.resolve("chrome-win").resolve("chrome.exe");
            if (Files.exists(exe)) {
                return playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(publishHeadless)
                        .setSlowMo(50)
                        .setExecutablePath(exe));
            }
        }
        return null;
    }

    private Path findChromiumDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) {
            return null;
        }
        Path base = Path.of(localAppData, "ykd-summer", "chromium");
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, "chromium-*")) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    return entry;
                }
            }
        } catch (IOException e) {
            log.debug("扫描 Chromium 目录失败: {}", e.getMessage());
        }
        return null;
    }

    private void saveDebugScreenshot(Page page, String tag) {
        try {
            Path dir = Path.of(System.getProperty("user.dir"), "data");
            Files.createDirectories(dir);
            Path file = dir.resolve("publish_" + tag + "_" + System.currentTimeMillis() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
            log.info("调试截图已保存: {}", file);
        } catch (Exception e) {
            log.warn("保存调试截图失败: {}", e.getMessage());
        }
    }

    private void closeQuietly(BrowserContext context, Browser browser, Playwright playwright) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
    }
}
