package org.example.ykd_summer.session;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Bot 登录会话持久化服务。
 * <p>
 * 将 {@link LoginContext} 序列化到项目根目录下的 .ilink_session.json 文件，
 * 应用重启时自动恢复，避免重复扫码。
 * <p>
 * 仅 Bot 账号自身登录需要持久化（永远只有一份），
 * 微信用户级别的会话隔离由 {@code UserActor} 和 Spring AI {@code ChatMemory} 负责，不在此处管理。
 */
@Slf4j
@Service
public class SessionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SESSION_FILE =
            Paths.get(System.getProperty("user.dir"), ".ilink_session.json");

    /**
     * 将 LoginContext 持久化到磁盘。
     */
    public void save(LoginContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            String json = GSON.toJson(ctx);
            Files.writeString(SESSION_FILE, json);
            log.info("登录会话已持久化: {} (botId={})", SESSION_FILE.toAbsolutePath(), ctx.getBotId());
        } catch (IOException e) {
            log.error("保存登录会话失败", e);
        }
    }

    /**
     * 从磁盘恢复 LoginContext。
     *
     * @return 已反序列化的 LoginContext，文件不存在或反序列化失败时返回 null
     */
    public LoginContext load() {
        if (!Files.exists(SESSION_FILE)) {
            log.info("未找到已存储的登录会话，将进入扫码登录流程");
            return null;
        }
        try {
            String json = Files.readString(SESSION_FILE);
            LoginContext ctx = GSON.fromJson(json, LoginContext.class);
            if (ctx == null || ctx.getBotToken() == null) {
                log.warn("已存储的登录会话无效（缺少 botToken），将回退到扫码登录");
                clear();
                return null;
            }
            log.info("从 {} 恢复登录会话: botId={}", SESSION_FILE.toAbsolutePath(), ctx.getBotId());
            return ctx;
        } catch (Exception e) {
            log.warn("加载登录会话失败，将回退到扫码登录: {}", e.getMessage());
            clear();
            return null;
        }
    }

    /**
     * 清除磁盘上的会话文件。
     */
    public void clear() {
        try {
            boolean existed = Files.deleteIfExists(SESSION_FILE);
            if (existed) {
                log.info("登录会话文件已清除: {}", SESSION_FILE.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("清除登录会话文件失败", e);
        }
    }
}
