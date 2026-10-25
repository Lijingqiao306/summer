package org.example.ykd_summer.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * B站 Cookie 持久化存储。
 * <p>
 * 将登录后的 Cookie 保存到 {@code data/bilibili_cookie.json}，
 * 应用重启时自动恢复。如果文件不存在，回退读取配置文件中的 {@code bilibili.cookie}。
 * <p>
 * 后续登录流程（Playwright 扫码）会调用 {@link #save(String)} 更新此文件。
 */
@Slf4j
@Component
public class BilibiliCookieStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path COOKIE_FILE =
            Paths.get(System.getProperty("user.dir"), "data", "bilibili_cookie.json");

    @Value("${bilibili.cookie:}")
    private String configCookie;

    /**
     * 获取当前有效的 Cookie 字符串。
     * 优先从文件读取，文件不存在时回退到配置项。
     *
     * @return Cookie 字符串，可能为 null
     */
    public String load() {
        // 优先从文件读取
        if (Files.exists(COOKIE_FILE)) {
            try {
                String json = Files.readString(COOKIE_FILE);
                CookieData data = GSON.fromJson(json, CookieData.class);
                if (data != null && data.getCookie() != null && !data.getCookie().isBlank()) {
                    log.info("从文件加载 B站 Cookie: {}", COOKIE_FILE.toAbsolutePath());
                    return data.getCookie();
                }
            } catch (Exception e) {
                log.warn("加载 B站 Cookie 文件失败: {}", e.getMessage());
            }
        }

        // 回退到配置
        if (configCookie != null && !configCookie.isBlank()) {
            log.info("从配置加载 B站 Cookie");
            return configCookie;
        }

        log.warn("B站 Cookie 未设置（文件不存在且配置为空）");
        return null;
    }

    /**
     * 保存 Cookie 到文件。
     *
     * @param cookie Cookie 完整字符串（如 {@code SESSDATA=xxx; bili_jct=xxx; ...}）
     */
    public void save(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            log.warn("Cookie 为空，跳过保存");
            return;
        }
        try {
            Files.createDirectories(COOKIE_FILE.getParent());
            CookieData data = new CookieData(cookie);
            Files.writeString(COOKIE_FILE, GSON.toJson(data));
            log.info("B站 Cookie 已保存到: {}", COOKIE_FILE.toAbsolutePath());
        } catch (IOException e) {
            log.error("保存 B站 Cookie 失败", e);
        }
    }

    /**
     * 清除 Cookie 文件。
     */
    public void clear() {
        try {
            boolean existed = Files.deleteIfExists(COOKIE_FILE);
            if (existed) {
                log.info("B站 Cookie 文件已清除: {}", COOKIE_FILE.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("清除 B站 Cookie 文件失败", e);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class CookieData {
        @SerializedName("cookie")
        private String cookie;
    }
}
