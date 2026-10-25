package org.example.ykd_summer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.billing.TokenUsageTracker;
import org.example.ykd_summer.dto.MatchReplyResult;
import org.example.ykd_summer.dto.PausedTaskState;
import org.example.ykd_summer.utils.LogUtils;
import org.example.ykd_summer.utils.TaskControlContext;
import org.example.ykd_summer.dto.OrchestrationPlan;
import org.example.ykd_summer.dto.PlanStep;
import org.example.ykd_summer.dto.ReceivedContent;
import org.example.ykd_summer.service.AiChatService;
import org.example.ykd_summer.service.OrchestrationService;
import org.example.ykd_summer.tools.FileTools;
import org.example.ykd_summer.utils.ImageWaitStore;
import org.example.ykd_summer.utils.VideoUploadPendingStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI 聊天服务实现。
 * <p>
 * 采用「路由编排 + 分发执行」两级架构：
 * <ol>
 *   <li>路由阶段：调用 routerChatClient（多模态 qwen-vl-plus），
 *       将用户消息转为 JSON 格式的步骤编排计划（{@link OrchestrationPlan}）</li>
 *   <li>分发阶段：{@link OrchestrationService} 解析计划，
 *       按依赖关系拓扑排序后逐步执行各子 Agent，最终聚合结果</li>
 * </ol>
 * <p>
 * 路由 Agent 不参与会话记忆（只做单次编排），
 * 文本/图片 Agent 各自维护独立会话记忆，通过 conversationId 隔离用户。
 *
 * @see OrchestrationService
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    /** 路由 Agent 使用的多模态模型（仅用于任务编排，不用于图片识别/生成） */
    private static final String ROUTER_MODEL = "qwen-vl-plus";

    private final ChatClient routerChatClient;
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;

    /** 追踪每个用户上一轮的消息文本、任务域及 AI 真实回复，为 Router 注入完整对话上下文 */
    private record RoundSummary(String userText, String domainLabel, String assistantReply) {}

    private final java.util.concurrent.ConcurrentHashMap<String, RoundSummary> lastRound =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 用户发送 #new 重置会话时调用：清除 Router 的"上一轮对话概况"残留，
     * 否则下一轮 Router 会继续注入已失效的上轮上下文（如上一轮是 IMAGE 步骤），
     * 导致单步编排拿到错误/空数据。
     */
    public void clearLastRound(String userId) {
        lastRound.remove(userId);
    }

    public AiChatServiceImpl(ChatClient routerChatClient,
                             OrchestrationService orchestrationService,
                             ObjectMapper objectMapper,
                             TokenUsageTracker tokenUsageTracker) {
        this.routerChatClient = routerChatClient;
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
        this.tokenUsageTracker = tokenUsageTracker;
    }

    @Override
    public MatchReplyResult matchReply(String userId, String sessionId, String text, List<ReceivedContent> attachments,
                                        Consumer<String> onProgress) {
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if (text == null || text.isBlank() || isSemanticallyEmpty(text)) {
            text = hasAttachments ? "请帮我分析这个内容" : "你好";
        }
        final String userText = text;
        final List<ReceivedContent> safeAttachments = attachments != null ? attachments : List.of();
        try {
            // === 暂停态优先处理 ===
            // 用户此前下发了多步骤任务并暂停。暂停期间用户可能发来：
            //   - 继续（RESUME）：恢复执行
            //   - 取消（CANCEL）：放弃任务
            //   - 修改/补充指令（MODIFY）：累积进原任务，保留暂停态，待"继续"时生效
            //   - 无关闲聊（CHITCHAT）：视为新问题，丢弃暂停任务并按正常新消息处理
            // 意图统一交由 LLM 判定（classifyPauseMessage），避免关键词硬匹配误判。
            if (TaskControlContext.hasPausedTask(userId)) {
                PausedTaskState state = TaskControlContext.consumePausedTask(userId);
                if (state != null) {
                    PauseIntent intent = classifyPauseMessage(state.userText(), userText);
                    log.info("暂停态意图识别 userId={}, intent={}, msg={}", userId, intent, userText);
                    switch (intent) {
                        case RESUME -> {
                            log.info("恢复暂停任务 userId={}, 已完成 {} 步", userId, state.stepOutputs().size());
                            MatchReplyResult result = orchestrationService.resume(state, onProgress);
                            log.info("暂停任务恢复完成 from={}, reply={}", userId,
                                    LogUtils.truncate(result.textReply(), 50));
                            return result;
                        }
                        case CANCEL -> {
                            orchestrationService.clearPausedResources(userId);
                            TaskControlContext.discardPausedTask(userId);
                            return new MatchReplyResult("任务已取消~", null, false);
                        }
                        case MODIFY -> {
                            // 累积修改指令，重新存回暂停态（保留任务，不丢弃）
                            PausedTaskState updated = state.withModifyInstruction(userText);
                            TaskControlContext.savePausedTask(userId, updated);
                            return new MatchReplyResult(
                                    "已记录你的修改：" + userText + "，回复「继续」按新指令执行~", null, false);
                        }
                        case CHITCHAT -> {
                            // 与原任务无关的闲聊/新问题：丢弃暂停任务，按正常新消息处理（落到下方 Router 流程）
                            orchestrationService.clearPausedResources(userId);
                            TaskControlContext.discardPausedTask(userId);
                            log.info("暂停态收到无关消息，丢弃暂停任务并按新任务处理 userId={}", userId);
                        }
                    }
                }
            }

            // === 阶段 1：路由编排（多模态输入 → JSON 步骤计划） ===
            // 确定性短路 1：等待封面的视频上传任务 → 直接恢复 BILIBILI 上传
            // 确定性短路 2：等待配图的动态发布任务 → 图片到达后直接编排 BILIBILI 带图步骤
            OrchestrationPlan plan;
            if (VideoUploadPendingStore.hasPending(userId) && isUploadContinuation(userText, safeAttachments)) {
                log.info("检测到等待封面的视频上传任务，跳过路由直接恢复 BILIBILI 上传 from={}", userId);
                plan = buildResumeUploadPlan(userText, safeAttachments);
            } else if (ImageWaitStore.hasPending(userId) && hasImageAttachment(safeAttachments)) {
                log.info("检测到等待配图的动态发布任务且图片已到达，跳过路由直接编排 BILIBILI 带图动态 from={}", userId);
                ImageWaitStore.PendingImageTask pending = ImageWaitStore.get(userId);
                plan = buildDynamicWithImagePlan(pending.originalText(), userText, safeAttachments);
                ImageWaitStore.remove(userId);
            } else {
                plan = callRouterAndParse(userId, userText, safeAttachments);
            }

            // === 阶段 2：编排执行（拓扑排序 → 逐步执行 → 聚合结果） ===
            MatchReplyResult result = orchestrationService.execute(plan, userId, sessionId, userText, safeAttachments, onProgress);

            // 记录本轮用户消息 + 任务域 + 真实回复，供下一轮 Router 注入完整上下文。
            // assistantReply 用于让 Router 在跨轮指代场景（如"给刚才推荐的电影做海报"）
            // 能从上一轮真实输出中提取具体实体（《八仙》），写进新计划的 task/input_hint。
            lastRound.put(userId, new RoundSummary(userText, extractDomainLabel(plan), result.textReply()));

            log.info("AI 回复 from={}, reply={}, hasFile={}",
                    userId, LogUtils.truncate(result.textReply(), 50), result.hasFile());
            return result;
        } catch (Exception e) {
            log.error("大模型调用失败 from={}", userId, e);
            return new MatchReplyResult("抱歉，AI 服务暂时不可用，请稍后再试~", null, false);
        }
    }

    // ==================== 视频上传续传短路 ====================

    /**
     * 判断当前消息是否为「等待封面的视频上传任务」的恢复指令。
     * 仅在 {@link VideoUploadPendingStore#hasPending} 为 true 时调用。
     * <ul>
     *   <li>回复「使用默认封面」→ 恢复，使用默认封面</li>
     *   <li>纯「继续」类指令（IlinkBotRunner 在纯封面图片场景也会注入"继续"）→ 恢复</li>
     *   <li>图片 + 封面相关表述（如"用这张图当封面"）→ 恢复，图片作为封面</li>
     * </ul>
     * 其他消息（如无关闲聊、识图请求）不视为恢复指令，走正常路由，任务保留待恢复。
     */
    private static boolean isUploadContinuation(String userText, List<ReceivedContent> attachments) {
        String t = userText == null ? "" : userText.trim();
        String cleaned = t.replaceAll("[\\s\\p{P}\\p{S}]", "");
        if (t.contains("默认封面")) {
            return true;
        }
        if (cleaned.equals("继续") || cleaned.equals("继续上传") || cleaned.equals("继续发布")) {
            return true;
        }
        boolean hasImage = attachments != null && attachments.stream().anyMatch(ReceivedContent::isImage);
        return hasImage && cleaned.contains("封面");
    }

    /**
     * 构建恢复视频上传的单步 BILIBILI 计划（绕过路由 LLM 的确定性编排）。
     * task 中保留「默认封面」关键字，供编排层正则识别封面选择。
     */
    private static OrchestrationPlan buildResumeUploadPlan(String userText, List<ReceivedContent> attachments) {
        boolean useDefault = userText != null && userText.contains("默认封面");
        boolean hasImage = attachments != null && attachments.stream().anyMatch(ReceivedContent::isImage);
        String task;
        if (useDefault) {
            task = "继续上传视频到B站，使用默认封面";
        } else if (hasImage) {
            task = "继续上传视频到B站，使用用户刚发送的图片作为封面";
        } else {
            task = "继续上传视频到B站";
        }
        PlanStep step = new PlanStep(0, "BILIBILI", task,
                "恢复等待封面的视频上传任务", Collections.emptyList());
        return new OrchestrationPlan(Collections.singletonList(step), false);
    }

    // ==================== 带图动态发布短路 ====================

    /** 判断附件列表中是否包含图片 */
    private static boolean hasImageAttachment(List<ReceivedContent> attachments) {
        return attachments != null && attachments.stream().anyMatch(ReceivedContent::isImage);
    }

    /**
     * 构建带图 B站动态发布的单步 BILIBILI 计划（绕过路由 LLM 的确定性编排）。
     * <p>
     * 当 Runner 等待图片期间 Router 仍误判为 WAIT_IMAGE 时，matchReply 入口调用此方法短路。
     * 正常情况下图片到达后走路由即可，此方法仅作为安全网。
     */
    private static OrchestrationPlan buildDynamicWithImagePlan(String originalText,
                                                                 String mergedText,
                                                                 List<ReceivedContent> attachments) {
        // 直接使用原始指令作为配文来源；mergedText 已包含 originalText（Runner 合并时拼接），
        // 故不再重复拼接，避免文本污染。
        String task = "发布一条B站图文动态，" + originalText + "，并将附带的图片作为配图";
        PlanStep step = new PlanStep(0, "BILIBILI", task,
                "恢复等待配图的动态发布任务，图片已到达", Collections.emptyList());
        return new OrchestrationPlan(Collections.singletonList(step), false);
    }

    // ==================== 路由调用与 JSON 解析 ====================

    /**
     * 调用路由 Agent 获取编排计划，解析 JSON 返回 {@link OrchestrationPlan}。
     * <p>
     * 图片附件转为 base64 Data URL 后以 {@link Media} 形式传入多模态路由模型。
     * <p>
     * JSON 解析失败时，降级为单步 TEXT 计划，确保系统不会因 LLM 输出
     * 格式异常而崩溃。
     */
    private OrchestrationPlan callRouterAndParse(String userId, String userText,
                                                  List<ReceivedContent> attachments) {
        // 筛选图片附件，转为 base64 Data URL（路由阶段需要多模态输入）
        final List<ReceivedContent> images = attachments.stream()
                .filter(ReceivedContent::isImage)
                .toList();

        // 将文档类附件信息附加到 prompt 文本中，让路由模型知晓文件存在及其类型
        final String docInfoText = appendDocumentInfo(userText, attachments);

        // 注入跨轮文件上下文：当前轮无附件但上一轮绑定了文件时，让 Router 感知到已有文件
        String boundFilesSummary = FileTools.getBoundFilesSummary();
        String baseText = boundFilesSummary != null
                ? (docInfoText != null ? docInfoText : userText) + boundFilesSummary
                : docInfoText;

        // 注入上一轮对话上下文（消息 + 任务域），帮助 Router 正确路由及判断 new_topic
        // 采用结构化分字段格式，将"上一轮 Agent"提升为一级信号，引导 Router 优先对比 Agent 类型
        RoundSummary prevRound = lastRound.get(userId);
        final String augmentedText;
        if (prevRound != null) {
            String prevReply = prevRound.assistantReply() != null ? prevRound.assistantReply() : "";
            // 截断过长回复，仅保留足够提取实体的上下文（约前 400 字）
            if (prevReply.length() > 400) {
                prevReply = prevReply.substring(0, 400) + "…";
            }
            augmentedText = "【上一轮对话概况】\n"
                    + "· 上一轮 Agent：" + extractAgentFromLabel(prevRound.domainLabel()) + "\n"
                    + "· 上一轮任务：" + prevRound.domainLabel() + "\n"
                    + "· 上一轮用户消息：" + prevRound.userText() + "\n"
                    + "· 上一轮 AI 真实回复（含具体实体名，如电影/作品名）：" + prevReply + "\n\n"
                    + "【当前用户消息】" + baseText + "\n"
                    + "（请先判断：本轮应把任务分配给哪个 Agent？若当前消息指代上一轮的实体，"
                    + "请在 task/input_hint 中直接写明该实体名，不要只写“上一轮推荐的”。）";
            log.info("Router 注入上轮上下文: domain={}, prevUserText={}, prevReplyLen={}",
                    prevRound.domainLabel(), LogUtils.truncate(prevRound.userText(), 50),
                    prevReply.length());
        } else {
            augmentedText = baseText;
        }

        String rawResponse;
        try {
            ChatResponse routerResponse = routerChatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(ROUTER_MODEL))
                    .user(userSpec -> {
                        userSpec.text(augmentedText);
                        for (ReceivedContent img : images) {
                            String dataUrl = img.toDataUrl();
                            if (dataUrl != null) {
                                userSpec.media(new Media(MimeTypeUtils.IMAGE_PNG,
                                        URI.create(dataUrl)));
                            }
                        }
                    })
                    .call()
                    .chatResponse();
            rawResponse = routerResponse.getResult().getOutput().getText();
            Usage usage = routerResponse.getMetadata().getUsage();
            if (usage != null) {
                long cachedTokens = extractCachedPromptTokens(usage);
                if (cachedTokens > 0) {
                    long rawPrompt = usage.getPromptTokens() + cachedTokens;
                    log.info("recordTokenUsage: agent=Router, promptTokens={}, completionTokens={}, "
                                    + "totalTokens={}, cachedTokens={}, cacheHitRate={:.1f}%",
                            usage.getPromptTokens(), usage.getCompletionTokens(),
                            usage.getTotalTokens(), cachedTokens,
                            (cachedTokens * 100.0) / rawPrompt);
                } else {
                    log.info("recordTokenUsage: agent=Router, promptTokens={}, completionTokens={}, totalTokens={}",
                            usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
                }
                tokenUsageTracker.record("Router",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens(),
                        cachedTokens);
            }
        } catch (Exception e) {
            log.error("路由 Agent 调用失败，降级", e);
            return fallbackPlan(userText, attachments);
        }

        log.info("路由 Agent 原始输出: {}", LogUtils.truncate(rawResponse, 200));

        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("路由 Agent 返回空响应，降级");
            return fallbackPlan(userText, attachments);
        }

        return parsePlanResponse(rawResponse.trim(), userText, attachments);
    }

    /**
     * 解析路由 Agent 的 JSON 输出。
     * <p>
     * 兼容 LLM 可能输出的 markdown 代码块包裹（```json ... ```），
     * 以及纯 JSON 字符串。解析失败时先尝试修复常见 JSON 格式错误，
     * 再降级为智能 fallback。
     */
    private OrchestrationPlan parsePlanResponse(String response, String userText, List<ReceivedContent> attachments) {
        // 去除可能的 markdown 代码块包裹
        String json = response;
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            if (start > 0) {
                json = json.substring(start + 1);
            }
            int end = json.lastIndexOf("```");
            if (end > 0) {
                json = json.substring(0, end);
            }
            json = json.trim();
        }

        try {
            OrchestrationPlan plan = objectMapper.readValue(json, OrchestrationPlan.class);
            log.info("路由解析成功: {} 个步骤",
                    plan.steps() != null ? plan.steps().size() : 0);
            return plan;
        } catch (Exception e) {
            log.warn("路由 JSON 解析失败: json={}, error={}", LogUtils.truncate(json, 100), e.getMessage());
            // 尝试修复常见 LLM 输出的 JSON 格式错误（如缺少 ] 等）
            String repaired = attemptJsonRepair(json);
            if (repaired != null) {
                try {
                    OrchestrationPlan plan = objectMapper.readValue(repaired, OrchestrationPlan.class);
                    log.info("JSON 修复成功: {} 个步骤",
                            plan.steps() != null ? plan.steps().size() : 0);
                    return plan;
                } catch (Exception e2) {
                    log.warn("JSON 修复后仍解析失败: {}", e2.getMessage());
                }
            }
            return fallbackPlan(userText, attachments);
        }
    }

    /**
     * 降级计划：根据用户文本和附件类型智能选择目标 Agent。
     * <p>
     * 路由 Agent 返回空/格式异常/解析失败时，确保用户请求不被丢弃，
     * 同时附件信息不会丢失：
     * <ul>
     *   <li>有文档附件 → FILE Agent，仅解析模式</li>
     *   <li>有图片附件 → IMAGE Agent，仅描述模式</li>
     *   <li>纯文本 → TEXT Agent</li>
     * </ul>
     */
    private static OrchestrationPlan fallbackPlan(String userText, List<ReceivedContent> attachments) {
        if (attachments != null && !attachments.isEmpty()) {
            boolean hasDoc = attachments.stream().anyMatch(ReceivedContent::isDocument);
            boolean hasImage = attachments.stream().anyMatch(ReceivedContent::isImage);

            if (hasDoc) {
                String task = "读取用户上传的文件内容，告知用户文件概况（文件名、内容概要），等待用户后续指令";
                PlanStep fallbackStep = new PlanStep(0, "FILE", task,
                        "降级模式：仅解析文件", Collections.emptyList());
                return new OrchestrationPlan(Collections.singletonList(fallbackStep), false);
            }
            if (hasImage) {
                String task = "描述图片内容，等待用户后续指令";
                PlanStep fallbackStep = new PlanStep(0, "IMAGE", task,
                        "降级模式：仅描述图片", Collections.emptyList());
                return new OrchestrationPlan(Collections.singletonList(fallbackStep), false);
            }
        }

        String effectiveText = (userText != null && !userText.isBlank()) ? userText : "你好";
        PlanStep fallbackStep = new PlanStep(0, "TEXT", effectiveText,
                "直接回复用户", Collections.emptyList());
        return new OrchestrationPlan(Collections.singletonList(fallbackStep), false);
    }

    /**
     * 尝试修复 LLM 输出的常见 JSON 格式错误。
     * <p>
     * 主要修复括号/花括号不匹配问题（如缺少 closing ]），
     * 通过括号计数自动补全缺失的闭合符号。
     *
     * @return 修复后的 JSON 字符串，若无需/无法修复则返回 null
     */
    private static String attemptJsonRepair(String json) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        int braceDepth = 0;
        int bracketDepth = 0;
        for (char c : trimmed.toCharArray()) {
            switch (c) {
                case '{': braceDepth++; break;
                case '}': braceDepth--; break;
                case '[': bracketDepth++; break;
                case ']': bracketDepth--; break;
            }
        }
        if (braceDepth == 0 && bracketDepth == 0) return null; // 括号已平衡，无需修复

        String repaired = trimmed;

        // 补全缺失的 ]
        while (bracketDepth > 0) {
            int lastBrace = repaired.lastIndexOf('}');
            if (lastBrace > 0) {
                repaired = repaired.substring(0, lastBrace) + "]" + repaired.substring(lastBrace);
            } else {
                repaired = repaired + "]";
            }
            bracketDepth--;
        }

        // 补全缺失的 }
        while (braceDepth > 0) {
            repaired = repaired + "}";
            braceDepth--;
        }

        if (!repaired.equals(trimmed)) {
            log.info("JSON 已自动修复: bracket补全={}, brace补全={}",
                    bracketDepth == 0 ? "done" : "N/A", "done");
            return repaired;
        }
        return null;
    }

    /**
     * 判断文本是否语义上为空（仅含标点、空白等无意义字符）。
     * <p>
     * 例如 "，" 或 "，。" 等纯标点堆积的文本应被视为空输入，
     * 避免 Router/TEXT Agent 收到无意义输入后输出无效回复。
     */
    private static boolean isSemanticallyEmpty(String text) {
        if (text == null) return true;
        // 去除空白 + Unicode 标点 + 数学符号 + 杂项符号后，检查是否还有实质内容
        String cleaned = text.replaceAll("[\\s\\p{P}\\p{S}]", "");
        return cleaned.isEmpty();
    }

    // ==================== 工具方法 ====================

    /**
     * 将文档类附件信息以文本形式附加到用户消息中，让路由模型知晓文件存在。
     * <p>
     * 路由模型（qwen-vl-plus）是多模态模型，但只接受图片作为 media 参数，
     * 因此文档类附件（.docx/.pdf/.txt 等）通过文本描述告知，而非直接传递文件内容。
     */
    private static String appendDocumentInfo(String userText, List<ReceivedContent> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return userText;
        }
        List<ReceivedContent> docs = attachments.stream()
                .filter(a -> !a.isImage())  // 排除图片（图片已通过 Media 传递）
                .toList();
        if (docs.isEmpty()) {
            return userText;
        }

        StringBuilder sb = new StringBuilder(userText);
        sb.append("\n[用户上传的文件：");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) sb.append("，");
            ReceivedContent doc = docs.get(i);
            String typeLabel = doc.isVideo() ? "视频" : "文件";
            sb.append(typeLabel).append("-").append(doc.fileName());
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 暂停态下用户消息的意图分类。
     * <ul>
     *   <li>RESUME：用户想恢复执行（"继续""接着做"等）</li>
     *   <li>CANCEL：用户想放弃整个任务（"算了""取消"等）</li>
     *   <li>MODIFY：用户对原任务做修改/补充/追加要求（"把配文改成test""画成油画风"等）</li>
     *   <li>CHITCHAT：与原任务无关的闲聊或新问题（应另起新任务）</li>
     * </ul>
     */
    private enum PauseIntent { RESUME, CANCEL, MODIFY, CHITCHAT }

    /**
     * 暂停态意图识别：交由 LLM 判定用户新消息相对于原任务的意图，
     * 避免死板的关键词正则（正则会把"今天天气怎么样"误判为修改、把"算了"漏判为取消）。
     *
     * @param originalUserText 原任务指令（暂停前用户下达的多步骤指令）
     * @param pendingMsg       暂停期间用户新发的消息
     * @return 意图枚举，失败时兜底为 CHITCHAT（保守，最多让用户重发"继续"）
     */
    private PauseIntent classifyPauseMessage(String originalUserText, String pendingMsg) {
        String prompt = """
                你是一个「任务暂停态」下的意图识别器。
                用户之前下达了一个多步骤任务并暂停，现在发来一条新消息。
                原任务指令：%s
                用户当前消息：%s

                请判断用户当前消息的意图，严格只输出一个英文单词（不要任何解释）：
                - MODIFY：用户在对原任务做修改、补充、追加要求（例如"把配文改成test""再加一步发邮件""换成油画风格"）
                - CANCEL：用户要放弃/取消整个任务（例如"算了""不要了""取消任务"）
                - RESUME：用户其实就是想恢复/继续任务（例如"继续""接着做吧""go"）
                - CHITCHAT：与原任务无关的闲聊或全新的问题（例如"今天天气怎么样""讲个笑话"）
                """.formatted(originalUserText == null ? "" : originalUserText,
                               pendingMsg == null ? "" : pendingMsg);

        try {
            String raw = routerChatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(ROUTER_MODEL))
                    .user(prompt)
                    .call().chatResponse().getResult().getOutput().getText();
            String trimmed = raw.trim().toUpperCase();
            // 宽松匹配：即使 LLM 多输出内容，只要含关键字就识别
            if (trimmed.contains("MODIFY"))  return PauseIntent.MODIFY;
            if (trimmed.contains("CANCEL"))  return PauseIntent.CANCEL;
            if (trimmed.contains("RESUME"))  return PauseIntent.RESUME;
            return PauseIntent.CHITCHAT;
        } catch (Exception e) {
            log.warn("暂停态意图识别 LLM 调用失败，兜底为 CHITCHAT userId", e);
            return PauseIntent.CHITCHAT;
        }
    }

    /**
     * 从编排计划中提取本轮任务的"领域标签"，供 Router 在下一轮中判断话题是否切换。
     * <p>
     * 格式示例："TEXT（天气查询）"、"IMAGE（水墨画生成）"、"BILIBILI（视频上传）"
     */
    private static String extractDomainLabel(OrchestrationPlan plan) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return "未知";
        }
        PlanStep first = plan.steps().get(0);
        String agent = first.agent() != null ? first.agent() : "UNKNOWN";
        String task = first.task() != null ? first.task() : "";
        // 截取 task 用于标签，最多 15 个字符
        String shortTask = task.length() > 15 ? task.substring(0, 15) + "…" : task;
        return agent + "（" + shortTask + "）";
    }

    /** 从 domainLabel（如 "TEXT（查询天气）"）中提取纯 Agent 类型，供上下文注入结构化展示 */
    private static String extractAgentFromLabel(String domainLabel) {
        if (domainLabel == null) return "未知";
        int idx = domainLabel.indexOf('（');
        return idx > 0 ? domainLabel.substring(0, idx) : domainLabel;
    }

    /**
     * 从 Usage 中提取 DashScope 上下文缓存命中的 token 数。
     * <p>
     * 使用反射访问 {@code PromptTokensDetails}，避免硬编译依赖（Spring AI 2.0
     * 中该类可能不在同一包路径下）。若平台不支持，静默返回 0。
     */
    private static long extractCachedPromptTokens(Usage usage) {
        try {
            java.lang.reflect.Method m = usage.getClass().getMethod("getPromptTokensDetails");
            Object details = m.invoke(usage);
            if (details != null) {
                java.lang.reflect.Method getCached = details.getClass().getMethod("getCachedTokens");
                Long cached = (Long) getCached.invoke(details);
                return cached != null ? cached : 0;
            }
        } catch (Exception ignored) {
            // getPromptTokensDetails 不可用（旧版模型或 DashScope 不返回此字段）
        }
        return 0;
    }
}
