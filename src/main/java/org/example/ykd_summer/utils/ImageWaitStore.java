package org.example.ykd_summer.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 等待配图的动态发布任务暂存。
 * <p>
 * 当用户说"配图如下"等明确要求带图发布但图片尚未到达时，
 * Runner 先调路由确认意图，路由返回 WAIT_IMAGE 后将原始指令暂存于此。
 * 图片到达后 {@link org.example.ykd_summer.service.impl.AiChatServiceImpl#matchReply}
 * 检测到此标记，跳过路由 LLM 直接编排 BILIBILI 带图步骤。
 */
public final class ImageWaitStore {

    private static final Map<String, PendingImageTask> STORE = new ConcurrentHashMap<>();

    /** 20 分钟过期（等待窗口 20s + 路由/发布耗时余量） */
    private static final long EXPIRY_MS = 20 * 60 * 1000L;

    public record PendingImageTask(String originalText, long timestamp) {}

    private ImageWaitStore() {}

    public static void put(String userId, String originalText) {
        if (userId == null) return;
        STORE.put(userId, new PendingImageTask(originalText, System.currentTimeMillis()));
    }

    public static PendingImageTask get(String userId) {
        if (userId == null) return null;
        PendingImageTask task = STORE.get(userId);
        if (task == null) return null;
        if (System.currentTimeMillis() - task.timestamp() > EXPIRY_MS) {
            STORE.remove(userId);
            return null;
        }
        return task;
    }

    public static boolean hasPending(String userId) {
        return get(userId) != null;
    }

    public static void remove(String userId) {
        STORE.remove(userId);
    }
}
