package org.example.ykd_summer.utils;

import org.example.ykd_summer.dto.ReceivedContent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨轮次图片暂存上下文。
 * <p>
 * 当用户仅发送图片（无文字指令）时，图片暂存于此，等待用户补充指令后取出合并，
 * 实现"先发图，再说用途"的多轮交互模式。
 * <p>
 * 与 {@link BilibiliToolContext} 类似，在 IlinkBotRunner 中于 AI 调用前后存取。
 */
public final class AttachmentContext {

    private static final Map<String, PendingAttachment> STORE = new ConcurrentHashMap<>();

    /** 暂存超时时间（毫秒） */
    private static final long EXPIRE_MS = 5 * 60 * 1000; // 5 分钟

    private record PendingAttachment(List<ReceivedContent> attachments, long timestamp) {}

    private AttachmentContext() {}

    /**
     * 暂存用户的附件（图片）。
     */
    public static void put(String userId, List<ReceivedContent> attachments) {
        if (attachments == null || attachments.isEmpty()) return;
        STORE.put(userId, new PendingAttachment(attachments, System.currentTimeMillis()));
    }

    /**
     * 取出并清除用户的暂存附件。
     *
     * @return 暂存的附件列表，不存在时返回 null
     */
    public static List<ReceivedContent> getAndClear(String userId) {
        PendingAttachment pa = STORE.remove(userId);
        return pa != null ? pa.attachments() : null;
    }

    /**
     * 查看用户的暂存附件（不清除），用于在 AI 调用前合并图片到消息中，
     * 同时保留原始数据供 BILIBILI Agent 工具后续消费。
     *
     * @return 暂存的附件列表，不存在时返回 null
     */
    public static List<ReceivedContent> peek(String userId) {
        PendingAttachment pa = STORE.get(userId);
        if (pa == null) return null;
        if (System.currentTimeMillis() - pa.timestamp > EXPIRE_MS) {
            STORE.remove(userId);
            return null;
        }
        return pa.attachments();
    }

    /**
     * 清除用户的暂存附件（不返回数据），用于 AI 处理完毕后释放。
     */
    public static void clear(String userId) {
        STORE.remove(userId);
    }

    /**
     * 判断用户是否有暂存附件（未过期的）。
     */
    public static boolean hasPending(String userId) {
        PendingAttachment pa = STORE.get(userId);
        if (pa == null) return false;
        if (System.currentTimeMillis() - pa.timestamp > EXPIRE_MS) {
            STORE.remove(userId);
            return false;
        }
        return true;
    }

    /** 主动清理过期的暂存附件。 */
    public static void cleanExpired() {
        long now = System.currentTimeMillis();
        STORE.entrySet().removeIf(e -> now - e.getValue().timestamp() > EXPIRE_MS);
    }
}
