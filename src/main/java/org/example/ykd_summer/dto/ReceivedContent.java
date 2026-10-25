package org.example.ykd_summer.dto;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Base64;

/**
 * 统一的内容载体，替代分散的 byte[] / base64 String / URL 等多套路。
 * <p>
 * 使用 Spring {@link Resource} 包装原始数据，按需延迟读取，避免不必要的数据复制。
 * 无论是用户上传的图片、语音文件，还是 AI 生成的图片/文档，统一使用此载体传递。
 *
 * @param resource 数据载体（支持 ByteArrayResource 等实现）
 * @param mimeType MIME 类型，如 "image/png"、"audio/wav"、"application/pdf"
 * @param fileName 原始文件名，如 "photo.png"、"voice.wav"
 */
public record ReceivedContent(Resource resource, String mimeType, String fileName) {

    /**
     * 便捷工厂：从 byte[] 创建 {@link ReceivedContent}。
     */
    public static ReceivedContent build(byte[] data, String mimeType, String fileName) {
        return new ReceivedContent(new ByteArrayResource(data), mimeType, fileName);
    }

    /** 是否为图片类型 */
    public boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /** 是否为视频类型 */
    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith("video/");
    }

    /** 是否为音频类型 */
    public boolean isAudio() {
        return mimeType != null && mimeType.startsWith("audio/");
    }

    /** 是否为文档类型 */
    public boolean isDocument() {
        if (mimeType == null) return false;
        return mimeType.equals("application/pdf")
                || mimeType.contains("word")
                || mimeType.contains("document")
                || mimeType.contains("text/")
                || mimeType.contains("spreadsheet")
                || mimeType.contains("presentation");
    }

    /**
     * 将 Resource 内容读取为 Base64 Data URL。
     * <p>
     * 主要用于路由阶段将图片传给多模态模型（qwen-vl-plus 需要 base64 data URI）。
     * 调用方需自行确保 Resource 可重复读取（ByteArrayResource 满足此条件）。
     *
     * @return Data URL 字符串（如 "data:image/png;base64,..."），读取失败时返回 null
     */
    public String toDataUrl() {
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + b64;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 将 Resource 内容读取为 byte[]。
     *
     * @return 字节数组，读取失败时返回 null
     */
    public byte[] toBytes() {
        try {
            return resource.getInputStream().readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
