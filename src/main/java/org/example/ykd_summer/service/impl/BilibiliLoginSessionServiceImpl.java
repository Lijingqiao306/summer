package org.example.ykd_summer.service.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.BilibiliLoginSessionService;
import org.example.ykd_summer.store.BilibiliCookieStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BILIBILI Agent 专用的异步登录会话管理器实现。
 */
@Slf4j
@Component
public class BilibiliLoginSessionServiceImpl implements BilibiliLoginSessionService {

    private static final String LOGIN_URL = "https://passport.bilibili.com/login";
    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    private final BilibiliCookieStore cookieStore;

    /** userId → 活跃的登录会话 */
    private final ConcurrentHashMap<String, LoginSession> sessions = new ConcurrentHashMap<>();

    /** userId → 会话启动时间戳 */
    private final ConcurrentHashMap<String, Long> sessionTimestamps = new ConcurrentHashMap<>();

    public BilibiliLoginSessionServiceImpl(BilibiliCookieStore cookieStore) {
        this.cookieStore = cookieStore;
    }

    // ==================== 公共 API ====================

    @Override
    public byte[] startLogin() {
        try {
            // 创建独立 Playwright 实例
            Playwright playwright = Playwright.create();
            Browser browser = launchBrowser(playwright);
            if (browser == null) {
                playwright.close();
                log.error("BilibiliLoginSessionService: 无法启动浏览器");
                return null;
            }

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 打开登录页
            page.navigate(LOGIN_URL, new Page.NavigateOptions().setTimeout(30_000));
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));

            // 截图二维码
            byte[] screenshot = page.screenshot(
                    new Page.ScreenshotOptions().setType(ScreenshotType.PNG));

            // 生成临时 userId 用于存储会话
            String sessionId = "session_" + System.currentTimeMillis();
            LoginSession session = new LoginSession(playwright, browser, context, page);
            sessions.put(sessionId, session);
            sessionTimestamps.put(sessionId, System.currentTimeMillis());

            log.info("BilibiliLoginSessionService: 登录会话已创建, sessionId={}, screenshotSize={} bytes",
                    sessionId, screenshot.length);

            // 将会话 ID 存入 page 的临时标记中（通过 page 对象本身追踪）
            return screenshot;
        } catch (Exception e) {
            log.error("BilibiliLoginSessionService: 启动登录失败", e);
            return null;
        }
    }

    @Override
    public boolean hasActiveSession() {
        return !sessions.isEmpty();
    }

    @Override
    public boolean completeLoginIfPending(String userId) {
        if (sessions.isEmpty()) {
            return false;
        }

        for (Map.Entry<String, LoginSession> entry : sessions.entrySet()) {
            String sessionId = entry.getKey();
            LoginSession session = entry.getValue();

            try {
                String sessdata = extractCookie(session.context, "SESSDATA");
                if (sessdata != null && !sessdata.isBlank()) {
                    log.info("BilibiliLoginSessionService: 检测到 SESSDATA，扫码登录成功！sessionId={}, userId={}",
                            sessionId, userId);

                    // 提取完整 Cookie 并持久化
                    String fullCookie = buildCookieString(session.context);
                    cookieStore.save(fullCookie);

                    // 保存完整 BrowserContext 状态（cookies + localStorage + indexedDB）
                    // 这是 B站上传图片所需的会话状态，仅靠 cookie 不够
                    try {
                        Path stateFile = Path.of(System.getProperty("user.dir"),
                                "data", "bilibili_browser_state.json");
                        java.nio.file.Files.createDirectories(stateFile.getParent());
                        session.context.storageState(new BrowserContext.StorageStateOptions()
                                .setPath(stateFile));
                        log.info("BilibiliLoginSessionService: 浏览器完整状态已保存: {}", stateFile);
                    } catch (Exception ex) {
                        log.warn("BilibiliLoginSessionService: 保存浏览器状态失败: {}", ex.getMessage());
                    }

                    // 关闭会话资源
                    closeSession(sessionId, session);

                    log.info("BilibiliLoginSessionService: Cookie 已持久化, userId={}", userId);
                    return true;
                }
            } catch (Exception e) {
                log.warn("BilibiliLoginSessionService: 检查登录状态异常, sessionId={}", sessionId, e);
                closeSession(sessionId, session);
            }
        }

        return false;
    }

    // ==================== 定时清理 ====================

    @Scheduled(fixedRate = 60_000)
    public void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long cutoff = now - SESSION_TIMEOUT_MS;

        for (Map.Entry<String, Long> entry : sessionTimestamps.entrySet()) {
            if (entry.getValue() < cutoff) {
                String sessionId = entry.getKey();
                LoginSession session = sessions.remove(sessionId);
                if (session != null) {
                    closeSession(sessionId, session);
                }
                sessionTimestamps.remove(sessionId);
                log.info("BilibiliLoginSessionService: 过期会话已清理, sessionId={}", sessionId);
            }
        }
    }

    // ==================== 内部方法 ====================

    private void closeSession(String sessionId, LoginSession session) {
        try {
            session.context.close();
        } catch (Exception ignored) {
        }
        try {
            session.browser.close();
        } catch (Exception ignored) {
        }
        try {
            session.playwright.close();
        } catch (Exception ignored) {
        }
        sessions.remove(sessionId);
        sessionTimestamps.remove(sessionId);
        log.debug("BilibiliLoginSessionService: 会话已关闭, sessionId={}", sessionId);
    }

    private String extractCookie(BrowserContext context, String name) {
        return context.cookies().stream()
                .filter(c -> name.equals(c.name))
                .map(c -> c.value)
                .findFirst().orElse(null);
    }

    private String buildCookieString(BrowserContext context) {
        return context.cookies().stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));
    }

    // ---- Playwright 浏览器启动（三级回退） ----

    private Browser launchBrowser(Playwright playwright) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(true);

        // 1) 系统 Chrome
        try {
            return playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setChannel("chrome"));
        } catch (Exception e) {
            log.debug("系统 Chrome 不可用，尝试 Edge...");
        }

        // 2) 系统 Edge
        try {
            return playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setChannel("msedge"));
        } catch (Exception e) {
            log.debug("系统 Edge 不可用，尝试已下载的 Chromium...");
        }

        // 3) 已下载的 Chromium
        Path chromiumDir = findChromiumDir();
        if (chromiumDir != null) {
            Path exe = chromiumDir.resolve("chrome-win").resolve("chrome.exe");
            if (Files.exists(exe)) {
                return playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(true)
                                .setExecutablePath(exe));
            }
        }

        return null;
    }

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

    // ---- 内部数据结构 ----

    /**
     * 活跃的 Playwright 登录会话。
     */
    private static class LoginSession {
        final Playwright playwright;
        final Browser browser;
        final BrowserContext context;
        final Page page;

        LoginSession(Playwright playwright, Browser browser, BrowserContext context, Page page) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
            this.page = page;
        }
    }
}
