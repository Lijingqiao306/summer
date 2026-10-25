package org.example.ykd_summer.service.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.BilibiliLoginService;
import org.example.ykd_summer.store.BilibiliCookieStore;
import org.example.ykd_summer.tools.MailSendTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * B站 Cookie 过期后的自动登录服务实现。
 * <p>
 * 使用独立 Playwright 实例（不与 BrowserServiceImpl 共用），
 * 打开登录页 → 截图二维码发邮件 → 轮询等待扫码 → 提取新 Cookie 持久化。
 */
@Slf4j
@Service
public class BilibiliLoginServiceImpl implements BilibiliLoginService {

    private static final String LOGIN_URL = "https://passport.bilibili.com/login";
    private static final int POLL_INTERVAL_MS = 3_000;
    private static final int MAX_POLL_TIME_MS = 5 * 60_000;
    private static final int MAX_QR_RETRIES = 3;

    private final BilibiliCookieStore cookieStore;
    private final MailSendTools mailSendTools;

    @Value("${bilibili.report-qq}")
    private String reportQq;

    public BilibiliLoginServiceImpl(BilibiliCookieStore cookieStore,
                                    MailSendTools mailSendTools) {
        this.cookieStore = cookieStore;
        this.mailSendTools = mailSendTools;
    }

    @Override
    public boolean loginAndSaveCookie() {
        log.info("=== 开始 B站扫码登录流程 ===");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            if (browser == null) {
                log.error("无法启动浏览器，登录流程中止");
                sendNotification("B站登录失败",
                        "<p>无法启动浏览器，请检查系统是否安装了 Chrome 或 Edge。</p>");
                return false;
            }

            try {
                BrowserContext context = browser.newContext();
                Page page = context.newPage();

                // 打开登录页
                page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(30_000));
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(15_000));

                return pollForLogin(context, page, 0);
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            log.error("B站登录流程异常", e);
            sendNotification("B站登录异常",
                    "<p>登录流程异常：" + escapeHtml(e.getMessage()) + "</p>");
            return false;
        }
    }

    // ---- 轮询等待扫码 ----

    private boolean pollForLogin(BrowserContext context, Page page, int qrRetryCount) {
        // 截图并发送二维码邮件
        byte[] screenshot = page.screenshot(
                new Page.ScreenshotOptions().setType(ScreenshotType.PNG));
        String retryHint = qrRetryCount > 0 ? "（第 " + qrRetryCount + " 次刷新）" : "";
        mailSendTools.sendHtmlMail(reportQq,
                "【B站登录】请扫描二维码" + retryHint,
                "<p>B站 Cookie 已过期，请使用 <b>B站 App</b> 扫描附件中的二维码完成登录。</p>"
                        + (qrRetryCount > 0
                        ? "<p style=\"color:red;\">之前的二维码已过期，已自动刷新。</p>"
                        : ""),
                "bilibili_qrcode.png", screenshot);

        // 轮询检测 SESSDATA
        long deadline = System.currentTimeMillis() + MAX_POLL_TIME_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            // 检测是否登录成功
            String sessdata = extractCookie(context, "SESSDATA");
            if (sessdata != null && !sessdata.isBlank()) {
                return onLoginSuccess(context);
            }

            // 检测二维码是否过期
            if (isQrExpired(page)) {
                log.warn("二维码已过期 (retry {}/{})", qrRetryCount + 1, MAX_QR_RETRIES);
                if (qrRetryCount < MAX_QR_RETRIES) {
                    page.reload(new Page.ReloadOptions().setTimeout(15_000));
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(10_000));
                    return pollForLogin(context, page, qrRetryCount + 1);
                } else {
                    log.error("二维码过期已达最大重试次数 {}", MAX_QR_RETRIES);
                    sendNotification("B站登录失败",
                            "<p>二维码已过期 " + MAX_QR_RETRIES + " 次，登录流程中止。</p>");
                    return false;
                }
            }
        }

        // 超时
        log.error("扫码登录超时（{}s）", MAX_POLL_TIME_MS / 1000);
        sendNotification("B站登录超时",
                "<p>等待扫码已超过 " + (MAX_POLL_TIME_MS / 60_000)
                        + " 分钟，登录流程中止。</p>");
        return false;
    }

    // ---- 登录成功处理 ----

    private boolean onLoginSuccess(BrowserContext context) {
        log.info("检测到 SESSDATA，B站扫码登录成功！");

        // 提取完整 Cookie 并持久化
        String fullCookie = buildCookieString(context);
        cookieStore.save(fullCookie);

        // 发送成功通知
        sendNotification("B站登录成功",
                "<p style=\"color:green;\">B站扫码登录成功，Cookie 已更新。</p>"
                        + "<p>后续数据采集可正常进行。</p>");

        return true;
    }

    // ---- Playwright Cookie 工具 ----

    /** 从 Playwright Context 中提取指定名称的 Cookie 值 */
    private String extractCookie(BrowserContext context, String name) {
        return context.cookies().stream()
                .filter(c -> name.equals(c.name))
                .map(c -> c.value)
                .findFirst().orElse(null);
    }

    /** 组装完整 Cookie 字符串（key=value; key=value 格式） */
    private String buildCookieString(BrowserContext context) {
        return context.cookies().stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));
    }

    // ---- 二维码过期检测 ----

    private boolean isQrExpired(Page page) {
        try {
            String text = page.textContent("body");
            return text != null && (
                    text.contains("二维码已过期")
                            || text.contains("点击刷新")
                            || text.contains("重新加载")
                            || text.contains("已失效"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 邮件通知 ----

    private void sendNotification(String title, String htmlContent) {
        try {
            mailSendTools.sendHtmlMail(reportQq, title, htmlContent, null, null);
        } catch (Exception e) {
            log.error("发送通知邮件失败: {}", e.getMessage());
        }
    }

    // ---- HTML 转义 ----

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ---- Playwright 浏览器启动（三级回退） ----

    private Browser launchBrowser(Playwright playwright) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(true);

        // 1) 系统 Chrome
        try {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setChannel("chrome"));
            log.info("使用系统 Chrome 启动登录浏览器");
            return browser;
        } catch (Exception e) {
            log.info("系统 Chrome 不可用，尝试 Edge...");
        }

        // 2) 系统 Edge
        try {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setChannel("msedge"));
            log.info("使用系统 Edge 启动登录浏览器");
            return browser;
        } catch (Exception e) {
            log.info("系统 Edge 不可用，尝试已下载的 Chromium...");
        }

        // 3) 已下载的 Chromium
        Path chromiumDir = findChromiumDir();
        if (chromiumDir != null) {
            Path exe = chromiumDir.resolve("chrome-win").resolve("chrome.exe");
            if (Files.exists(exe)) {
                log.info("使用已下载的 Chromium 启动登录浏览器: {}", exe);
                return playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(true)
                                .setExecutablePath(exe));
            }
        }

        return null;
    }

    /** 查找 BrowserServiceImpl 下载的 Chromium 目录 */
    private Path findChromiumDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) return null;
        Path base = Path.of(localAppData, "ykd-summer", "chromium");
        if (!Files.isDirectory(base)) return null;
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
}
