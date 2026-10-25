package org.example.ykd_summer.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.ReceivedContent;
import org.example.ykd_summer.dto.VideoMetadata;
import org.example.ykd_summer.dto.VideoPublishResult;
import org.example.ykd_summer.dto.VideoUploadResult;
import org.example.ykd_summer.service.BilibiliLoginSessionService;
import org.example.ykd_summer.service.BilibiliService;
import org.example.ykd_summer.service.BilibiliVideoPublishService;
import org.example.ykd_summer.utils.AttachmentContext;
import org.example.ykd_summer.utils.BilibiliToolContext;
import org.example.ykd_summer.utils.VideoUploadPendingStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BILIBILI Agent 的工具集。
 * <p>
 * 当前支持的能力：
 * <ul>
 *   <li>在已登录的 B站账号上发布纯文本动态（{@link #postBilibiliDynamic(String)}）</li>
 * </ul>
 * <p>
 * Cookie 过期时自动触发扫码登录流程，返回二维码供用户扫描，
 * 用户扫码后说「继续」即可恢复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BilibiliAgentTools {

    private final BilibiliService bilibiliService;
    private final BilibiliLoginSessionService loginSessionManager;
    private final BilibiliVideoPublishService publishService;
    private final BilibiliStoreTools bilibiliStoreTools;

    /** Tool 返回值中触发登录流程的标记 */
    private static final String LOGIN_REQUIRED_PREFIX = "[LOGIN_REQUIRED]";

    @Tool(description = "在已登录的B站账号上发布一条纯文本动态。登录过期会自动触发扫码。")
    public String postBilibiliDynamic(
            @ToolParam(description = "要发布的动态文本内容（纯文本，不要添加额外格式）") String content) {

        String userId = BilibiliToolContext.getCurrentUserId();
        // 编排层指定的内容优先使用（防止 LLM 臆测或附加非用户指定的文本）
        String effectiveContent = BilibiliToolContext.getIntendedDynamicContent();
        if (effectiveContent != null && !effectiveContent.isBlank()) {
            log.info("BilibiliAgentTools: postBilibiliDynamic 使用编排层指定内容（原始LLM content={}）", content);
            content = effectiveContent;
        }
        log.info("BilibiliAgentTools: postBilibiliDynamic 被调用, userId={}, content={}", userId, content);

        if (userId == null) {
            log.warn("BilibiliAgentTools: 无法获取当前用户 ID");
            return "❌ 无法获取用户身份，请稍后重试。";
        }

        if (content == null || content.isBlank()) {
            return "❌ 动态内容不能为空，请提供要发布的文本内容。";
        }

        return ensureLoginAndPost(userId, content, true);
    }

    @Tool(description = "在已登录的B站账号上发布图文动态。图片从用户之前发送的图片中获取。")
    public String postBilibiliDynamicWithImage(
            @ToolParam(description = "要发布的动态文本内容（纯文本，不要添加额外格式）") String content) {

        String userId = BilibiliToolContext.getCurrentUserId();
        // 编排层指定的内容优先使用（防止 LLM 臆测或附加非用户指定的文本）
        String effectiveContent = BilibiliToolContext.getIntendedDynamicContent();
        if (effectiveContent != null && !effectiveContent.isBlank()) {
            log.info("BilibiliAgentTools: postBilibiliDynamicWithImage 使用编排层指定内容（原始LLM content={}）",
                    content);
            content = effectiveContent;
        }
        log.info("BilibiliAgentTools: postBilibiliDynamicWithImage 被调用, userId={}, content={}", userId, content);

        if (userId == null) {
            log.warn("BilibiliAgentTools: 无法获取当前用户 ID");
            return "❌ 无法获取用户身份，请稍后重试。";
        }

        if (content == null || content.isBlank()) {
            return "❌ 动态内容不能为空，请提供要发布的文本内容。";
        }

        // 从 AttachmentContext 获取暂存图片
        List<ReceivedContent> images = AttachmentContext.getAndClear(userId);
        if (images == null || images.isEmpty()) {
            return "❌ 未找到要上传的图片。请先发送图片，再发送指令如「发B站动态配这张图：xxx」。";
        }

        ReceivedContent image = images.get(0);
        if (!image.isImage()) {
            return "❌ 暂存的文件不是图片格式，请先发送一张图片。";
        }

        byte[] imageBytes = image.toBytes();
        if (imageBytes == null || imageBytes.length == 0) {
            return "❌ 图片数据读取失败，请重新发送图片。";
        }

        String mimeType = image.mimeType();
        String fileName = image.fileName();
        log.info("BilibiliAgentTools: 从 AttachmentContext 获取到图片, size={} bytes, mime={}, fileName={}",
                imageBytes.length, mimeType, fileName);

        return ensureLoginAndPostWithImage(userId, content, imageBytes, mimeType, fileName, true);
    }

    @Tool(description = "上传并发布视频到B站。视频从用户之前发送的视频附件中获取。")
    public String postBilibiliVideo(
            @ToolParam(description = "视频文件名（从用户消息中提取，如 'test.mp4'）") String fileName,
            @ToolParam(description = "视频标题（用户指定则使用，否则用文件名）", required = false) String title,
            @ToolParam(description = "视频简介（用户指定则使用，否则留空）", required = false) String description,
            @ToolParam(description = "视频标签，多个用逗号分隔（如 '日常,vlog'）", required = false) String tags,
            @ToolParam(description = "目标分区（如 '生活''日常''游戏'），未匹配则用默认", required = false) String partition) {

        String userId = BilibiliToolContext.getCurrentUserId();
        log.info("BilibiliAgentTools: postBilibiliVideo 被调用, userId={}, fileName={}, title={}, desc={}, tags={}, partition={}",
                userId, fileName, title, description, tags, partition);

        if (userId == null) {
            log.warn("BilibiliAgentTools: 无法获取当前用户 ID");
            return "❌ 无法获取用户身份，请稍后重试。";
        }

        // 获取附件与封面意图（编排层正则检测结果）
        List<ReceivedContent> attachments = AttachmentContext.getAndClear(userId);
        ReceivedContent imageAttachment = attachments == null ? null
                : attachments.stream().filter(ReceivedContent::isImage).findFirst().orElse(null);
        String coverChoice = BilibiliToolContext.getAndClearCoverChoice(userId);

        // ── 续传场景：有等待封面的上传任务 ──
        VideoUploadPendingStore.PendingUpload pending = VideoUploadPendingStore.peek(userId);
        if (pending != null) {
            log.info("BilibiliAgentTools: 检测到等待封面的上传任务，恢复执行 userId={}, fileName={}",
                    userId, pending.fileName());
            byte[] coverBytes;
            if (imageAttachment != null) {
                coverBytes = imageAttachment.toBytes();
                if (coverBytes == null || coverBytes.length == 0) {
                    return "❌ 封面图片读取失败，请重新发送封面图片，或回复「使用默认封面」。";
                }
                log.info("BilibiliAgentTools: 使用用户提供的封面图片, size={} bytes", coverBytes.length);
                // 将封面一并暂存：若恰逢登录过期需扫码，扫码后说「继续」恢复任务时封面不丢失
                VideoUploadPendingStore.put(userId, pending.videoBytes(), pending.fileName(),
                        pending.metadata(), coverBytes);
            } else if ("default".equals(coverChoice)) {
                coverBytes = null;   // null → 使用默认封面
                log.info("BilibiliAgentTools: 用户选择默认封面");
            } else if (pending.coverBytes() != null) {
                // 恢复此前已暂存的封面（如扫码登录后的续传场景）
                coverBytes = pending.coverBytes();
                log.info("BilibiliAgentTools: 使用此前暂存的封面图片, size={} bytes", coverBytes.length);
            } else {
                // 既没发图也没说默认 → 继续等待
                return "⏳ 还在等待封面哦：\n"
                        + "1. 直接发送一张图片作为封面（收到后自动继续）\n"
                        + "2. 回复「使用默认封面」跳过";
            }
            // 不在此清除 pending：若恰逢登录过期，扫码后说「继续」还能恢复任务；
            // pending 在上传成功后由 doUploadAndPublish 清除
            return ensureLoginAndUploadVideo(userId, pending.videoBytes(), pending.fileName(),
                    pending.metadata(), coverBytes);
        }

        // ── 首次调用：从附件取视频 ──
        ReceivedContent video = attachments == null ? null
                : attachments.stream().filter(ReceivedContent::isVideo).findFirst().orElse(null);
        if (video == null) {
            return "❌ 未找到要上传的视频。请先发送视频，再发送指令如「把这个视频发到B站」。";
        }

        byte[] videoBytes = video.toBytes();
        if (videoBytes == null || videoBytes.length == 0) {
            return "❌ 视频数据读取失败，请重新发送视频。";
        }

        String actualFileName = fileName != null && !fileName.isBlank() ? fileName : video.fileName();
        log.info("BilibiliAgentTools: 从 AttachmentContext 获取到视频, size={} bytes, mime={}, fileName={}",
                videoBytes.length, video.mimeType(), actualFileName);

        // 构建元数据
        VideoMetadata metadata = buildMetadata(actualFileName, title, description, tags, partition);
        log.info("BilibiliAgentTools: 视频元数据: title={}, desc={}, tags={}, tid={}",
                metadata.title(), metadata.description(), metadata.tags(), metadata.tid());

        // ── 封面决策 ──
        if (imageAttachment != null) {
            // 附件自带图片（用户先发图或图文同发）→ 直接用作封面
            byte[] coverBytes = imageAttachment.toBytes();
            if (coverBytes != null && coverBytes.length > 0) {
                log.info("BilibiliAgentTools: 附件中检测到图片，直接用作封面, size={} bytes", coverBytes.length);
                return ensureLoginAndUploadVideo(userId, videoBytes, actualFileName, metadata, coverBytes);
            }
        }
        if ("default".equals(coverChoice)) {
            // 用户明确使用默认封面 → 直接执行
            log.info("BilibiliAgentTools: 用户指令中明确使用默认封面");
            return ensureLoginAndUploadVideo(userId, videoBytes, actualFileName, metadata, null);
        }
        // 无图且无明确选择 → 暂存任务，询问封面
        VideoUploadPendingStore.put(userId, videoBytes, actualFileName, metadata);
        if ("hint".equals(coverChoice)) {
            // 用户说了"封面如下"但图片还没到 → 等待图片
            log.info("BilibiliAgentTools: 检测到「封面如下」提示，等待用户发送封面图片 userId={}", userId);
            return "好的，请发送封面图片～\n"
                    + "收到图片后会自动继续上传发布；也可以回复「使用默认封面」跳过。";
        }
        log.info("BilibiliAgentTools: 未提供封面，暂存任务并询问 userId={}", userId);
        return "未检测到封面图片。请选择封面方式：\n"
                + "1. 直接发送一张图片作为封面（收到后自动继续上传发布）\n"
                + "2. 回复「使用默认封面」直接使用默认封面";
    }

    /** 根据用户输入构建视频元数据（缺省值兜底） */
    private VideoMetadata buildMetadata(String fileName, String title, String description,
                                        String tags, String partition) {
        VideoMetadata base = VideoMetadata.defaults(fileName);
        int tid = VideoMetadata.resolvePartition(partition)[0];
        java.util.List<String> tagList = base.tags();
        if (tags != null && !tags.isBlank()) {
            tagList = java.util.Arrays.stream(tags.split("[,，、]"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(10)
                    .toList();
        }
        return new VideoMetadata(
                title != null && !title.isBlank() ? title : base.title(),
                description != null ? description : "",
                tagList,
                VideoMetadata.partitionMainOf(tid),
                VideoMetadata.partitionSubOf(tid),
                tid);
    }

    /** 确保登录态有效后执行视频上传+发布 */
    private String ensureLoginAndUploadVideo(String userId, byte[] videoBytes, String fileName,
                                             VideoMetadata metadata, byte[] coverBytes) {
        // 1. 检查 Cookie 是否有效
        if (bilibiliService.checkCookieValid()) {
            loginSessionManager.completeLoginIfPending(userId);
            return doUploadAndPublish(videoBytes, fileName, metadata, coverBytes);
        }

        // 2. Cookie 过期或不存在 → 检查是否已有活跃的扫码会话
        if (loginSessionManager.hasActiveSession()) {
            if (loginSessionManager.completeLoginIfPending(userId)) {
                bilibiliService.refreshWbiKeys();
                return doUploadAndPublish(videoBytes, fileName, metadata, coverBytes);
            }
            byte[] qrCode = loginSessionManager.startLogin();
            if (qrCode != null) {
                BilibiliToolContext.setPendingImage(userId, qrCode);
            }
            return LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                    + "完成后回复【继续】，重新上传视频：" + fileName;
        }

        // 3. 无活跃会话 → 启动新的登录流程
        byte[] qrCode = loginSessionManager.startLogin();
        if (qrCode != null) {
            BilibiliToolContext.setPendingImage(userId, qrCode);
        } else {
            return "❌ 无法启动B站登录流程，请稍后重试。";
        }

        String result = LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                + "完成后回复【继续】，重新上传视频：" + fileName;

        log.info("BilibiliAgentTools: 已触发扫码登录流程（视频上传）, userId={}, fileName={}", userId, fileName);
        return result;
    }

    /** 执行视频上传 + Playwright 发布的完整流程 */
    private String doUploadAndPublish(byte[] videoBytes, String fileName, VideoMetadata metadata,
                                      byte[] coverBytes) {
        try {
            // Step 1: API 上传视频到草稿箱（含元数据+封面）
            VideoUploadResult uploadResult = bilibiliService.uploadVideo(videoBytes, fileName, metadata, coverBytes);
            if (uploadResult == null || !uploadResult.isSuccess()) {
                log.warn("BilibiliAgentTools: 视频上传失败, fileName={}", fileName);
                return "❌ 视频上传失败，请稍后重试。";
            }
            log.info("BilibiliAgentTools: 视频上传成功, fileName={}, cid={}, draftAid={}",
                    uploadResult.fileName(), uploadResult.cid(), uploadResult.draftAid());

            // 上传成功，清除可能存在的等待封面任务（防止后续误恢复重传）
            VideoUploadPendingStore.clear(BilibiliToolContext.getCurrentUserId());

            if (uploadResult.draftAid() == null) {
                return "⚠️ 视频已上传但草稿保存异常，请到创作中心手动处理。";
            }

            // Step 2: Playwright 打开发布页并点击发布
            VideoPublishResult publishResult = publishService.publishDraft(
                    uploadResult.draftAid(), metadata);

            if (publishResult.success()) {
                // 发布成功后记录到创作档案向量库
                bilibiliStoreTools.saveUploadRecord(
                        BilibiliToolContext.getCurrentUserId(), metadata);

                String bvid = publishResult.bvid();
                StringBuilder sb = new StringBuilder("✅ 视频发布成功！\n");
                sb.append("标题: ").append(metadata.title()).append("\n");
                if (bvid != null) {
                    sb.append("BV号: ").append(bvid).append("\n");
                    sb.append("链接: https://www.bilibili.com/video/").append(bvid);
                } else {
                    sb.append("稿件已提交审核（未能自动获取BV号）。");
                }
                return sb.toString();
            } else {
                log.warn("BilibiliAgentTools: 发布失败, draftAid={}, reason={}",
                        uploadResult.draftAid(), publishResult.message());
                return "⚠️ 视频已上传到草稿箱，但自动发布未完成。\n"
                        + "原因: " + publishResult.message() + "\n"
                        + "请前往创作中心手动完成发布：\n"
                        + publishResult.draftUrl();
            }
        } catch (Exception e) {
            log.error("BilibiliAgentTools: 视频上传发布异常", e);
            return "❌ 视频上传发布失败：" + e.getMessage();
        }
    }

    /** 确保登录态有效后执行纯文本发动态 */
    private String ensureLoginAndPost(String userId, String content, boolean allowRetry) {
        // 1. 检查 Cookie 是否有效
        if (bilibiliService.checkCookieValid()) {
            loginSessionManager.completeLoginIfPending(userId);
            return doPostDynamic(content);
        }

        // 2. Cookie 过期或不存在 → 检查是否已有活跃的扫码会话
        if (loginSessionManager.hasActiveSession()) {
            if (loginSessionManager.completeLoginIfPending(userId)) {
                bilibiliService.refreshWbiKeys();
                return doPostDynamic(content);
            }
            byte[] qrCode = loginSessionManager.startLogin();
            if (qrCode != null) {
                BilibiliToolContext.setPendingImage(userId, qrCode);
            }
            return LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                    + "完成后回复【继续】，将对以下内容重新发布：\n\"" + content + "\"";
        }

        // 3. 无活跃会话 → 启动新的登录流程
        byte[] qrCode = loginSessionManager.startLogin();
        if (qrCode != null) {
            BilibiliToolContext.setPendingImage(userId, qrCode);
        } else {
            return "❌ 无法启动B站登录流程，请稍后重试。";
        }

        String result = LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                + "完成后回复【继续】，将对以下内容重新发布：\n\"" + content + "\"";

        log.info("BilibiliAgentTools: 已触发扫码登录流程, userId={}, content={}", userId, content);
        return result;
    }

    /** 确保登录态有效后执行带图发动态 */
    private String ensureLoginAndPostWithImage(String userId, String content, byte[] imageBytes,
                                            String mimeType, String fileName, boolean allowRetry) {
        // 1. 检查 Cookie 是否有效
        if (bilibiliService.checkCookieValid()) {
            loginSessionManager.completeLoginIfPending(userId);
            return doPostDynamicWithImage(content, imageBytes, mimeType, fileName);
        }

        // 2. Cookie 过期或不存在 → 检查是否已有活跃的扫码会话
        if (loginSessionManager.hasActiveSession()) {
            if (loginSessionManager.completeLoginIfPending(userId)) {
                bilibiliService.refreshWbiKeys();
                return doPostDynamicWithImage(content, imageBytes, mimeType, fileName);
            }
            byte[] qrCode = loginSessionManager.startLogin();
            if (qrCode != null) {
                BilibiliToolContext.setPendingImage(userId, qrCode);
            }
            return LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                    + "完成后回复【继续】，将对以下内容重新发布（含图片）：\n\"" + content + "\"";
        }

        // 3. 无活跃会话 → 启动新的登录流程
        byte[] qrCode = loginSessionManager.startLogin();
        if (qrCode != null) {
            BilibiliToolContext.setPendingImage(userId, qrCode);
        } else {
            return "❌ 无法启动B站登录流程，请稍后重试。";
        }

        String result = LOGIN_REQUIRED_PREFIX + " 🔐 B站登录已过期，请使用B站App扫描上方二维码完成登录。"
                + "完成后回复【继续】，将对以下内容重新发布（含图片）：\n\"" + content + "\"";

        log.info("BilibiliAgentTools: 已触发扫码登录流程（带图）, userId={}, content={}", userId, content);
        return result;
    }

    /** 执行实际的发纯文本动态操作 */
    private String doPostDynamic(String content) {
        try {
            boolean ok = bilibiliService.postDynamic(content);
            if (ok) {
                log.info("BilibiliAgentTools: 动态发布成功, content={}", content);
                return "✅ B站动态发布成功！";
            } else {
                log.warn("BilibiliAgentTools: 动态发布失败, content={}", content);
                return "❌ B站动态发布失败，请稍后重试。";
            }
        } catch (Exception e) {
            log.error("BilibiliAgentTools: 发布动态异常", e);
            return "❌ B站动态发布失败：" + e.getMessage();
        }
    }

    /** 执行实际的发带图动态操作 */
    private String doPostDynamicWithImage(String content, byte[] imageBytes, String mimeType, String fileName) {
        try {
            boolean ok = bilibiliService.postDynamicWithImage(content, imageBytes, mimeType, fileName);
            if (ok) {
                log.info("BilibiliAgentTools: 带图动态发布成功, content={}", content);
                return "✅ B站带图动态发布成功！";
            } else {
                log.warn("BilibiliAgentTools: 带图动态发布失败, content={}", content);
                return "❌ B站带图动态发布失败，请稍后重试。";
            }
        } catch (Exception e) {
            log.error("BilibiliAgentTools: 发布带图动态异常", e);
            return "❌ B站带图动态发布失败：" + e.getMessage();
        }
    }
}
