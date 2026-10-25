package org.example.ykd_summer.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.ReceivedContent;
import org.example.ykd_summer.service.ImageService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片 Agent 可调用的工具集合
 * 用于独立的图片 ChatClient，不集成到文本 BotTools 中
 * <p>
 * 跨线程数据传递说明：
 * Spring AI 内部可能在独立线程执行 @Tool 方法（而非调用 ChatClient 的线程），
 * 因此 ThreadLocal 不可靠。所有工具产出（生成图片、附件映射）同时写入
 * ThreadLocal（同线程快速路径）和 ConcurrentHashMap（跨线程回退路径）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageTools {

    private final ImageService imageService;

    // ==================== 截断工具 ====================

    private static String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }

    // ==================== 用户上传图片附件映射 ====================

    /**
     * ThreadLocal 路径（同线程快速路径）。
     */
    private static final ThreadLocal<Map<String, ReceivedContent>> IMAGE_MAP = new ThreadLocal<>();

    /**
     * ConcurrentHashMap 路径（跨线程回退）。
     * key 为图片 Data URL，确保全局唯一。
     */
    private static final ConcurrentHashMap<String, Map<String, ReceivedContent>> IMAGE_ATTACHMENT_STORE =
            new ConcurrentHashMap<>();

    /** 当前线程的关键数据 URL 标记，用于跨线程回退查询 */
    private static final ThreadLocal<List<String>> ATTACHMENT_DATA_URLS = new ThreadLocal<>();

    /**
     * 存入当前线程的用户上传图片，生成 "ref:0"、"ref:1" … 的映射。
     * 同时写入 ThreadLocal 和 ConcurrentHashMap（以图片 dataUrl 为回退键）。
     */
    public static void storeImageAttachments(List<ReceivedContent> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        Map<String, ReceivedContent> map = new HashMap<>();
        for (int i = 0; i < attachments.size(); i++) {
            map.put("ref:" + i, attachments.get(i));
        }
        IMAGE_MAP.set(map);

        // 跨线程回退：以每张图片的 dataUrl 为键存入 ConcurrentHashMap
        List<String> dataUrls = new java.util.ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            String dataUrl = attachments.get(i).toDataUrl();
            if (dataUrl != null) {
                dataUrls.add(dataUrl);
                IMAGE_ATTACHMENT_STORE.put(dataUrl, map);
            }
        }
        ATTACHMENT_DATA_URLS.set(dataUrls);
        log.debug("ImageTools: 图片附件已存入, count={}, thread={}",
                attachments.size(), Thread.currentThread().getName());
    }

    /** 解析 ref:N 引用为真实 base64 Data URL */
    private static String resolveImageUrl(String input) {
        // ① 先尝试 ThreadLocal（同线程）
        Map<String, ReceivedContent> map = IMAGE_MAP.get();
        if (map == null) {
            // ② 跨线程回退：遍历 ConcurrentHashMap（用户上传图片）
            map = findAttachmentMap();
        }
        if (map != null && input != null && input.startsWith("ref:")) {
            ReceivedContent rc = map.get(input);
            if (rc != null) {
                String dataUrl = rc.toDataUrl();
                log.info("ImageTools: 解析引用 {} → type={}, size={}, thread={}",
                        input, rc.mimeType(),
                        dataUrl != null ? dataUrl.length() : 0,
                        Thread.currentThread().getName());
                return dataUrl;
            }
        }
        // ③ Bot 生成图片缓存回退（跨轮修改如"改为雪霁风格"）
        //    与用户上传图片存储完全隔离，互不干扰
        if (input != null && input.startsWith("ref:")) {
            String currentUserId = CURRENT_USER_ID.get();
            if (currentUserId != null) {
                ReceivedContent genContent = LAST_GENERATED_CACHE.get(currentUserId);
                if (genContent != null) {
                    String dataUrl = genContent.toDataUrl();
                    log.info("ImageTools: 解析引用 {} → Bot生成图片缓存 hit, userId={}, size={}, thread={}",
                            input, currentUserId,
                            dataUrl != null ? dataUrl.length() : 0,
                            Thread.currentThread().getName());
                    return dataUrl;
                }
            }
        }
        // 防御：ref:N 引用无法解析时，返回明确错误而非将无效引用传给 DashScope
        if (input != null && input.startsWith("ref:")) {
            log.warn("ImageTools: 无法解析引用 {}，所有回退路径均已耗尽, thread={}",
                    input, Thread.currentThread().getName());
            return null;
        }
        return input;
    }

    /** 遍历 ConcurrentHashMap 找一个可用的附件映射（跨线程回退） */
    private static Map<String, ReceivedContent> findAttachmentMap() {
        if (!IMAGE_ATTACHMENT_STORE.isEmpty()) {
            // 返回任意一个 map（所有 map 内容相同，都是 ref:0 → ReceivedContent）
            return IMAGE_ATTACHMENT_STORE.values().iterator().next();
        }
        return null;
    }

    /** 清理当前线程的图片映射 */
    public static void clearImageAttachments() {
        IMAGE_MAP.remove();
        List<String> dataUrls = ATTACHMENT_DATA_URLS.get();
        if (dataUrls != null) {
            for (String url : dataUrls) {
                IMAGE_ATTACHMENT_STORE.remove(url);
            }
        }
        ATTACHMENT_DATA_URLS.remove();
    }

    /** 无条件清理所有图片附件（ThreadLocal + ConcurrentHashMap）。
     *  用于会话中收到新图片上传时，替换旧附件。 */
    public static void clearAllImageAttachments() {
        IMAGE_MAP.remove();
        IMAGE_ATTACHMENT_STORE.clear();
        ATTACHMENT_DATA_URLS.remove();
    }

    // ==================== Bot 生成图片的跨轮持久化缓存 ====================

    /**
     * 按 userId 缓存最近生成的图片，供后续跨轮修改（如"改为雪霁风格"）时解析 ref:0。
     * 与 IMAGE_ATTACHMENT_STORE（用户上传图片）完全隔离，互不影响。
     */
    private static final ConcurrentHashMap<String, ReceivedContent> LAST_GENERATED_CACHE =
            new ConcurrentHashMap<>();

    /** 携带当前 userId 的 ThreadLocal，供 resolveImageUrl 跨线程获取 userId */
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    public static void setCurrentUserId(String userId) {
        if (userId != null) {
            CURRENT_USER_ID.set(userId);
        }
    }

    public static void clearCurrentUserId() {
        CURRENT_USER_ID.remove();
    }

    public static String getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void cacheLastGenerated(String userId, ReceivedContent content) {
        if (userId != null && content != null) {
            // 仅覆盖写入按 userId 的最新生成图（不清理 GENERATED_IMAGE_STORE）。
            // 注意：跨线程回退存储的清理必须在"下一次新图生成前"（storeGeneratedImage 中）
            // 进行，而非此处——否则 IMAGE 步骤结束即清理会导致后续 BILIBILI 步骤取图落空。
            LAST_GENERATED_CACHE.put(userId, content);
            log.info("ImageTools: 缓存Bot生成图片 userId={}, size={} bytes",
                    userId, content.toBytes() != null ? content.toBytes().length : 0);
        }
    }

    /** 清理指定 userId 的生成图片缓存（用户上传新图片 / newTopic / 任务取消时调用） */
    public static void clearLastGenerated(String userId) {
        if (userId != null) {
            ReceivedContent removed = LAST_GENERATED_CACHE.remove(userId);
            if (removed != null) {
                log.debug("ImageTools: 清除Bot生成图片缓存 userId={}", userId);
            }
        }
    }

    // ==================== AI 生成图片的跨线程载体 ====================

    /**
     * ThreadLocal 路径（同线程快速路径）。
     */
    private static final ThreadLocal<ReceivedContent> GENERATED_IMAGE_HOLDER = new ThreadLocal<>();

    /**
     * ConcurrentHashMap 路径（跨线程回退）。
     * key 为生成图片的 DashScope URL，确保全局唯一。
     */
    private static final ConcurrentHashMap<String, ReceivedContent> GENERATED_IMAGE_STORE =
            new ConcurrentHashMap<>();

    /**
     * 生图后存储图片数据，同时写入 ThreadLocal 和 ConcurrentHashMap。
     * <p>
     * ConcurrentHashMap 以图片 URL 为键，供调用方在 LLM 输出中提取 URL 后回退查询。
     * <p>
     * 包级别可见，供 OrchestrationServiceImpl 直接调用（绕过 LLM 工具调用）。
     */
    public static void storeGeneratedImage(String imageUrl, byte[] imageData, String fileName) {
        ReceivedContent rc = ReceivedContent.build(imageData, "image/png", fileName);
        // 同线程快速路径
        GENERATED_IMAGE_HOLDER.set(rc);
        // 跨线程回退路径（Spring AI 工具线程 ← → 主线程）
        // 新图生成即清理旧图：保证回退只取到最新生成的那张，避免多轮生成图共存导致取错
        if (!GENERATED_IMAGE_STORE.isEmpty()) {
            GENERATED_IMAGE_STORE.clear();
            log.debug("ImageTools: 清理跨线程生成图回退缓存（新图生成前）");
        }
        if (imageUrl != null) {
            GENERATED_IMAGE_STORE.put(imageUrl, rc);
        }
        log.info("图片已存储: thread={}, url={}, size={} bytes",
                Thread.currentThread().getName(), truncateForLog(imageUrl, 120), imageData.length);
    }

    /**
     * 查看生成图片数据（不消费），优先 ThreadLocal，回退 ConcurrentHashMap。
     * <p>
     * 与 {@link #consumeGeneratedImage()} 不同，此方法不移除数据，
     * 供 BILIBILI 等后续步骤桥接图片到 AttachmentContext 时使用。
     *
     * @return 生成的图片数据，不存在时返回 null
     */
    public static ReceivedContent peekGeneratedImage() {
        ReceivedContent img = GENERATED_IMAGE_HOLDER.get();
        if (img != null) {
            log.debug("ImageTools: peekGeneratedImage 从 ThreadLocal 获取, thread={}",
                    Thread.currentThread().getName());
            return img;
        }
        if (!GENERATED_IMAGE_STORE.isEmpty()) {
            ReceivedContent fallback = GENERATED_IMAGE_STORE.values().iterator().next();
            log.info("ImageTools: peekGeneratedImage 从 ConcurrentHashMap 回退获取, thread={}",
                    Thread.currentThread().getName());
            return fallback;
        }
        return null;
    }

    /**
     * 兜底生图：当 IMAGE Agent 的 LLM 未调用 generateImage 工具时（如臆测 URL），
     * 由 OrchestrationServiceImpl 调用此方法补偿生成图片，确保后续 BILIBILI 等
     * 步骤能拿到真实的图片数据。此方法不经过 LLM，直接调用 ImageService。
     * <p>
     * 注意：此为兜底补偿机制，正常流程中仍由 IMAGE Agent 的 LLM 通过工具调用生图。
     *
     * @param prompt 图片描述
     * @param style  风格（选填）
     * @param size   尺寸（选填）
     * @return 生成的图片 URL
     */
    public String fallbackGenerateImage(String prompt, String style, String size) {
        String imageUrl = imageService.generateImage(prompt, style, size);
        try {
            byte[] imageData = imageService.downloadImage(imageUrl);
            storeGeneratedImage(imageUrl, imageData, "generated.png");
            log.info("ImageTools.fallbackGenerateImage: 兜底生图成功, url={}, size={}",
                    truncateForLog(imageUrl, 120), imageData.length);
        } catch (Exception e) {
            log.warn("ImageTools.fallbackGenerateImage: 图片下载失败，仅返回 URL: url={}, error={}",
                    truncateForLog(imageUrl, 120), e.getMessage());
        }
        return imageUrl;
    }

    /**
     * 取出生成图片数据（优先 ThreadLocal，回退 ConcurrentHashMap）。
     * ConcurrentHashMap 回退需传入 URL，由调用方从 LLM 输出中提取。
     */
    public static ReceivedContent consumeGeneratedImage() {
        // 优先同线程 ThreadLocal
        ReceivedContent img = GENERATED_IMAGE_HOLDER.get();
        GENERATED_IMAGE_HOLDER.remove();
        if (img != null) {
            log.info("ImageTools: consumeGeneratedImage 从 ThreadLocal 取出, thread={}",
                    Thread.currentThread().getName());
            return img;
        }
        log.info("ImageTools: consumeGeneratedImage ThreadLocal 为空, thread={} (需调用 consumeByUrl 回退)",
                Thread.currentThread().getName());
        return null;
    }

    /**
     * 通过生成图片的 DashScope URL 从 ConcurrentHashMap 取出图片数据（跨线程回退路径）。
     *
     * @param imageUrl DashScope 返回的图片 URL
     * @return 下载好的图片数据，未找到时返回 null
     */
    public static ReceivedContent consumeGeneratedImageByUrl(String imageUrl) {
        if (imageUrl == null) return null;
        ReceivedContent rc = GENERATED_IMAGE_STORE.remove(imageUrl);
        if (rc != null) {
            log.info("ImageTools: consumeGeneratedImageByUrl 从 store 取出, url={}, thread={}",
                    truncateForLog(imageUrl, 120), Thread.currentThread().getName());
        }
        return rc;
    }

    // ==================== 工具方法 ====================

    @Tool(description = "调用外部服务生成图片。prompt 为图片描述（必填），style 为风格，size 为尺寸。")
    public String generateImage(
            @ToolParam(description = "图片的文字描述，例如：一只坐在窗边的橘猫看着窗外的雨") String prompt,
            @ToolParam(description = "图片风格，例如：写实、卡通、水彩、油画。可不传") String style,
            @ToolParam(description = "图片尺寸，例如：1024*1024。可不传，默认 1024*1024") String size) {

        try {
            log.info("ImageTools.generateImage: prompt={}, style={}, size={}, thread={}",
                    prompt, style, size, Thread.currentThread().getName());
            String imageUrl = imageService.generateImage(prompt, style, size);

            // 下载图片二进制数据，双路径存储（ThreadLocal + ConcurrentHashMap）
            try {
                byte[] imageData = imageService.downloadImage(imageUrl);
                storeGeneratedImage(imageUrl, imageData, "generated.png");
            } catch (Exception e) {
                log.warn("图片下载失败，仅返回 URL: url={}, error={}",
                        truncateForLog(imageUrl, 120), e.getMessage());
            }

            return "图片已生成，链接如下（有效期 24 小时）：\n" + imageUrl;
        } catch (IllegalArgumentException e) {
            log.warn("图片生成参数无效: {}", e.getMessage());
            return "图片生成失败：" + e.getMessage();
        } catch (RuntimeException e) {
            log.error("图片生成异常", e);
            return "图片生成失败：" + e.getMessage();
        }
    }

    @Tool(description = "识别和描述图片内容。imageUrl 为图片引用或 URL。")
    public String describeImage(
            @ToolParam(description = "图片引用（如 ref:0）或图片 URL") String imageUrl) {

        try {
            // 解析 ref:N 引用为真实 URL（base64 data URL 或公网 URL）
            String realUrl = resolveImageUrl(imageUrl);
            if (realUrl == null) {
                return "图片引用已过期（" + imageUrl + "），请重新上传图片后再试。";
            }
            log.info("ImageTools.describeImage: imageUrl={}", truncateForLog(realUrl, 120));
            String description = imageService.describeImage(realUrl);
            return "图片描述：" + description;
        } catch (IllegalArgumentException e) {
            log.warn("图片识别参数无效: {}", e.getMessage());
            return "图片识别失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("图片识别异常", e);
            return "图片识别暂时不可用，请稍后再试。";
        }
    }

    /**
     * 丢弃已生成但未被消费的图片（任务取消/暂停被覆盖时使用）。
     * <p>
     * 同时清理 ThreadLocal 和 ConcurrentHashMap 中的残留，
     * 避免虚拟线程复用污染和内存泄漏。
     * 与 {@link #consumeGeneratedImage()} 的区别：本方法不返回数据，直接丢弃。
     */
    public static void discardGeneratedImage() {
        ReceivedContent img = GENERATED_IMAGE_HOLDER.get();
        GENERATED_IMAGE_HOLDER.remove();
        if (img != null) {
            GENERATED_IMAGE_STORE.entrySet().removeIf(e -> e.getValue() == img);
            log.info("ImageTools: 已丢弃生成图片, thread={}, size={} bytes",
                    Thread.currentThread().getName(),
                    img.toBytes() != null ? img.toBytes().length : 0);
        }
    }
}
