package org.example.ykd_summer.utils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * BILIBILI Agent 的跨线程上下文传递工具。
 * <p>
 * 采用 ThreadLocal + ConcurrentHashMap 双重机制（与 FileTools 一致），
 * 解决 Spring AI @Tool 可能在独立线程执行导致的 ThreadLocal 丢失问题。
 * <p>
 * 用途：
 * <ol>
 *   <li>传递当前用户 ID，供 BilibiliAgentTools 获取</li>
 *   <li>传递登录二维码图片，供 IlinkBotRunner 在 matchReply 后发送</li>
 * </ol>
 */
public class BilibiliToolContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, String> USER_ID_MAP = new ConcurrentHashMap<>();

    private static final ThreadLocal<byte[]> PENDING_IMAGE = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, byte[]> PENDING_IMAGE_MAP = new ConcurrentHashMap<>();

    /** 编排层指定的动态发布内容（覆盖 LLM 传入的 content，防臆测文本污染） */
    private static final ThreadLocal<String> INTENDED_DYNAMIC_CONTENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, String> INTENDED_CONTENT_MAP = new ConcurrentHashMap<>();

    /**
     * 编排层检测到的封面意图：
     * "hint" = 用户表示要提供封面（如"封面如下"），"default" = 用户明确使用默认封面
     */
    private static final ConcurrentHashMap<String, String> COVER_CHOICE_MAP = new ConcurrentHashMap<>();

    private BilibiliToolContext() {
    }

    /** 设置封面意图（由编排层正则检测后写入） */
    public static void setCoverChoice(String userId, String choice) {
        if (userId != null && choice != null) {
            COVER_CHOICE_MAP.put(userId, choice);
        }
    }

    /** 获取并清除封面意图 */
    public static String getAndClearCoverChoice(String userId) {
        return userId != null ? COVER_CHOICE_MAP.remove(userId) : null;
    }

    /** 设置当前用户 ID（ThreadLocal → ConcurrentHashMap 双写） */
    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
        if (userId != null) {
            USER_ID_MAP.put(userId, userId);
        }
    }

    /** 获取当前用户 ID（ThreadLocal → ConcurrentHashMap 回退） */
    public static String getCurrentUserId() {
        String id = CURRENT_USER_ID.get();
        if (id != null) {
            return id;
        }
        // 跨线程回退：从 map 中取任意一个（同一时刻只有一个 BILIBILI 工具在执行）
        for (String uid : USER_ID_MAP.values()) {
            return uid;
        }
        return null;
    }

    /** 暂存二维码图片（按 userId），供 IlinkBotRunner 发送 */
    public static void setPendingImage(String userId, byte[] imageBytes) {
        if (userId != null && imageBytes != null) {
            PENDING_IMAGE.set(imageBytes);
            PENDING_IMAGE_MAP.put(userId, imageBytes);
        }
    }

    /** 获取并清除暂存的二维码图片（ThreadLocal → ConcurrentHashMap 回退） */
    public static byte[] getAndClearPendingImage(String userId) {
        // 1) ThreadLocal
        byte[] img = PENDING_IMAGE.get();
        PENDING_IMAGE.remove();
        if (img != null) {
            return img;
        }
        // 2) ConcurrentHashMap
        if (userId != null) {
            return PENDING_IMAGE_MAP.remove(userId);
        }
        // 3) 兜底：取 map 中任意一个
        for (var entry : PENDING_IMAGE_MAP.entrySet()) {
            PENDING_IMAGE_MAP.remove(entry.getKey());
            return entry.getValue();
        }
        return null;
    }

    /**
     * 存储编排层指定的动态发布内容（ThreadLocal → ConcurrentHashMap 双写）。
     * BilibiliAgentTools 在发布时会优先使用此内容，覆盖 LLM 可能臆测的文本。
     */
    public static void setIntendedDynamicContent(String userId, String content) {
        INTENDED_DYNAMIC_CONTENT.set(content);
        if (userId != null && content != null) {
            INTENDED_CONTENT_MAP.put(userId, content);
        }
    }

    /**
     * 获取编排层指定的动态发布内容（ThreadLocal → ConcurrentHashMap 回退）。
     * 不消费不销毁，内容由 {@link #clear} 清理。
     * 返回 null 表示无指定内容，工具应使用 LLM 传入的 content。
     */
    public static String getIntendedDynamicContent() {
        String content = INTENDED_DYNAMIC_CONTENT.get();
        if (content != null) {
            return content;
        }
        // 跨线程回退：按 userId 精确查找
        for (var entry : INTENDED_CONTENT_MAP.entrySet()) {
            return entry.getValue();
        }
        return null;
    }

    /** 清除当前上下文 */
    public static void clear(String userId) {
        CURRENT_USER_ID.remove();
        if (userId != null) {
            USER_ID_MAP.remove(userId);
        }
        PENDING_IMAGE.remove();
        if (userId != null) {
            PENDING_IMAGE_MAP.remove(userId);
        }
        INTENDED_DYNAMIC_CONTENT.remove();
        if (userId != null) {
            INTENDED_CONTENT_MAP.remove(userId);
            COVER_CHOICE_MAP.remove(userId);
        }
    }
}
