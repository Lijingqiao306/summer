package org.example.ykd_summer.service.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.BrowserService;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 浏览器自动化引擎实现，封装 Playwright 网页能力（状态管理、页面交互、Chromium 生命周期）。
 * <p>
 * 作为 BrowserTools（Spring AI Tool 入口）的底层支撑，BrowserTools 的每个
 * {@code @Tool} 方法直接委托到本类同名方法。
 * </p>
 *
 * @see BrowserService
 * @see org.example.ykd_summer.tools.BrowserTools
 */
@Slf4j
@Service
public class BrowserServiceImpl implements BrowserService {

    // ========== 静态常量 ==========

    private static final String CHROMIUM_DIR_PREFIX = "chromium-";
    private static final String CHROMIUM_REVISION = "1148";
    private static final String MIRROR_BASE_URL =
            "https://cdn.npmmirror.com/binaries/playwright/builds/chromium/" + CHROMIUM_REVISION + "/";
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 300;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_TOOL_CALLS = 12;
    private static final boolean HEADLESS;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        HEADLESS = !(os.contains("win") || os.contains("mac"));
        log.info("BrowserServiceImpl 静态初始化：os={}, headless={}", os, HEADLESS);
    }

    // ========== 内部枚举 ==========

    private enum ChromiumState {
        UNINITIALIZED, AVAILABLE, DOWNLOADING, UNAVAILABLE
    }

    // ========== 实例状态 ==========

    private final AtomicReference<ChromiumState> state =
            new AtomicReference<>(ChromiumState.UNINITIALIZED);
    private volatile long downloadStartTime;

    private Browser activeBrowser;
    private Playwright activePlaywright;
    Page activePage;
    BrowserContext activeContext;
    final List<Page> allPages = new ArrayList<>();
    final AtomicInteger toolCallCount = new AtomicInteger(0);

    // ========== 页面打开 ==========

    @Override
    public String openWebPage(String url) {
        switch (state.get()) {
            case UNINITIALIZED:
                return handleUninitialized(url);
            case AVAILABLE:
                return openWithPlaywright(url);
            case DOWNLOADING:
                return handleDownloading();
            case UNAVAILABLE:
                return handleUnavailable();
            default:
                return "浏览器功能异常，未知状态。";
        }
    }

    /** UNINITIALIZED 状态：磁盘探测 → 已安装则 AVAILABLE，未安装则后台下载 */
    private synchronized String handleUninitialized(String url) {
        if (state.get() != ChromiumState.UNINITIALIZED) {
            return openWebPage(url);
        }
        Path chromiumDir = findChromiumDir();
        if (chromiumDir != null) {
            log.info("检测到 Chromium 已安装：{}", chromiumDir);
            state.set(ChromiumState.AVAILABLE);
            return openWebPage(url);
        }
        log.warn("未检测到 Chromium，启动后台自动下载（revision {}）...", CHROMIUM_REVISION);
        startDownload();
        return "Chromium 浏览器未安装，正在后台自动下载（约 137 MB，预计 1~3 分钟），下载完成后请重新发送指令。";
    }

    /** DOWNLOADING 状态：告知等待 */
    private String handleDownloading() {
        long elapsedSeconds = (System.currentTimeMillis() - downloadStartTime) / 1000;
        long remainingMinutes = Math.max(1, 2 - elapsedSeconds / 60);
        return String.format(
                "Chromium 仍在后台安装中，已等待约 %d 秒，预计还需 %d 分钟，下载完成后请重新发送指令。",
                elapsedSeconds, remainingMinutes);
    }

    /** UNAVAILABLE 状态：重试下载 */
    private String handleUnavailable() {
        log.info("上次安装失败，重新尝试下载 Chromium（revision {}）...", CHROMIUM_REVISION);
        startDownload();
        return "上次自动下载 Chromium 失败，正在重新尝试（约 137 MB，预计 1~3 分钟），下载完成后请重新发送指令。";
    }

    /** AVAILABLE 状态：使用 Playwright 打开网页 */
    private String openWithPlaywright(String url) {
        String limitMsg = checkToolCallLimit();
        if (limitMsg != null) return limitMsg;

        // 幂等性检查
        if (activePage != null) {
            try {
                String currentUrl = activePage.url();
                if (currentUrl != null && currentUrl.equals(url)) {
                    log.info("页面已打开，跳过重复导航：{}", url);
                    return buildPageSummary("页面已处于打开状态");
                }
                // 同源有意义页面守卫
                if (currentUrl != null
                        && isSameOrigin(currentUrl, url)
                        && isMeaningfulStatePage(currentUrl)
                        && isHomepageLike(url)) {
                    log.warn("当前活跃页面 {} 已是同源有意义状态页，忽略 openWebPage({})", currentUrl, url);
                    return buildPageSummary(
                            "当前活跃页面已是目标网站的有意义状态页（" + currentUrl
                                    + "），已忽略重复打开首页的请求，避免覆盖已有搜索/视频/赛事上下文。"
                                    + "请直接调用 getPageSnapshot 获取当前页面的可交互元素继续操作；"
                                    + "如需查看其他标签页，可调用 listTabs/switchTab；"
                                    + "如确实要返回该网站首页，请先确认其他标签页已处理完毕。");
                }
            } catch (Exception e) {
                log.debug("检查当前页面 URL 失败：{}", e.getMessage());
            }
        }

        // 浏览器已存在 → 当前窗口内导航
        if (activePage != null) {
            try {
                toolCallCount.set(0);
                log.info("在现有浏览器窗口中导航到：{}", url);
                activePage.navigate(url, new Page.NavigateOptions().setTimeout(30000));
                activePage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(10000));
                log.info("已在现有窗口中打开网页：{}", url);
                return buildPageSummary("已完成");
            } catch (Exception e) {
                log.warn("在现有窗口中导航失败，回退到新建浏览器：{}", e.getMessage());
                closeActiveBrowser();
            }
        }

        // 创建全新浏览器
        try {
            toolCallCount.set(0);
            activePlaywright = Playwright.create();
            activeBrowser = launchBrowserWithFallback();
            activeContext = activeBrowser.newContext();
            allPages.clear();

            activeContext.onPage(page -> {
                allPages.add(page);
                log.info("检测到新标签页打开: index={}, url={}", allPages.size() - 1, page.url());
            });

            activePage = activeContext.newPage();
            allPages.add(activePage);
            activePage.setDefaultTimeout(15000);
            log.info("正在以 {} 模式打开网页：{}", HEADLESS ? "无头" : "可见窗口", url);
            activePage.navigate(url, new Page.NavigateOptions().setTimeout(30000));
            activePage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10000));
            log.info("已通过 Playwright 打开网页，窗口保持可见：{}", url);
            return buildPageSummary("已完成");
        } catch (Exception e) {
            log.error("Playwright 打开网页失败：{}", url, e);
            closeActiveBrowser();
            return "无法使用：浏览器自动化功能暂时不可用，请稍后重试。";
        }
    }

    // ========== 页面快照 ==========

    @Override
    public String getPageSnapshot(Integer maxElements) {
        if (activePage == null) {
            return "当前没有打开的网页，请先使用 openWebPage 打开网页后再获取快照。";
        }
        String limitMsg = checkToolCallLimit();
        if (limitMsg != null) return limitMsg;

        final int limit = (maxElements != null && maxElements > 0) ? maxElements : 80;
        try {
            Object result = activePage.evaluate(
                """
                limit => {
                  const selectors = 'a[href], button, input, textarea, select, \
                    [role="button"], [role="link"], [role="searchbox"], [role="tab"], \
                    [onclick], summary, option';
                  const all = document.querySelectorAll(selectors);
                  const items = [];
                  document.querySelectorAll('[data-tool-ref]').forEach(el =>
                    el.removeAttribute('data-tool-ref'));
                  for (const el of all) {
                    const rect = el.getBoundingClientRect();
                    if (rect.width < 10 && rect.height < 10) continue;
                    const style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    if (el.tagName === 'INPUT' && el.type === 'hidden') continue;
                    const id = items.length + 1;
                    el.setAttribute('data-tool-ref', id);
                    const tag = el.tagName.toLowerCase();
                    let typeHint = '';
                    if (tag === 'input') {
                      typeHint = '|' + (el.type || 'text');
                    }
                    let text = (el.textContent || el.placeholder || el.getAttribute('aria-label') || '').trim();
                    text = text.replace(/\\s+/g, ' ');
                    if (text.length > 80) text = text.substring(0, 77) + '...';
                    items.push('[' + id + '] <' + tag + typeHint + '> ' + text);
                    if (items.length >= limit) break;
                  }
                  return JSON.stringify(items);
                }
                """, limit);

            String elementList = result != null ? result.toString() : "[]";

            StringBuilder elements = new StringBuilder();
            if (elementList.startsWith("[")) {
                String inner = elementList.substring(1, elementList.length() - 1);
                boolean first = true;
                StringBuilder line = new StringBuilder();
                boolean inString = false;
                boolean escaped = false;
                for (int i = 0; i < inner.length(); i++) {
                    char c = inner.charAt(i);
                    if (escaped) { line.append(c); escaped = false; continue; }
                    if (c == '\\') { line.append(c); escaped = true; continue; }
                    if (c == '"') { inString = !inString; line.append(c); continue; }
                    if (c == ',' && !inString) {
                        String s = line.toString().trim();
                        if (s.length() > 3 && s.startsWith("\"") && s.endsWith("\"")) {
                            s = s.substring(1, s.length() - 1);
                        }
                        if (!s.isEmpty()) {
                            if (!first) elements.append("\n");
                            elements.append(s);
                            first = false;
                        }
                        line = new StringBuilder();
                        continue;
                    }
                    line.append(c);
                }
                String s = line.toString().trim();
                if (s.length() > 3 && s.startsWith("\"") && s.endsWith("\"")) {
                    s = s.substring(1, s.length() - 1);
                }
                if (!s.isEmpty()) {
                    if (!first) elements.append("\n");
                    elements.append(s);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(buildStatusHeader()).append("\n");

            String elemStr = elements.toString();
            int count = elemStr.isEmpty() ? 0 : elemStr.split("\n").length;
            sb.append("可交互元素（共 ").append(count).append(" 个，" +
                    "使用 ref 编号如 [15] 可精确定位）：\n");
            sb.append(elemStr);

            return sb.toString();
        } catch (Exception e) {
            log.error("获取页面快照失败", e);
            return "获取页面快照失败：" + e.getMessage();
        }
    }

    // ========== 页面内容获取 ==========

    @Override
    public String getPageContent() {
        if (activePage == null) {
            return "当前没有打开的网页，请先使用 openWebPage 打开网页后再获取内容。";
        }
        String limitMsg = checkToolCallLimit();
        if (limitMsg != null) return limitMsg;

        try {
            String body = safeGet(() -> {
                Object result = activePage.evaluate(
                    "() => {" +
                    "  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);" +
                    "  let s = '';" +
                    "  while (w.nextNode()) {" +
                    "    const t = w.currentNode.textContent;" +
                    "    if (t) s += t.trim() + ' ';" +
                    "    if (s.length > 3100) return s.substring(0, 3000) + '...';" +
                    "  }" +
                    "  return s.substring(0, Math.min(s.length, 3000));" +
                    "}");
                return result != null ? result.toString() : "";
            }, "(页面内容为空)");

            String links = safeGet(() -> {
                Object result = activePage.evaluate(
                    "() => {" +
                    "  const all = document.querySelectorAll('a[href]');" +
                    "  const items = [];" +
                    "  document.querySelectorAll('a[data-tool-ref]').forEach(el => " +
                    "    el.removeAttribute('data-tool-ref'));" +
                    "  for (const a of all) {" +
                    "    const t = a.textContent.trim();" +
                    "    if (!t || t.length >= 100) continue;" +
                    "    const id = items.length + 1;" +
                    "    a.setAttribute('data-tool-ref', id);" +
                    "    items.push('[' + id + '] ' + t);" +
                    "    if (items.length >= 100) break;" +
                    "  }" +
                    "  return items.length > 0 ? items.join('\\n') : '(无可用链接)';" +
                    "}");
                return result != null ? result.toString() : "(无可用链接)";
            }, "(链接获取失败)");

            StringBuilder sb = new StringBuilder();
            sb.append(buildStatusHeader()).append("\n");
            sb.append("可点击的链接（带 ref 编号，可使用 clickPageElement([N]) 精确定位）：\n")
                    .append(links).append("\n\n");
            if (body != null && !body.isEmpty()) {
                int maxLen = Math.min(body.length(), 3000);
                sb.append("页面正文（前").append(maxLen).append("字）：\n")
                        .append(body.substring(0, maxLen));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取页面内容失败", e);
            return "获取页面内容失败：" + e.getMessage();
        }
    }

    // ========== 页面点击 ==========

    @Override
    public String clickPageElement(String target) {
        if (activePage == null) {
            return "当前没有打开的网页，请先使用 openWebPage 打开网页后再执行点击操作。";
        }
        String limitMsg = checkToolCallLimit();
        if (limitMsg != null) return limitMsg;

        Pattern refPattern = Pattern.compile("^\\[(\\d+)\\]$");
        Matcher refMatcher = refPattern.matcher(target.trim());
        if (refMatcher.matches()) {
            int refId = Integer.parseInt(refMatcher.group(1));
            try {
                Object clicked = activePage.evaluate(
                    "id => {" +
                    "  const el = document.querySelector('[data-tool-ref=\"' + id + '\"]');" +
                    "  if (!el) return 0;" +
                    "  el.scrollIntoView({block: 'center'});" +
                    "  el.click();" +
                    "  return 1;" +
                    "}", refId);
                int success = clicked instanceof Number ? ((Number) clicked).intValue() : 0;
                if (success > 0) {
                    log.info("ref ID 点击成功：[{}]", refId);
                    return buildClickResult("[" + refId + "]");
                }
                log.debug("ref ID 点击失败：未找到 data-tool-ref={}", refId);
            } catch (Exception e) {
                log.debug("ref ID 点击异常：[{}]: {}", refId, e.getMessage());
            }
            return "未找到 ref 编号为 " + refId + " 的元素，请使用 getPageSnapshot 刷新页面元素列表后重试。";
        }

        try {
            // 精确文本匹配
            try {
                activePage.getByText(target, new Page.GetByTextOptions().setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(3000));
                log.info("精确文本匹配点击成功：{}", target);
                return buildClickResult(target);
            } catch (Exception ignored) { /* fall through */ }

            // 模糊文本匹配
            try {
                activePage.getByText(target).first()
                        .click(new Locator.ClickOptions().setTimeout(3000));
                log.info("模糊文本匹配点击成功：{}", target);
                return buildClickResult(target);
            } catch (Exception ignored) { /* fall through */ }

            // CSS 选择器
            try {
                activePage.locator(target).first()
                        .click(new Locator.ClickOptions().setTimeout(3000));
                log.info("CSS 选择器点击成功：{}", target);
                return buildClickResult(target);
            } catch (Exception ignored) { /* fall through */ }

            // JS textContent 包含匹配（兜底）
            try {
                String escaped = target.replace("\\", "\\\\").replace("'", "\\'");
                Object countObj = activePage.evaluate(
                    "target => {" +
                    "  const all = document.querySelectorAll('a, button, [role=link], [onclick]');" +
                    "  const results = [];" +
                    "  for (const el of all) {" +
                    "    if (el.textContent && el.textContent.includes(target)) {" +
                    "      results.push(el);" +
                    "    }" +
                    "  }" +
                    "  if (results.length === 0) return 0;" +
                    "  results[0].click();" +
                    "  return 1;" +
                    "}", escaped);
                int clicked = countObj instanceof Number ? ((Number) countObj).intValue() : 0;
                if (clicked > 0) {
                    log.info("JS textContent 匹配点击成功：{}", target);
                    return buildClickResult(target);
                }
            } catch (Exception ignored) { /* fall through */ }

            return "未找到匹配的页面元素：「" + target + "」，请使用 getPageContent 查看页面内容后重新尝试。";
        } catch (Exception e) {
            log.error("点击页面元素失败：{}", target, e);
            return "点击失败：「" + target + "」，错误：" + e.getMessage();
        }
    }

    // ========== 文本输入 ==========

    @Override
    public String typeText(String target, String text) {
        if (activePage == null) {
            return "当前没有打开的网页，请先使用 openWebPage 打开网页后再输入文字。";
        }

        boolean submitWithEnter = text.endsWith("\n") || text.endsWith("\r\n");
        String actualText = text;
        if (submitWithEnter) {
            actualText = text.endsWith("\r\n")
                    ? text.substring(0, text.length() - 2)
                    : text.substring(0, text.length() - 1);
        }

        Pattern refPattern = Pattern.compile("^\\[(\\d+)\\]$");
        Matcher refMatcher = refPattern.matcher(target.trim());
        if (refMatcher.matches()) {
            int refId = Integer.parseInt(refMatcher.group(1));
            int preOpTabCount = allPages.size();
            try {
                Object filled = activePage.evaluate(
                    "args => {" +
                    "  const id = args[0];" +
                    "  const text = args[1];" +
                    "  const submitAfter = args[2];" +
                    "  const el = document.querySelector('[data-tool-ref=\"' + id + '\"]');" +
                    "  if (!el) return 0;" +
                    "  const tag = el.tagName;" +
                    "  if (tag !== 'INPUT' && tag !== 'TEXTAREA' && el.getAttribute('role') !== 'searchbox') return 0;" +
                    "  el.focus();" +
                    "  el.value = text;" +
                    "  el.dispatchEvent(new Event('input', {bubbles: true}));" +
                    "  el.dispatchEvent(new Event('change', {bubbles: true}));" +
                    "  if (submitAfter) {" +
                    "    el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
                    "    el.dispatchEvent(new KeyboardEvent('keypress', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
                    "    el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
                    "    const form = el.closest('form');" +
                    "    if (form) {" +
                    "      form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));" +
                    "      try { form.submit(); } catch (e) {}" +
                    "    }" +
                    "  }" +
                    "  return 1;" +
                    "}", List.of(refId, actualText, submitWithEnter));
                int success = filled instanceof Number ? ((Number) filled).intValue() : 0;
                if (success > 0) {
                    if (submitWithEnter) {
                        log.info("ref ID 输入并回车提交：[{}] → {} (含 Enter)", refId, actualText);
                        return detectAndReportPage(preOpTabCount,
                                String.format("已在 [%d] 输入框中输入并按 Enter 提交：「%s」", refId, actualText),
                                "[" + refId + "]");
                    }
                    log.info("ref ID 输入成功：[{}] → {}", refId, actualText);
                    return "已在 [" + refId + "] 输入框中输入：「" + actualText + "」";
                }
            } catch (Exception ignored) {
                log.debug("ref ID 输入异常：[{}]: {}", refId, ignored.getMessage());
            }
            return "ref 编号 " + refId + " 对应的元素不是输入框，请使用 getPageSnapshot 确认元素类型后重试。";
        }

        try {
            try {
                activePage.getByPlaceholder(target).first().fill(text);
                log.info("通过 placeholder 输入成功：{} → {}", target, text);
                return "已在「" + target + "」输入框中输入：「" + text + "」";
            } catch (Exception ignored) { /* fall through */ }

            try {
                activePage.locator("input[aria-label*='" + target + "'], " +
                        "textarea[aria-label*='" + target + "'], " +
                        "[role='searchbox'][aria-label*='" + target + "']").first().fill(text);
                log.info("通过 aria-label 输入成功：{} → {}", target, text);
                return "已在「" + target + "」输入框中输入：「" + text + "」";
            } catch (Exception ignored) { /* fall through */ }

            try {
                activePage.getByLabel(target).first().fill(text);
                log.info("通过 label 输入成功：{} → {}", target, text);
                return "已在「" + target + "」输入框中输入：「" + text + "」";
            } catch (Exception ignored) { /* fall through */ }

            try {
                activePage.locator("input[type='search'], input[type='text'], " +
                        "input:not([type]), [role='searchbox']").first().fill(text);
                log.info("通过通用选择器输入成功：{}", text);
                return "已在输入框中输入：「" + text + "」（使用通用匹配策略）";
            } catch (Exception ignored) { /* fall through */ }

            return "未找到可输入的目标输入框：「" + target + "」，请使用 getPageContent 查看页面结构后重试。";
        } catch (Exception e) {
            log.error("输入文字失败：target={}, text={}", target, text, e);
            return "输入失败：错误：" + e.getMessage();
        }
    }

    // ========== 标签页管理 ==========

    @Override
    public String listTabs() {
        if (allPages.isEmpty()) {
            return "当前没有打开的标签页。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已打开的标签页（共 ").append(allPages.size()).append(" 个）：\n");
        for (int i = 0; i < allPages.size(); i++) {
            final int idx = i;
            String marker = (allPages.get(idx) == activePage) ? " [当前]" : "";
            String url = safeGet(() -> allPages.get(idx).url(), "加载中...");
            String title = safeGet(() -> {
                String t = allPages.get(idx).title();
                return t != null && t.length() > 60 ? t.substring(0, 57) + "..." : t;
            }, "(无标题)");
            sb.append("  ").append(i).append(". ").append(title != null ? title : "(无标题)")
              .append(marker).append("\n     ").append(url).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String switchTab(int index) {
        if (allPages.isEmpty()) {
            return "当前没有打开的标签页。";
        }
        if (index < 0 || index >= allPages.size()) {
            return "标签页索引 " + index + " 无效，有效范围：0 ~ " + (allPages.size() - 1);
        }
        Page targetPage = allPages.get(index);
        try {
            targetPage.bringToFront();
        } catch (Exception e) {
            log.debug("bringToFront 失败（headless 模式下正常）: {}", e.getMessage());
        }
        activePage = targetPage;
        String url = safeGet(() -> targetPage.url(), "加载中...");
        String title = safeGet(() -> targetPage.title(), "未知标题");
        return "已切换到标签页 " + index + "\n页面标题：" + title + "\nURL：" + url;
    }

    // ========== 页面报告辅助 ==========

    private String buildPageSummary(String prefix) {
        String summary = extractPageSummary(activePage);
        String header = buildStatusHeader();
        return String.format("%s\n\n%s\n页面摘要：%s", prefix, header, summary);
    }

    private String extractPageSummary(Page page) {
        return safeGet(() -> {
            Object result = page.evaluate(
                "() => {" +
                "  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);" +
                "  let s = '';" +
                "  while (w.nextNode()) {" +
                "    const t = w.currentNode.textContent;" +
                "    if (t) s += t.trim() + ' ';" +
                "    if (s.length > 600) return s.substring(0, 500) + '...';" +
                "  }" +
                "  return s.substring(0, Math.min(s.length, 500));" +
                "}");
            return result != null ? result.toString() : "";
        }, "(正文获取失败)");
    }

    private String buildStatusHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("已打开的标签页（共 ").append(allPages.size()).append(" 个）：\n");
        for (int i = 0; i < allPages.size(); i++) {
            final int idx = i;
            String marker = (allPages.get(idx) == activePage) ? " ← 当前" : "";
            String url = safeGet(() -> allPages.get(idx).url(), "加载中...");
            String title = safeGet(() -> {
                String t = allPages.get(idx).title();
                return t != null && t.length() > 50 ? t.substring(0, 47) + "..." : t;
            }, "(无标题)");
            sb.append("  ").append(i).append(". ").append(title != null ? title : "")
              .append(marker).append("\n  ").append(url).append("\n");
        }
        String currentUrl = safeGet(() -> activePage.url(), "未知");
        String currentTitle = safeGet(() -> activePage.title(), "未知");
        sb.append("当前页面: ").append(currentTitle).append("\n");
        sb.append("当前URL: ").append(currentUrl).append("\n");

        if (currentUrl != null) {
            String lower = currentUrl.toLowerCase();
            if (lower.contains("/video/") || lower.contains("/watch") || lower.contains("/play")) {
                sb.append("【此页面是视频/媒体播放页面 → 任务已完成，停止所有操作】\n");
            } else if (lower.contains("/search") || lower.contains("?q=") || lower.contains("?keyword=")) {
                sb.append("【此页面是搜索结果页 → 查看结果，点击目标】\n");
            }
        }
        return sb.toString();
    }

    private String checkToolCallLimit() {
        int count = toolCallCount.incrementAndGet();
        if (count > MAX_TOOL_CALLS) {
            String url = safeGet(() -> activePage.url(), "未知URL");
            String title = safeGet(() -> activePage.title(), "未知标题");
            return String.format(
                    "🔴 [操作次数已达上限 %d 次] 为避免无限循环，当前浏览器会话已强制终止。"
                    + "\n当前页面标题：%s\n当前URL：%s\n"
                    + "请立即停止所有操作，直接向用户汇报当前页面信息。不要再调用任何浏览器工具。",
                    MAX_TOOL_CALLS, title, url);
        }
        return null;
    }

    // ========== URL 工具 ==========

    private static boolean isSameOrigin(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        try {
            URI u1 = URI.create(url1);
            URI u2 = URI.create(url2);
            String h1 = u1.getHost();
            String h2 = u2.getHost();
            if (h1 == null || h2 == null) return false;
            return geteTLDPlusOne(h1).equals(geteTLDPlusOne(h2));
        } catch (Exception e) {
            return false;
        }
    }

    private static String geteTLDPlusOne(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static boolean isMeaningfulStatePage(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            return path != null && path.length() > 1 && !path.equals("/");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHomepageLike(String url) {
        if (url == null) return false;
        return url.matches("https?://[^/?#]+/?");
    }

    // ========== 浏览器启动 ==========

    private Browser launchBrowserWithFallback() {
        try {
            Browser browser = activePlaywright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(HEADLESS).setChannel("chrome"));
            log.info("使用 Google Chrome 启动浏览器");
            return browser;
        } catch (Exception e) { /* Chrome 不可用 */ }

        try {
            Browser browser = activePlaywright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(HEADLESS).setChannel("msedge"));
            log.info("使用 Microsoft Edge 启动浏览器");
            return browser;
        } catch (Exception e) { /* Edge 不可用 */ }

        Path chromiumDir = findChromiumDir();
        Path chromeExe = chromiumDir.resolve("chrome-win").resolve("chrome.exe");
        log.info("Chrome/Edge 均不可用，降级使用自下载 Chromium（不支持 H.264 视频播放）");
        return activePlaywright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(HEADLESS)
                        .setExecutablePath(chromeExe));
    }

    // ========== 操作后页面检测 ==========

    private String buildClickResult(String target) {
        return detectAndReportPage(allPages.size(), "已点击", target);
    }

    private String detectAndReportPage(int preTabCount, String opDesc, String target) {
        try {
            Thread.sleep(800);

            boolean newTabOpened = allPages.size() > preTabCount;
            if (newTabOpened) {
                int newTabIndex = allPages.size() - 1;
                Page newTab = allPages.get(newTabIndex);
                try {
                    activePage = newTab;
                    newTab.bringToFront();
                } catch (Exception e) {
                    log.debug("新标签页 bringToFront 失败: {}", e.getMessage());
                }
                String newUrl = safeGet(() -> newTab.url(), "加载中...");
                String newTitle = safeGet(() -> newTab.title(), "未知标题");
                log.info("检测到新标签页打开: index={}, url={}", newTabIndex, newUrl);
                try {
                    newTab.waitForLoadState(LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(8000));
                } catch (Exception ignored) { /* 超时不阻断 */ }
                String summary = extractPageSummary(newTab);
                String header = buildStatusHeader();
                return String.format("%s「%s」→ 自动切到新标签页 %d\n\n%s\n页面摘要：%s",
                        opDesc, target, newTabIndex, header, summary);
            }

            String summary = extractPageSummary(activePage);
            String header = buildStatusHeader();
            return String.format("%s「%s」\n\n%s\n页面摘要：%s",
                    opDesc, target, header, summary);
        } catch (Exception e) {
            return opDesc + "「" + target + "」。";
        }
    }

    // ========== 安全工具 ==========

    static String safeGet(Callable<String> supplier, String fallback) {
        try {
            return supplier.call();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ========== 资源管理 ==========

    private synchronized void closeActiveBrowser() {
        allPages.clear();
        if (activePage != null) {
            try { activePage.close(); } catch (Exception e) { log.debug("关闭旧页面失败：{}", e.getMessage()); }
            activePage = null;
        }
        if (activeContext != null) {
            try { activeContext.close(); } catch (Exception e) { log.debug("关闭旧 Context 失败：{}", e.getMessage()); }
            activeContext = null;
        }
        if (activeBrowser != null) {
            try { activeBrowser.close(); } catch (Exception e) { log.debug("关闭旧浏览器失败：{}", e.getMessage()); }
            activeBrowser = null;
        }
        if (activePlaywright != null) {
            try { activePlaywright.close(); } catch (Exception e) { log.debug("关闭旧 Playwright 失败：{}", e.getMessage()); }
            activePlaywright = null;
        }
    }

    @Override
    @PreDestroy
    public synchronized void shutdown() {
        log.info("BrowserServiceImpl 正在释放 Playwright 资源...");
        closeActiveBrowser();
    }

    // ========== Chromium 下载安装 ==========

    private void startDownload() {
        ChromiumState prev;
        do {
            prev = state.get();
            if (prev == ChromiumState.DOWNLOADING || prev == ChromiumState.AVAILABLE) {
                return;
            }
        } while (!state.compareAndSet(prev, ChromiumState.DOWNLOADING));

        downloadStartTime = System.currentTimeMillis();
        log.info("开始从国内镜像下载 Chromium revision {}", CHROMIUM_REVISION);

        Thread downloadThread = new Thread(() -> {
            Path tempZip = null;
            try {
                String downloadUrl = MIRROR_BASE_URL + "chromium-win64.zip";
                log.info("下载 URL: {}", downloadUrl);

                Path installDir = getChromiumInstallDir();
                if (installDir == null) {
                    throw new IOException("无法确定 Chromium 安装目录路径");
                }
                Files.createDirectories(installDir);
                tempZip = Files.createTempFile("chromium-", ".zip");
                log.info("开始下载 chromium-win64.zip，临时文件：{}", tempZip);

                URL url = new URL(downloadUrl);
                URLConnection conn = url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(DOWNLOAD_TIMEOUT_SECONDS * 1000);

                long totalSize = conn.getContentLengthLong();
                log.info("文件总大小：{} 字节（约 {} MB）", totalSize, totalSize / (1024 * 1024));

                try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                     OutputStream out = new FileOutputStream(tempZip.toFile())) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    long downloaded = 0;
                    int bytesRead;
                    long lastLogBytes = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        downloaded += bytesRead;
                        if (downloaded - lastLogBytes >= 10 * 1024 * 1024) {
                            int pct = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : -1;
                            log.info("下载进度：{} MB / {} MB{}",
                                    downloaded / (1024 * 1024),
                                    totalSize > 0 ? totalSize / (1024 * 1024) : "?",
                                    pct >= 0 ? " (" + pct + "%)" : "");
                            lastLogBytes = downloaded;
                        }
                    }
                }
                log.info("下载完成，开始解压...");

                Path targetDir = installDir.resolve(CHROMIUM_DIR_PREFIX + CHROMIUM_REVISION);
                extractZip(tempZip, targetDir);
                log.info("解压完成：{}", targetDir);

                Path chromiumDir = findChromiumDir();
                if (chromiumDir != null) {
                    long elapsed = (System.currentTimeMillis() - downloadStartTime) / 1000;
                    log.info("Chromium 自动安装完成，耗时 {} 秒，路径：{}，浏览器功能已就绪",
                            elapsed, chromiumDir);
                    state.set(ChromiumState.AVAILABLE);
                } else {
                    log.error("解压完成但未检测到 chromium-{} 目录", CHROMIUM_REVISION);
                    state.set(ChromiumState.UNAVAILABLE);
                }
            } catch (Exception e) {
                long elapsed = (System.currentTimeMillis() - downloadStartTime) / 1000;
                log.error("Chromium 自动下载/安装失败（耗时 {} 秒）：{}", elapsed, e.getMessage(), e);
                state.set(ChromiumState.UNAVAILABLE);
            } finally {
                if (tempZip != null) {
                    try {
                        Files.deleteIfExists(tempZip);
                    } catch (IOException e) {
                        log.debug("删除临时文件失败：{}", tempZip, e);
                    }
                }
            }
        }, "chromium-installer");

        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private static void extractZip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entry.isDirectory() || entryName.contains("__MACOSX")) {
                    zis.closeEntry();
                    continue;
                }
                Path entryPath = targetDir.resolve(entryName).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    log.warn("跳过潜在 Zip Slip 路径：{}", entryName);
                    zis.closeEntry();
                    continue;
                }
                Files.createDirectories(entryPath.getParent());
                try (OutputStream out = Files.newOutputStream(entryPath)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private Path findChromiumDir() {
        Path installDir = getChromiumInstallDir();
        if (installDir == null || !Files.isDirectory(installDir)) {
            log.debug("Chromium 安装目录不存在：{}", installDir);
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(installDir,
                CHROMIUM_DIR_PREFIX + "*")) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    return entry;
                }
            }
        } catch (IOException e) {
            log.debug("扫描 Chromium 安装目录失败：{}", e.getMessage());
        }
        return null;
    }

    private static Path getChromiumInstallDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isEmpty()) {
                return Paths.get(localAppData, "ykd-summer", "chromium");
            }
            return Paths.get(home, "AppData", "Local", "ykd-summer", "chromium");
        } else if (os.contains("mac")) {
            return Paths.get(home, "Library", "Caches", "ykd-summer", "chromium");
        } else {
            return Paths.get(home, ".cache", "ykd-summer", "chromium");
        }
    }
}
