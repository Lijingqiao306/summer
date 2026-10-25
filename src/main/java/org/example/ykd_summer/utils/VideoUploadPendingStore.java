package org.example.ykd_summer.utils;

import org.example.ykd_summer.dto.VideoMetadata;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 等待封面的视频上传任务暂存器。
 * <p>
 * 用户指令上传视频但未提供封面时，视频字节和元数据暂存于此，
 * 等待用户发送封面图片或回复「使用默认封面」后恢复执行。
 * <p>
 * 与 {@link AttachmentContext} 的区别：AttachmentContext 暂存原始附件，
 * 本类暂存"已解析的上传任务"（含元数据），支持跨轮次恢复。
 */
public final class VideoUploadPendingStore {

    /** 任务过期时间（10 分钟） */
    private static final long EXPIRE_MS = 10 * 60 * 1000;

    private static final Map<String, PendingUpload> STORE = new ConcurrentHashMap<>();

    /**
     * 等待封面的上传任务。
     *
     * @param videoBytes 视频字节
     * @param fileName   视频文件名
     * @param metadata   已解析的稿件元数据
     * @param coverBytes 封面图片字节（用户已提供封面时暂存，防止扫码登录恢复后封面丢失；null 表示未提供）
     * @param timestamp  暂存时间戳
     */
    public record PendingUpload(byte[] videoBytes, String fileName,
                                VideoMetadata metadata, byte[] coverBytes, long timestamp) {}

    private VideoUploadPendingStore() {}

    /** 暂存任务（未提供封面） */
    public static void put(String userId, byte[] videoBytes, String fileName, VideoMetadata metadata) {
        put(userId, videoBytes, fileName, metadata, null);
    }

    /** 暂存任务（含已提供的封面） */
    public static void put(String userId, byte[] videoBytes, String fileName,
                           VideoMetadata metadata, byte[] coverBytes) {
        if (userId == null || videoBytes == null) {
            return;
        }
        STORE.put(userId, new PendingUpload(videoBytes, fileName, metadata, coverBytes,
                System.currentTimeMillis()));
    }

    /** 查看任务（不清除），过期自动移除 */
    public static PendingUpload peek(String userId) {
        if (userId == null) {
            return null;
        }
        PendingUpload p = STORE.get(userId);
        if (p == null) {
            return null;
        }
        if (System.currentTimeMillis() - p.timestamp() > EXPIRE_MS) {
            STORE.remove(userId);
            return null;
        }
        return p;
    }

    /** 清除任务 */
    public static void clear(String userId) {
        if (userId != null) {
            STORE.remove(userId);
        }
    }

    /** 是否有等待中的任务 */
    public static boolean hasPending(String userId) {
        return peek(userId) != null;
    }
}
