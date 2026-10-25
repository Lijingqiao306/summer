package org.example.ykd_summer.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.billing.TokenUsageTracker;
import org.example.ykd_summer.config.advisor.PersonalRagAdvisor;
import org.example.ykd_summer.dto.MatchReplyResult;
import org.example.ykd_summer.dto.PausedTaskState;
import org.example.ykd_summer.utils.LogUtils;
import org.example.ykd_summer.utils.TaskControlContext;
import org.example.ykd_summer.utils.PromptUtils;
import org.example.ykd_summer.dto.OrchestrationPlan;
import org.example.ykd_summer.dto.PlanStep;
import org.example.ykd_summer.dto.ReceivedContent;
import org.example.ykd_summer.service.OrchestrationService;
import org.example.ykd_summer.tools.FileTools;
import org.example.ykd_summer.tools.ImageTools;
import org.example.ykd_summer.tools.PersonalTools;
import org.example.ykd_summer.utils.AttachmentContext;
import org.example.ykd_summer.utils.BilibiliToolContext;
import org.example.ykd_summer.web.ConsoleEvent;
import org.example.ykd_summer.web.ConsoleEventBus;
import org.example.ykd_summer.web.TaskTracker;
import org.example.ykd_summer.web.ToolStateManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.aop.support.AopUtils;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 编排服务实现。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>校验计划是否为空（空 → 返回"暂不支持"）</li>
 *   <li>拓扑排序（Kahn 算法，处理 depends_on 依赖关系）</li>
 *   <li>逐步执行：将前置步骤输出注入当前步骤的上下文</li>
 *   <li>结果聚合：拼接所有文本，提取最后一步生成的图片/文档</li>
 * </ol>
 * <p>
 * 线程安全：依赖 Actor 模式（同用户消息在同一虚拟线程上串行执行），
 * ImageTools / FileTools 的 ThreadLocal 机制自然安全。
 */
@Slf4j
@Service
public class OrchestrationServiceImpl implements OrchestrationService {

    private final ChatClient aiChatClient;
    private final ChatClient browserChatClient;
    private final ChatClient imageChatClient;
    private final ChatClient fileChatClient;
    private final ChatClient springMonitorAgent;
    private final ChatClient bilibiliChatClient;
    private final VectorStore personalVectorStore;
    private final AtomicLong imageRunCounter = new AtomicLong(0);

    /** 工具类启用/禁用状态管理器 */
    private final ToolStateManager toolStateManager;
    /** 各 Agent 的全量工具列表，供运行时按启用/禁用状态过滤 */
    private final List<Object> aiChatTools;
    private final List<Object> imageChatTools;
    private final ImageTools imageTools;
    private final List<Object> fileChatTools;
    private final List<Object> browserChatTools;
    private final List<Object> bilibiliChatTools;
    private final List<Object> springChatTools;

    /** FILE Agent 生成的文档（executeFileStep 消费后暂存，aggregateResults 取出） */
    private static final ThreadLocal<List<ReceivedContent>> PENDING_GENERATED_FILES = new ThreadLocal<>();

    /** TEXT Agent 系统提示词（完整版），FULL 模式回退时使用 */
    private static final String TEXT_SYSTEM_PROMPT = PromptUtils.load("prompts/ai-chat-system.txt");

    /** TEXT Agent 基础提示词（身份、核心规则、决策框架、天气/版本/状态），始终注入 */
    private static final String TEXT_SYSTEM_PROMPT_BASE = PromptUtils.load("prompts/ai-chat-system-base.txt");

    /** 工具领域 → 专用提示词模块映射，运行时按决议类别动态组装 */
    private static final Map<ToolCategory, String> TOOL_MODULE_PROMPTS;
    static {
        Map<ToolCategory, String> m = new HashMap<>();
        m.put(ToolCategory.SEARCH,   PromptUtils.load("prompts/ai-chat-tool-websearch.txt"));
        m.put(ToolCategory.MAP,      PromptUtils.load("prompts/ai-chat-tool-amap.txt"));
        m.put(ToolCategory.LOCATION, PromptUtils.load("prompts/ai-chat-tool-location.txt"));
        m.put(ToolCategory.PERSONAL, PromptUtils.load("prompts/ai-chat-tool-personal.txt"));
        m.put(ToolCategory.MAIL,     PromptUtils.load("prompts/ai-chat-tool-mail.txt"));
        TOOL_MODULE_PROMPTS = Collections.unmodifiableMap(m);
    }

    // ==================== ChatMemory 动态窗口 ====================
    /** 新话题时的最小记忆窗口（仅保留最近 1 轮问答作为环境上下文） */
    private static final int MIN_MEMORY_WINDOW = 2;
    /** TEXT Agent 追加/修改时的全量窗口 */
    private static final int MAX_TEXT_MEMORY_WINDOW = 8;

    /**
     * 追问模式检测：用户消息是否明确是对上一轮对话的追加/修改/追问。
     * <p>
     * 作为 Router newTopic 判定的兜底规则，解决 Router 无 ChatMemory 上下文
     * 时无法识别"那杭州呢""改为油画"等明确追问短语的问题。
     */
    static boolean isFollowUpPattern(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim().replaceAll("\\s+", " ");

        // 1. 指示代词开头："那"/"这"指代上文内容 → 追问
        //    例："那杭州呢"、"那明天呢"、"这个呢"
        if (t.startsWith("那") || t.startsWith("这") || t.startsWith("那个") || t.startsWith("这个")) {
            return true;
        }

        // 2. 以"呢"结尾的短消息（≤15字）→ 追问
        //    例："湿度呢"、"价格呢"、"那呢"
        if (t.endsWith("呢") && t.length() <= 15) {
            return true;
        }

        // 3. 修改/替换/否定类短语 → 追加
        //    例："换成油画"、"改为竖版"、"还是用简笔画吧"、"不要红色"
        if (t.matches("^(换成|改为|改成|换|改成|改|变|转|切换|还是|要不|不要|别|取消).+")) {
            return true;
        }

        // 4. 递进/叠加类短语 → 追加
        //    例："也帮我查一下"、"再画一幅"、"还想要"
        if (t.matches("^[也再还](来|要|想|试试|搞|弄|整|查|搜|找|画|发|给|帮|做)|^(再|也|还)[一来].+")) {
            return true;
        }

        return false;
    }
    /** BILIBILI Agent 追加/修改时的全量窗口 */
    private static final int MAX_BILIBILI_MEMORY_WINDOW = 5;
    /** SPRING Agent 追加/修改时的全量窗口 */
    private static final int MAX_SPRING_MEMORY_WINDOW = 8;

    // ==================== 工具按需过滤 ====================

    /** 过滤激进程度 */
    private enum FilterLevel { MINIMAL, STANDARD, FULL }

    /** 工具过滤结果：过滤后的 Bean 数组 + 决议后的类别集合 */
    private record ToolFilterResult(Object[] tools, Set<ToolCategory> categories) {}

    /** 多任务连接词——出现即视为复合意图，不冒险优化，直接全量 */
    private static final Pattern MULTI_TASK_PATTERN =
            Pattern.compile("并且|同时|然后|顺便|另外|还有|以及|此外|再加上|跟着");

    // -- 各类别的关键词触发词表 --

    /** MAP 触发词：地理搜索、周边推荐、导航 */
    private static final Set<String> MAP_KEYWORDS = Set.of(
            "附近", "门店", "餐厅", "怎么去", "规划", "周边", "商圈",
            "距离", "在哪里", "找到", "最近的", "火锅", "电影院",
            "健身房", "医院", "酒店", "商场", "地图", "导航", "路线", "走路",
            "地铁", "公交", "开车", "地址", "在哪", "哪里", "好吃", "好玩",
            "有什么", "找一下", "找找", "店铺", "推荐"
    );

    /** SEARCH 触发词：时效信息、新闻、活动（仅保留领域词，时间词"今天/这周/本周"和泛化动词"查一查"会误命中天气等 SYSTEM 类查询，已移除） */
    private static final Set<String> SEARCH_KEYWORDS = Set.of(
            "最新", "新闻", "活动", "电影", "展览", "演出", "漫展",
            "演唱会", "新开", "攻略",
            "动态", "资讯", "消息", "发生", "政策", "景点", "测评"
    );

    /** SYSTEM 触发词：天气、版本、状态 */
    private static final Set<String> SYSTEM_KEYWORDS = Set.of(
            "天气", "气温", "下雨", "温度", "阴晴", "刮风", "雾霾",
            "版本", "version", "状态", "status", "是否在线"
    );

    /** LOCATION 触发词：用户告知位置 */
    private static final Set<String> LOCATION_KEYWORDS = Set.of(
            "我在", "我是", "我的位置", "定位", "城市是", "住在", "搬到",
            "过来旅游", "来旅游", "出差到", "来到"
    );

    /** PERSONAL 触发词：个人信息/偏好管理 */
    private static final Set<String> PERSONAL_KEYWORDS = Set.of(
            "保存", "记住", "删除", "个人信息", "偏好", "档案", "记录",
            "喜欢吃", "不喜欢", "讨厌", "过敏", "忌口", "我叫", "我的"
    );

    /** MAIL 触发词：发送邮件 */
    private static final Set<String> MAIL_KEYWORDS = Set.of(
            "邮件", "发送到", "收件人", "@qq.com", "发邮件", "发信"
    );

    /** Bean 简单类名 → ToolCategory */
    private static final Map<String, ToolCategory> CLASS_CATEGORY_MAP = Map.of(
            "BotTools",        ToolCategory.SYSTEM,
            "AmapTools",       ToolCategory.MAP,
            "LocationTools",   ToolCategory.LOCATION,
            "PersonalTools",   ToolCategory.PERSONAL,
            "MailSendTools",   ToolCategory.MAIL,
            "WebSearchTools",  ToolCategory.SEARCH
    );

    private final ConsoleEventBus eventBus;
    private final TaskTracker taskTracker;
    private final TokenUsageTracker tokenUsageTracker;

    public OrchestrationServiceImpl(
            @Qualifier("aiChatClient") ChatClient aiChatClient,
            @Qualifier("browserChatClient") ChatClient browserChatClient,
            @Qualifier("imageChatClient") ChatClient imageChatClient,
            @Qualifier("fileChatClient") ChatClient fileChatClient,
            @Qualifier("springMonitorAgent") ChatClient springMonitorAgent,
            @Qualifier("personalVectorStore") VectorStore personalVectorStore,
            @Qualifier("bilibiliChatClient") ChatClient bilibiliChatClient,
            ConsoleEventBus eventBus,
            TaskTracker taskTracker,
            TokenUsageTracker tokenUsageTracker,
            ToolStateManager toolStateManager,
            @Qualifier("aiChatTools") List<Object> aiChatTools,
            @Qualifier("imageChatTools") List<Object> imageChatTools,
            @Qualifier("fileChatTools") List<Object> fileChatTools,
            @Qualifier("browserChatTools") List<Object> browserChatTools,
            @Qualifier("bilibiliChatTools") List<Object> bilibiliChatTools,
            @Qualifier("springChatTools") List<Object> springChatTools,
            ImageTools imageTools) {
        this.aiChatClient = aiChatClient;
        this.browserChatClient = browserChatClient;
        this.imageChatClient = imageChatClient;
        this.fileChatClient = fileChatClient;
        this.springMonitorAgent = springMonitorAgent;
        this.personalVectorStore = personalVectorStore;
        this.bilibiliChatClient = bilibiliChatClient;
        this.eventBus = eventBus;
        this.taskTracker = taskTracker;
        this.tokenUsageTracker = tokenUsageTracker;
        this.toolStateManager = toolStateManager;
        this.aiChatTools = aiChatTools;
        this.imageChatTools = imageChatTools;
        this.fileChatTools = fileChatTools;
        this.browserChatTools = browserChatTools;
        this.bilibiliChatTools = bilibiliChatTools;
        this.springChatTools = springChatTools;
        this.imageTools = imageTools;
    }

    // ==================== 主流程 ====================

    @Override
    public MatchReplyResult execute(OrchestrationPlan plan, String userId, String sessionId,
                                     String userText, List<ReceivedContent> attachments,
                                     Consumer<String> onProgress) {
        if (plan.isEmpty()) {
            log.info("编排计划为空，返回不支持回复");
            return new MatchReplyResult("抱歉，暂不支持此功能，请尝试发送文本或图片~", null, false);
        }

        // 读取 Router 判定的话题切换标志，子 Agent 据此动态调整 ChatMemory 窗口
        boolean newTopic = plan.newTopic();
        // 追问模式规则兜底："那杭州呢"、"改为油画" 等明确追问即使 Router 误判也覆盖为 false
        if (newTopic && isFollowUpPattern(userText)) {
            log.info("编排话题判定: Router判定newTopic=true, 但用户消息匹配追问模式 → 覆盖为 newTopic=false, text=\"{}\"", userText);
            newTopic = false;
        }
        if (!newTopic) {
            log.info("编排话题判定: newTopic=false, userId={}", userId);
        } else {
            log.info("编排话题判定: newTopic=true, userId={}", userId);
        }

        // 全新话题：清空 Bot 生成图片缓存（旧图片与当前话题无关）
        if (newTopic) {
            ImageTools.clearLastGenerated(userId);
        }

        // 特殊步骤：WAIT_IMAGE —— 用户要求带图发布但图片尚未到达，由 Runner 进入等待窗口。
        // 只要计划里出现 WAIT_IMAGE，直接返回等待信号，不执行任何步骤，避免 Router 同时输出
        // WAIT_IMAGE + BILIBILI 时被错误地当作普通 agent 调用。
        List<PlanStep> planSteps = plan.steps();
        boolean hasWaitImage = planSteps.stream()
                .anyMatch(s -> "WAIT_IMAGE".equalsIgnoreCase(s.agent()));
        if (hasWaitImage) {
            log.info("编排计划要求等待图片 userId={}", userId);
            return new MatchReplyResult("请发送你要配的图片，我会等你 10 秒~", null, false, null, true);
        }

        List<PlanStep> sorted = topologicalSort(planSteps);

        // ===== 方案 A：单步 IMAGE + 跨轮指代实体 → 自动补一步 TEXT 实体解析 =====
        // 场景：「推荐了《八仙》」→「给这部电影做海报」。Router 常把此类规划成
        // 单步 IMAGE + depends_on=[]，而 IMAGE Agent 不读 ChatMemory，导致拿不到实体、
        // 自行臆造片名。此处检测并自动插入一个轻量 TEXT 步骤（从对话历史解析具体实体），
        // 让 IMAGE 步骤依赖它，从而把真实实体（《八仙》）注入 IMAGE 输入。通用，不限于电影。
        sorted = maybeInsertEntityResolveStep(sorted, userText);

        log.info("编排执行开始: 共 {} 步，拓扑排序: {}",
                sorted.size(), sorted.stream().map(s -> "step" + s.index()).toList());

        // 多步骤时，每步执行前发送进度提示
        boolean shouldReport = onProgress != null && sorted.size() >= 2;

        // 每轮编排生成唯一 ID，防止 ChatMemory 跨轮污染（如上一轮的水彩画对话污染本轮识图）
        String imageRunId = userId + "_img_" + imageRunCounter.incrementAndGet();

        Map<Integer, String> stepOutputs = new LinkedHashMap<>();

        for (int i = 0; i < sorted.size(); i++) {
            PlanStep step = sorted.get(i);
            log.info("执行步骤 {}: agent={}, task={}, depends_on={}",
                    step.index(), step.agent(), step.task(), step.dependsOn());

            // ---------- 中断检查（取消） ----------
            if (TaskControlContext.isCancelled(userId)) {
                log.info("任务被用户取消: userId={}, 已完成 {}/{} 步",
                        userId, stepOutputs.size(), sorted.size());
                ImageTools.discardGeneratedImage();
                PENDING_GENERATED_FILES.remove();
                BilibiliToolContext.clear(userId);
                AttachmentContext.clear(userId);
                ImageTools.clearAllImageAttachments();
                ImageTools.clearLastGenerated(userId);
                TaskControlContext.clear(userId);
                eventBus.publish(ConsoleEvent.step("Orchestration",
                        "任务已取消 (已完成 " + stepOutputs.size() + " 步)", userId));
                var trackedTask = taskTracker.findByUser(userId);
                if (trackedTask != null) {
                    taskTracker.fail(trackedTask.taskId, "用户取消");
                }
                return new MatchReplyResult("任务已取消", null, false);
            }

            // ---------- 中断检查（暂停） ----------
            if (TaskControlContext.isPaused(userId)) {
                // 暂停时无运行任务且无已完成的步骤 → 忽略标志，正常执行
                if (stepOutputs.isEmpty()) {
                    TaskControlContext.clear(userId);
                } else {
                    List<ReceivedContent> pendingFiles = PENDING_GENERATED_FILES.get();
                    PausedTaskState pausedState = new PausedTaskState(
                            sorted,
                            new LinkedHashMap<>(stepOutputs),
                            i,
                            userId,
                            sessionId,
                            userText,
                            attachments,
                            imageRunId,
                            pendingFiles,
                            null
                    );
                    TaskControlContext.savePausedTask(userId, pausedState);
                    log.info("任务已暂停 userId={}, 已完成 {}/{} 步，下次从步骤 {} 恢复",
                            userId, stepOutputs.size(), sorted.size(), i);
                    eventBus.publish(ConsoleEvent.step("Orchestration",
                            "任务已暂停 (已完成 " + stepOutputs.size() + " 步)", userId));
                    return new MatchReplyResult(
                            String.format("已暂停 (已完成 %d/%d 步)，回复「继续」继续执行~",
                                    stepOutputs.size(), sorted.size()),
                            null, false);
                }
            }

            if (shouldReport) {
                onProgress.accept(stepProgressMessage(step));
            }

            // 发布步骤事件
            String stepName = step.agent() + " step" + step.index();
            eventBus.publish(ConsoleEvent.step("Orchestration",
                    "开始: " + stepDescription(step), userId));
            var trackedTask = taskTracker.findByUser(userId);
            if (trackedTask != null) {
                taskTracker.updateStep(trackedTask.taskId, stepName);
            }

            String contextualInput = buildContextualInput(step, stepOutputs, userId, sessionId, newTopic);
            String result = executeStep(step, userId, sessionId, userText, contextualInput, attachments, imageRunId, newTopic);
            stepOutputs.put(step.index(), result);

            // Bot 生成图片跨轮缓存：IMAGE 步骤完成后缓存生成图片，供后续修改轮次解析 ref:0
            if ("IMAGE".equalsIgnoreCase(step.agent())) {
                ReceivedContent generated = ImageTools.peekGeneratedImage();
                if (generated != null) {
                    ImageTools.cacheLastGenerated(userId, generated);
                }
            }

            // 步骤完成
            eventBus.publish(ConsoleEvent.step("Orchestration",
                    "完成: " + stepDescription(step), userId));
            if (trackedTask != null) {
                taskTracker.completeStep(trackedTask.taskId, stepName);
            }

            log.info("步骤 {} 完成: output={}", step.index(),
                    result != null ? result.substring(0, Math.min(80, result.length())) : "null");
        }

        return aggregateResults(sorted, stepOutputs);
    }

    // ==================== 任务恢复 ====================

    /**
     * 恢复被暂停的编排任务。
     * <p>
     * 从上次暂停的步骤继续，已完成步骤的输出直接复用，
     * 执行中仍支持再次暂停/取消。
     */
    @Override
    public MatchReplyResult resume(PausedTaskState state, Consumer<String> onProgress) {
        String userId = state.userId();
        String sessionId = state.sessionId();
        String userText = state.userText();
        List<ReceivedContent> attachments = state.attachments();
        List<PlanStep> sorted = state.steps();
        int startIndex = state.nextStepIndex();
        Map<Integer, String> stepOutputs = new LinkedHashMap<>(state.stepOutputs());

        // 恢复 FILE Agent 跨线程生成的文档（原本在 ThreadLocal 中，跨线程丢失）
        List<ReceivedContent> pendingFiles = state.pendingGeneratedFiles();
        if (pendingFiles != null) {
            PENDING_GENERATED_FILES.set(pendingFiles);
        }

        log.info("编排恢复执行: 共 {} 步，已完成 {} 步，从步骤 {} 继续 userId={}",
                sorted.size(), stepOutputs.size(), startIndex, userId);
        eventBus.publish(ConsoleEvent.step("Orchestration",
                String.format("恢复执行: 已完成 %d/%d 步", stepOutputs.size(), sorted.size()), userId));

        boolean shouldReport = onProgress != null && sorted.size() >= 2;
        String imageRunId = state.imageRunId();
        // 暂停期间用户累积的修改/补充指令（由 LLM 意图识别判定为 MODIFY），恢复时注入未完成步骤
        String pendingModify = state.pendingModifyInstruction();

        for (int i = startIndex; i < sorted.size(); i++) {
            PlanStep step = sorted.get(i);
            log.info("执行步骤 {}: agent={}, task={}, depends_on={}",
                    step.index(), step.agent(), step.task(), step.dependsOn());

            // ---------- 再度取消检查 ----------
            if (TaskControlContext.isCancelled(userId)) {
                log.info("恢复任务被取消: userId={}, 已完成 {}/{} 步",
                        userId, stepOutputs.size(), sorted.size());
                ImageTools.discardGeneratedImage();
                PENDING_GENERATED_FILES.remove();
                BilibiliToolContext.clear(userId);
                AttachmentContext.clear(userId);
                ImageTools.clearAllImageAttachments();
                ImageTools.clearLastGenerated(userId);
                TaskControlContext.clear(userId);
                eventBus.publish(ConsoleEvent.step("Orchestration",
                        "恢复任务已取消 (已完成 " + stepOutputs.size() + " 步)", userId));
                return new MatchReplyResult("任务已取消", null, false);
            }

            // ---------- 再度暂停检查 ----------
            if (TaskControlContext.isPaused(userId)) {
                List<ReceivedContent> currentPendingFiles = PENDING_GENERATED_FILES.get();
                PausedTaskState updatedState = new PausedTaskState(
                        sorted,
                        new LinkedHashMap<>(stepOutputs),
                        i,
                        userId,
                        sessionId,
                        userText,
                        attachments,
                        imageRunId,
                        currentPendingFiles,
                        state.pendingModifyInstruction()
                );
                TaskControlContext.savePausedTask(userId, updatedState);
                log.info("恢复任务再次暂停 userId={}, 已完成 {}/{} 步",
                        userId, stepOutputs.size(), sorted.size());
                return new MatchReplyResult(
                        String.format("已暂停 (已完成 %d/%d 步)，回复「继续」继续执行~",
                                stepOutputs.size(), sorted.size()),
                        null, false);
            }

            if (shouldReport) {
                onProgress.accept(stepProgressMessage(step));
            }

            String stepName = step.agent() + " step" + step.index();
            eventBus.publish(ConsoleEvent.step("Orchestration",
                    "开始: " + stepDescription(step), userId));
            var trackedTask = taskTracker.findByUser(userId);
            if (trackedTask != null) {
                taskTracker.updateStep(trackedTask.taskId, stepName);
            }

            String contextualInput = buildContextualInput(step, stepOutputs, userId, sessionId, false, pendingModify);
            String result = executeStep(step, userId, sessionId, userText, contextualInput, attachments, imageRunId, false);
            stepOutputs.put(step.index(), result);

            // Bot 生成图片跨轮缓存：IMAGE 步骤完成后缓存生成图片
            if ("IMAGE".equalsIgnoreCase(step.agent())) {
                ReceivedContent generated = ImageTools.peekGeneratedImage();
                if (generated != null) {
                    ImageTools.cacheLastGenerated(userId, generated);
                }
            }

            eventBus.publish(ConsoleEvent.step("Orchestration",
                    "完成: " + stepDescription(step), userId));
            if (trackedTask != null) {
                taskTracker.completeStep(trackedTask.taskId, stepName);
            }

            log.info("步骤 {} 完成: output={}", step.index(),
                    result != null ? result.substring(0, Math.min(80, result.length())) : "null");
        }

        return aggregateResults(sorted, stepOutputs);
    }

    @Override
    public void clearPausedResources(String userId) {
        ImageTools.discardGeneratedImage();
        PENDING_GENERATED_FILES.remove();
        BilibiliToolContext.clear(userId);
        AttachmentContext.clear(userId);
        ImageTools.clearAllImageAttachments();
        ImageTools.clearLastGenerated(userId);
        log.info("Orchestration: 已清理暂停任务残留资源 userId={}", userId);
    }

    // ==================== 拓扑排序（Kahn 算法） ====================

    /**
     * 按 depends_on 关系进行拓扑排序。
     * <p>
     * 排序后的列表中，无依赖步骤在前，依赖步骤在后。
     * 若存在循环依赖，降级为原始顺序执行。
     */
    private List<PlanStep> topologicalSort(List<PlanStep> steps) {
        Map<Integer, PlanStep> stepMap = new LinkedHashMap<>();
        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();

        for (PlanStep step : steps) {
            stepMap.put(step.index(), step);
            adjacency.put(step.index(), new ArrayList<>());
            inDegree.put(step.index(), 0);
        }

        // 构建邻接表与入度
        for (PlanStep step : steps) {
            if (step.dependsOn() != null) {
                for (int dep : step.dependsOn()) {
                    if (adjacency.containsKey(dep)) {
                        adjacency.get(dep).add(step.index());
                        inDegree.merge(step.index(), 1, Integer::sum);
                    } else {
                        log.warn("步骤 {} 声明依赖了不存在的步骤 {}，已忽略", step.index(), dep);
                    }
                }
            }
        }

        // Kahn BFS
        Queue<Integer> queue = new LinkedList<>();
        for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<PlanStep> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            sorted.add(stepMap.get(idx));
            for (int next : adjacency.get(idx)) {
                int newDegree = inDegree.merge(next, -1, Integer::sum);
                if (newDegree == 0) {
                    queue.offer(next);
                }
            }
        }

        // 循环依赖检测
        if (sorted.size() < steps.size()) {
            log.warn("检测到步骤间存在循环依赖，已降级为原始顺序执行");
            return new ArrayList<>(steps);
        }

        return sorted;
    }

    /**
     * 方案 A：当编排为「单步 IMAGE 且 depends_on 为空」且用户消息/IMAGE 任务指代了上一轮实体
     * （如"给刚才推荐的电影做海报""这部电影的封面"），自动插入一个轻量 TEXT 步骤，
     * 从对话历史中解析出具体实体名（电影/作品/对象），并让原 IMAGE 步骤依赖它。
     * <p>
     * 这样 IMAGE Agent（不读 ChatMemory、单步无 depends_on 时无数据）就能拿到真实实体，
     * 避免臆造片名。通用覆盖所有"先有实体、后作图"的跨轮指代场景。
     */
    private List<PlanStep> maybeInsertEntityResolveStep(List<PlanStep> sorted, String userText) {
        if (sorted.size() != 1) {
            return sorted; // 仅针对单步场景（多步已自带依赖传递）
        }
        PlanStep only = sorted.get(0);
        if (!"IMAGE".equalsIgnoreCase(only.agent())) {
            return sorted;
        }
        // depends_on 非空说明已有数据来源，无需插入
        if (only.dependsOn() != null && !only.dependsOn().isEmpty()) {
            return sorted;
        }
        // 检测是否含跨轮指代代词 + 作图意图
        if (!isCrossTurnEntityReference(userText, only.task())) {
            return sorted;
        }

        String entityTask = "从我们刚才的对话历史中，找出用户当前所指的具体实体（如电影名、作品名、对象名称），"
                + "仅返回该实体名称本身，不要解释、不要扩展。"
                + "若无法确定，返回“无法确定实体”。";
        PlanStep resolveStep = new PlanStep(0, "TEXT", entityTask, "实体解析（从历史提取具体名称）", Collections.emptyList());
        // 原 IMAGE 步骤改为 index=1，依赖步骤0，并把已识别的上下文传入 task
        String imageTask = only.task() + "（若上文实体解析步骤给出了具体名称，请以其为准生成对应内容）";
        PlanStep imageStep = new PlanStep(1, "IMAGE", imageTask, only.inputHint(), List.of(0));

        log.info("方案A: 检测到指代上文实体，已自动插入 TEXT 实体解析步 (step0) → IMAGE (step1, depends_on=[0])");
        return List.of(resolveStep, imageStep);
    }

    /** 判断用户消息/任务是否指代了上一轮的某个实体（需要跨轮解析） */
    private static boolean isCrossTurnEntityReference(String userText, String imageTask) {
        String combined = ((userText == null ? "" : userText) + " " + (imageTask == null ? "" : imageTask));
        // 代词：这个/这部/这张/那条/那张/刚才/前面/上面/上文/前文/之前/之前推荐/上次
        boolean hasPronoun = combined.matches(".*(这个|这部|这张|那条|那张|那部|刚才|前面|上面|上文|前文|之前|上次|前述|上述).*");
        // 作画/生成意图
        boolean hasGenIntent = combined.matches(".*(海报|画图|画一|画幅|图片|封面|头像|简笔画|生成|绘制|做一|制作).*");
        // 明确引用"上一轮/上一轮推荐/推荐的"
        boolean refersPrev = combined.matches(".*(上一轮|上一轮推荐|上一轮推荐的电影|推荐的电影|推荐的那部|推荐的那个).*");
        return hasGenIntent && (hasPronoun || refersPrev);
    }

    // ==================== 上下文构建 ====================

    /**
     * 构建当前步骤的上下文输入。
     * <p>
     * 格式上严格采用「任务指令 + 可选参考数据」结构，避免任何对话延续式标签
     * （如"前置任务结果""以下信息仅供参考"），防止 LLM 将输入误解为多轮对话
     * 而跳过工具调用直接文本续写。
     */
    private String buildContextualInput(PlanStep step, Map<Integer, String> stepOutputs, String userId, String sessionId, boolean newTopic) {
        StringBuilder input = new StringBuilder();

        // 任务指令（必须放在最前面，让 LLM 第一时间知道这是独立任务）
        input.append(step.task());

        // 参考数据（依赖步骤的真实输出，紧接任务后）。
        // 对于"根据XX画"这类两步编排：步骤0=TEXT 查到的真实数据（天气/电影等）会作为
        // 步骤1=IMAGE 的 depends_on 输出注入到这里，IMAGE 据此构图即可。
        // 这是让 IMAGE "看到"真实数据的唯一稳健来源（不依赖记忆读取的脆弱性），且通用
        // 于任何"先查数据再作图"的场景，而非仅针对天气。
        if (step.dependsOn() != null && !step.dependsOn().isEmpty() && !stepOutputs.isEmpty()) {
            for (int dep : step.dependsOn()) {
                String depOutput = stepOutputs.get(dep);
                if (depOutput != null) {
                    input.append("\n\n【参考数据（来自前置步骤的真实输出，仅供构图参考，不是作画指令）】\n")
                         .append(depOutput);
                    if ("IMAGE".equalsIgnoreCase(step.agent())) {
                        input.append("\n★ 请根据上面的参考数据，立即调用 generateImage 工具生成图片，")
                             .append("不要复述、总结或描述上面的文本，也不要输出任何文字作画指导。");
                    }
                }
            }
        }

        if (step.inputHint() != null && !step.inputHint().isBlank()) {
            input.append("\n提示：").append(step.inputHint());
        }

        return input.toString();
    }

    /**
     * {@link #buildContextualInput} 的重载：额外注入暂停期间用户累积的修改指令（MODIFY）。
     * <p>
     * 仅用于 resume() 恢复执行——把用户中途修改/补充的要求追加到未完成步骤的上下文末尾，
     * 让对应 Agent（如 BILIBILI 配文步骤）按新指令产出，而非原指令。
     */
    private String buildContextualInput(PlanStep step, Map<Integer, String> stepOutputs, String userId,
                                        String sessionId, boolean newTopic, String modifyInstruction) {
        String base = buildContextualInput(step, stepOutputs, userId, sessionId, newTopic);
        if (modifyInstruction == null || modifyInstruction.isBlank()) {
            return base;
        }
        return base + "\n\n【重要·用户中途修改要求（请在完成本步骤时严格遵循，覆盖原指令中与之冲突的部分）】\n"
                + modifyInstruction;
    }

    // ==================== 步骤分发执行 ====================

    /** Agent 类型 → 进度提示消息映射 */
    private static final Map<String, String> PROGRESS_MESSAGES = Map.of(
            "TEXT", "等等哦，正在为你查找...",
            "BROWSER", "正在打开网页...",
            "IMAGE", "正在生成图片...",
            "FILE", "正在处理文件...",
            "SPRING", "正在处理 Spring 监测...",
            "BILIBILI", "正在处理B站操作..."
    );

    private static String stepProgressMessage(PlanStep step) {
        return PROGRESS_MESSAGES.getOrDefault(step.agent(), "正在处理...");
    }

    private static String stepDescription(PlanStep step) {
        String agent = step.agent();
        String task = step.task();
        if (task != null && task.length() > 40) {
            task = task.substring(0, 40) + "...";
        }
        return agent + " - " + (task != null ? task : "无描述");
    }

    /**
     * 根据当前 ToolStateManager 的禁用状态，过滤全量工具列表，
     * 返回仅包含启用工具类的对象数组，供 {@code .prompt().tools()} 使用。
     */
    @SuppressWarnings("unchecked")
    private Object[] enabledTools(List<Object> allTools) {
        return allTools.stream()
                .filter(t -> !toolStateManager.isBeanDisabled(t))
                .toArray(Object[]::new);
    }

    /**
     * 根据 agent 字段分发到对应子 Agent。
     * <p>
     * TEXT Agent 已挂载 {@link PersonalRagAdvisor}（个人信息向量库环绕增强），
     * 所有分发到 TEXT 的请求都会自动检索并注入用户的个人信息作为上下文。
     *
     * @param imageRunId 当前编排轮的 IMAGE 对话 ID（每轮唯一，防止 ChatMemory 跨轮污染）
     * @param newTopic   Router 判定是否为全新话题，用于动态调整 ChatMemory 窗口大小
     */
    private String executeStep(PlanStep step, String userId, String sessionId, String userText,
                                String contextualInput, List<ReceivedContent> attachments,
                                String imageRunId, boolean newTopic) {
        if ("IMAGE".equalsIgnoreCase(step.agent())) {
            ImageTools.setCurrentUserId(userId);
            try {
                return executeImageStep(imageRunId, contextualInput, attachments, step.task());
            } finally {
                ImageTools.clearCurrentUserId();
            }
        }
        if ("FILE".equalsIgnoreCase(step.agent())) {
            return executeFileStep(contextualInput, attachments);
        }
        if ("BROWSER".equalsIgnoreCase(step.agent())) {
            return executeBrowserStep(contextualInput);
        }
        if ("SPRING".equalsIgnoreCase(step.agent())) {
            return executeSpringStep(userId, sessionId, contextualInput, newTopic);
        }
        if ("BILIBILI".equalsIgnoreCase(step.agent())) {
            return executeBilibiliStep(userId, sessionId, contextualInput, step.task(), newTopic);
        }
        // 默认 TEXT：
        // TEXT Agent 已挂载 PersonalRagAdvisor + PersonalTools，
        // 涵盖天气、搜索、邮件、个人信息管理、健康咨询等全部文本能力
        return executeTextStep(userId, sessionId, userText, contextualInput, newTopic);
    }

    /**
     * 调用文本子 Agent（aiChatClient，含 BotTools / MailSendTools / PersonalTools，
     * 挂载 PersonalRagAdvisor 实现个人信息向量库环绕增强，并具备 DashScope 原生联网搜索）。
     * <p>
     * 采用会话级隔离 + 任务上下文策略：
     * <ul>
     *   <li>{@code conversationId = userId + "_" + sessionId}，Actor 重建时自动切换新会话，
     *       H2 持久化中的旧对话数据不会被注入，杜绝跨会话语言污染</li>
     *   <li>{@code userText} 通过 {@code .user()} 写入 ChatMemory，保证多轮对话记忆纯净</li>
     *   <li>{@code taskInstruction}（路由器的 task + inputHint）通过 {@code .system()} 传入，
     *       不污染对话记忆，LLM 可据此自主判断是否需要联网搜索或调用工具</li>
     *   <li>通过 {@link PersonalTools#setCurrentUserId} / {@link PersonalTools#clearCurrentUserId}
     *       设置 ThreadLocal，使 PersonalRagAdvisor 和 PersonalTools 方法内能获取当前 userId</li>
     * </ul>
     */
    private String executeTextStep(String userId, String sessionId, String userText,
                                     String taskInstruction, boolean newTopic) {
        // 文本工具全部被禁用时直接返回，避免 LLM 臆造回复
        if (enabledTools(aiChatTools).length == 0) {
            log.info("TEXT Agent 所有工具已被禁用，跳过执行: input={}", userText);
            return "当前文本对话功能已被管理员禁用，暂时无法处理此请求。";
        }
        PersonalTools.setCurrentUserId(userId);
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("今天是 yyyy 年 M 月 d 日"));
            String conversationId = userId + "_" + sessionId;

            // 工具过滤 + 类别决议（同时返回过滤后的 Bean 数组和决议类别集合）
            ToolFilterResult filterResult = filterToolsByTask(
                    enabledTools(aiChatTools), userText, taskInstruction);
            // 根据决议类别动态组装系统提示词（基础 + 匹配的工具领域模块）
            String systemPrompt = composeSystemPrompt(filterResult.categories());

            // 动态窗口：新话题仅保留最小上下文（2 条），追加/修改保留全量（8 条）
            int memorySize = newTopic ? MIN_MEMORY_WINDOW : MAX_TEXT_MEMORY_WINDOW;
            log.info("TEXT ChatMemory 窗口: {} 条 (newTopic={})", memorySize, newTopic);

            var prompt = aiChatClient.prompt()
                    .tools(filterResult.tools())
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, conversationId);
                        a.param("chat_memory_retrieve_size", memorySize);
                        // 类别决议与个人信息无关（纯 SYSTEM/MAIL）时跳过 RAG 注入，
                        // 节省 token 并避免低阈值检索到的无关档案污染回复
                        if (!needsPersonalRag(filterResult.categories())) {
                            a.param(PersonalRagAdvisor.SKIP_RAG_KEY, true);
                        }
                    })
                    .user(userText);
            // 拼接动态提示词 + 日期 + 任务指令
            StringBuilder sysBuilder = new StringBuilder(systemPrompt);
            sysBuilder.append("\n\n").append(today);
            if (taskInstruction != null && !taskInstruction.isBlank()) {
                sysBuilder.append("\n【本次任务】").append(taskInstruction);
            }
            prompt.system(sysBuilder.toString());
            ChatResponse response = prompt.call().chatResponse();
            recordTokenUsage(response, "TEXT");
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("TEXT 步骤执行失败", e);
            return "文本处理失败：" + e.getMessage();
        } finally {
            PersonalTools.clearCurrentUserId();
        }
    }

    // ==================== 工具按需过滤方法 ====================

    /**
     * 根据任务上下文过滤 TEXT Agent 的工具列表，同时返回决议后的类别集合供提示词组装使用。
     * <p>
     * 三级过滤策略：
     * <ol>
     *   <li>关键词匹配：从 taskInstruction + userText 提取类别集合</li>
     *   <li>Level 决策：MINIMAL（纯个人/天气）→ STANDARD（带依赖连带）→ FULL（多任务/无匹配）</li>
     *   <li>类别 → Bean 映射：按类名匹配回实际 Bean 对象</li>
     * </ol>
     * <p>
     * 安全原则：宁可多带 1-2 个无关 Bean，绝不漏带必需的 Bean。
     * 当 taskInstruction 为空（直接对话非编排）时，直接返回全量。
     *
     * @param enabledTools 已通过启用/禁用状态过滤的工具 Bean 数组
     * @param userText     用户原始输入
     * @param taskInstruction Router 产出的任务描述（可能为 null）
     * @return 过滤结果（工具 Bean 数组 + 决议后的类别集合）
     */
    private ToolFilterResult filterToolsByTask(Object[] enabledTools, String userText, String taskInstruction) {
        // 无 taskInstruction（直接对话，非编排流程）→ 全量
        if (taskInstruction == null || taskInstruction.isBlank()) {
            return new ToolFilterResult(enabledTools, EnumSet.allOf(ToolCategory.class));
        }

        String query = taskInstruction + " " + userText;

        // 第一层：关键词匹配 → 类别集合
        Set<ToolCategory> categories = keywordMatch(query);

        // 第二层：决定过滤级别 + 补齐依赖
        FilterLevel level = decideFilterLevel(categories, query);
        Set<ToolCategory> resolved = switch (level) {
            case MINIMAL  -> categories;                              // 只保留匹配类
            case STANDARD -> ToolCategory.withStandardDependencies(categories);  // +连带
            case FULL     -> EnumSet.allOf(ToolCategory.class);       // 全量
        };

        log.info("filterToolsByTask: level={}, categories={} → resolved={}, query={}",
                level, categories, resolved, query.substring(0, Math.min(60, query.length())));

        // 第三层：类别 → Bean 对象
        return new ToolFilterResult(resolveBeansForCategories(enabledTools, resolved), resolved);
    }

    /**
     * 关键词匹配：从查询文本中提取工具类别集合。
     * 基于预定义的词表进行确定性匹配，零延迟、零 token 消耗。
     */
    private Set<ToolCategory> keywordMatch(String query) {
        EnumSet<ToolCategory> result = EnumSet.noneOf(ToolCategory.class);

        for (String kw : MAP_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.MAP); break; }
        }
        for (String kw : SEARCH_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.SEARCH); break; }
        }
        for (String kw : SYSTEM_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.SYSTEM); break; }
        }
        for (String kw : LOCATION_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.LOCATION); break; }
        }
        for (String kw : PERSONAL_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.PERSONAL); break; }
        }
        for (String kw : MAIL_KEYWORDS) {
            if (query.contains(kw)) { result.add(ToolCategory.MAIL); break; }
        }

        return result;
    }

    /**
     * 决定过滤级别。
     * <p>
     * 规则（优先级从高到低）：
     * <ol>
     *   <li>无匹配 → FULL（安全兜底）</li>
     *   <li>包含多任务连接词 → FULL（复合意图，不冒险）</li>
     *   <li>仅 PERSONAL 或 MAIL → MINIMAL（极安全，只需自身）</li>
     *   <li>仅 SYSTEM → MINIMAL（查天气/版本不需要其他工具）</li>
     *   <li>其他（含 MAP/SEARCH/LOCATION）→ STANDARD（带连带依赖）</li>
     * </ol>
     */
    private FilterLevel decideFilterLevel(Set<ToolCategory> categories, String query) {
        if (categories.isEmpty()) {
            return FilterLevel.FULL;
        }
        if (MULTI_TASK_PATTERN.matcher(query).find()) {
            log.debug("filterToolsByTask: 检测到多任务连接词 → FULL");
            return FilterLevel.FULL;
        }
        if (isSubsetOf(categories, ToolCategory.PERSONAL, ToolCategory.MAIL)) {
            return FilterLevel.MINIMAL;
        }
        if (isSubsetOf(categories, ToolCategory.SYSTEM)) {
            return FilterLevel.MINIMAL;
        }
        return FilterLevel.STANDARD;
    }

    /** 判断 categories 是否完全包含在 allowed 范围内 */
    private static boolean isSubsetOf(Set<ToolCategory> categories, ToolCategory... allowed) {
        Set<ToolCategory> allowedSet = EnumSet.noneOf(ToolCategory.class);
        Collections.addAll(allowedSet, allowed);
        return allowedSet.containsAll(categories);
    }

    /**
     * 将类别集合映射回工具 Bean 数组。
     * 通过 {@code AopUtils.getTargetClass} 获取代理后的真实类名，
     * 与 {@link #CLASS_CATEGORY_MAP} 匹配。
     */
    private Object[] resolveBeansForCategories(Object[] enabledTools, Set<ToolCategory> categories) {
        if (categories.containsAll(EnumSet.allOf(ToolCategory.class))) {
            return enabledTools; // 全量，无需逐个匹配
        }
        java.util.List<Object> filtered = new java.util.ArrayList<>();
        for (Object bean : enabledTools) {
            String className = AopUtils.getTargetClass(bean).getSimpleName();
            ToolCategory cat = CLASS_CATEGORY_MAP.get(className);
            if (cat != null && categories.contains(cat)) {
                filtered.add(bean);
            }
        }
        return filtered.toArray();
    }

    /**
     * 根据决议后的工具类别集合动态组装系统提示词。
     * <p>
     * 规则：
     * <ul>
     *   <li>全量类别 → 回退到原始完整提示词（{@link #TEXT_SYSTEM_PROMPT}），保持向后兼容</li>
     *   <li>其他 → 基础提示词 + 匹配的工具领域模块按需拼接</li>
     * </ul>
     * <p>
     * SYSTEM（天气/版本/状态）已内置在基础提示词中，此处不额外拼接。
     *
     * @param categories 决议后的工具类别集合
     * @return 组装后的系统提示词
     */
    private String composeSystemPrompt(Set<ToolCategory> categories) {
        if (categories.containsAll(EnumSet.allOf(ToolCategory.class))) {
            log.info("composeSystemPrompt: FULL 模式, 提示词长度={}字符", TEXT_SYSTEM_PROMPT.length());
            return TEXT_SYSTEM_PROMPT; // FULL 模式：使用原始完整提示词
        }
        StringBuilder sb = new StringBuilder(TEXT_SYSTEM_PROMPT_BASE);
        for (ToolCategory cat : categories) {
            String module = TOOL_MODULE_PROMPTS.get(cat);
            if (module != null) {
                sb.append('\n').append(module);
            }
        }
        log.info("composeSystemPrompt: 模块化模式, 加载模块={}, 提示词长度={}字符 (完整版={}字符)",
                categories, sb.length(), TEXT_SYSTEM_PROMPT.length());
        return sb.toString();
    }

    /**
     * 判断本次请求是否需要个人信息 RAG 注入。
     * <p>
     * 仅当决议类别与个性化相关时才需要：
     * <ul>
     *   <li>PERSONAL：档案问答/偏好管理的本体</li>
     *   <li>MAP：位置推荐需结合个人偏好筛选</li>
     *   <li>SEARCH：活动/资讯推荐需兴趣匹配标注</li>
     *   <li>LOCATION：位置管理与个人档案常关联</li>
     * </ul>
     * 纯 SYSTEM（天气/版本/状态）、MAIL 请求与个人信息无关，跳过注入。
     * FULL（全量类别）包含全部类别，自然命中 → 保守保留注入。
     *
     * @param categories 决议后的工具类别集合
     * @return true=需要注入个人信息 RAG
     */
    private boolean needsPersonalRag(Set<ToolCategory> categories) {
        // SEARCH 类请求（电影/活动/新闻等时效推荐）不注入兴趣档案：
        // 此类推荐应完全基于搜索返回的真实内容，兴趣只隐式影响排序，
        // 明文注入反而会诱使模型为"贴合兴趣"而编造契合点/论证理由。
        return categories.stream().anyMatch(c ->
                c == ToolCategory.PERSONAL || c == ToolCategory.MAP
                || c == ToolCategory.LOCATION);
    }

    /**
     * 调用浏览器子 Agent（browserChatClient，仅含 BrowserTools）。
     * <p>
     * BROWSER Agent 为单轮无状态设计——每个步骤独立执行，不依赖 ChatMemory。
     * 浏览器窗口状态由 BrowserTools 内部的 {@code activePage} 字段管理，
     * 页面在步骤间保持打开，但 Agent 本身无对话记忆。
     * <p>
     * 任务指令（taskInstruction）直接作为 user message 传入，LLM 据此调用
     * openWebPage / getPageContent / clickPageElement / typeText 等工具。
     */
    private String executeBrowserStep(String taskInstruction) {
        // 浏览器工具被禁用时直接返回
        if (enabledTools(browserChatTools).length == 0) {
            log.info("BROWSER Agent 已被禁用，跳过执行: input={}", taskInstruction);
            return "当前浏览器操作功能已被管理员禁用，暂时无法处理此请求。";
        }
        try {
            ChatResponse response = browserChatClient.prompt()
                    .tools(enabledTools(browserChatTools))
                    .user(taskInstruction)
                    .call()
                    .chatResponse();
            recordTokenUsage(response, "BROWSER");
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("BROWSER 步骤执行失败", e);
            return "浏览器操作失败：" + e.getMessage();
        }
    }

    /**
     * 调用 Spring 监测子 Agent（springMonitorAgent，含 SpringMonitorTools
     * + SpringMonitorConfigTools + MailSendTools）。
     * <p>
     * SPRING Agent 具备完整的 Spring 项目监测能力：
     * <ul>
     *   <li>监测范围管理：updateMonitorScope / getMonitorScope / resetMonitorScope</li>
     *   <li>版本查询：checkNewVersions / getLatestReleases / getReleaseDetail / searchReleaseByKeyword</li>
     *   <li>博客查询：getSpringBlogPosts / checkNewBlogPosts / forceRefreshAll</li>
     *   <li>邮件通知：sendMail</li>
     * </ul>
     * <p>
     * 采用会话级隔离 + ChatMemory：{@code conversationId = userId + "_spring_" + sessionId}，
     * 监测配置修改可多轮上下文保持。
     */
    private String executeSpringStep(String userId, String sessionId, String taskInstruction,
                                      boolean newTopic) {
        // Spring 监测工具全部被禁用时直接返回
        if (enabledTools(springChatTools).length == 0) {
            log.info("SPRING Agent 所有工具已被禁用，跳过执行: input={}", taskInstruction);
            return "当前 Spring 监测功能已被管理员禁用，暂时无法处理此请求。";
        }
        try {
            String conversationId = userId + "_spring_" + sessionId;
            // 动态窗口：新话题仅保留最小上下文（2 条），追加/修改保留全量（8 条）
            int memorySize = newTopic ? MIN_MEMORY_WINDOW : MAX_SPRING_MEMORY_WINDOW;
            log.info("SPRING ChatMemory 窗口: {} 条 (newTopic={})", memorySize, newTopic);
            ChatResponse response = springMonitorAgent.prompt()
                    .tools(enabledTools(springChatTools))
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, conversationId);
                        a.param("chat_memory_retrieve_size", memorySize);
                    })
                    .user(taskInstruction)
                    .call()
                    .chatResponse();
            recordTokenUsage(response, "SPRING");
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("SPRING 步骤执行失败", e);
            return "Spring 监测处理失败：" + e.getMessage();
        }
    }

    /**
     * 调用 BILIBILI 子 Agent（bilibiliChatClient，含 BilibiliAgentTools：发动态等）。
     * <p>
     * 采用会话级隔离 + ChatMemory：{@code conversationId = userId + "_bilibili_" + sessionId}，
     * 用户扫码后说"继续"时，Agent 可从记忆中的上下文恢复待发动态内容。
     * <p>
     * 通过 {@link BilibiliToolContext#setCurrentUserId} 设置上下文，
     * 使 BilibiliAgentTools 能获取当前用户 ID。
     */
    /** Router 可能在 BILIBILI task 中自行添加图片要求，但实际并没有图片可用的模式 */
    private static final Pattern BILIBILI_IMAGE_CLAUSE = Pattern.compile(
            "[，,、]?(并|且|同时)?附上[一]?[张份]?(与[^。，,、]*?相关的)?(图片|配图|截图|艺术图)[^。]*?(?:[）\\)])?");

    /**
     * 从 BILIBILI task 指令中提取用户指定的动态配文。
     * 捕获在"图片/配图/插画"类追加指令前截断（如"，并附上上一轮生成的插画"、
     * "，配图如下"），防止 Router 追加的图片说明污染动态文本。
     * <p>
     * 匹配逻辑：找到「配文/文案/内容...[:：]」后，捕获内容直到遇到以下任一截断点：
     * 1) 图片相关追加语（并/且/同时 + 附上/加上/配上/使用 + ...图片/配图/插画/图）
     * 2) 独立的"配图/图片/插画/附图"等名词（如"插画（文件名：...）"）
     * 3) 字符串结尾
     */
    private static final Pattern DYNAMIC_CONTENT_PATTERN = Pattern.compile(
            "(?:配文|文案|内容|文字|文本)(?:为|是)?[:：]\\s*(.+?)"
                    + "(?=\\s*[，,、]?\\s*(?:"
                    + "(?:并|且|同时)?(?:附上|加上|配上|使用|并以|并将)[^。，,、]*?(?:图片|配图|插画|图)"
                    + "|(?:配图|图片|插画|附图)(?:如下|见图|如图|见下|在下面|（|\\()"
                    + ")[^。]*$|"
                    + "\\s*[，,、]\\s*(?:并|且|同时|另)[^。]*?(?:图片|配图|插画|图)[^。]*$|"
                    + "\\s*[，,、]\\s*$)",
            Pattern.DOTALL);

    /** 封面提示语匹配："封面如下""封面是""封面用""封面见图""封面如图" */
    private static final Pattern COVER_HINT_PATTERN = Pattern.compile(
            "封面(?:如下|是|用|见图|如图|见下|在下面)");

    private String executeBilibiliStep(String userId, String sessionId, String taskInstruction,
                                        String originalTask, boolean newTopic) {

        // 动态窗口：新话题仅保留最小上下文（2 条），追加/修改保留全量（5 条）
        int memorySize = newTopic ? MIN_MEMORY_WINDOW : MAX_BILIBILI_MEMORY_WINDOW;
        log.info("BILIBILI ChatMemory 窗口: {} 条 (newTopic={})", memorySize, newTopic);
        // B站工具被禁用时直接返回
        if (enabledTools(bilibiliChatTools).length == 0) {
            log.info("BILIBILI Agent 已被禁用，跳过执行: input={}", taskInstruction);
            return "当前 B站操作功能已被管理员禁用，暂时无法处理此请求。";
        }
        BilibiliToolContext.setCurrentUserId(userId);
        try {
            // 检查前置步骤（如 IMAGE）是否生成了图片，若有则桥接到 AttachmentContext
            ReceivedContent generatedImage = ImageTools.peekGeneratedImage();
            String effectiveTask = taskInstruction;

            if (generatedImage != null) {
                // 有可用图片 → 桥接到 AttachmentContext，BILIBILI 工具可找到
                AttachmentContext.put(userId, List.of(generatedImage));
                log.info("BILIBILI step: 已将前置步骤生成的图片桥接到 AttachmentContext, userId={}, size={}",
                        userId, generatedImage.toBytes() != null ? generatedImage.toBytes().length : 0);
                // 不清理 task 中的图片引用——图片确实存在，引用合法
            } else {
                // 无可用图片 → 清理 Router 自行添加的图片要求（防止 Agent 不调用工具）
                effectiveTask = BILIBILI_IMAGE_CLAUSE.matcher(taskInstruction).replaceAll("");
                if (!effectiveTask.equals(taskInstruction)) {
                    log.info("BILIBILI task 已清理图片要求: {} → {}", taskInstruction, effectiveTask);
                }
            }

            // 从原始 task（不含 buildContextualInput 追加的前置步骤输出）中提取用户指定的配文
            // 必须用 originalTask 而非 taskInstruction，否则正则会匹配到追加的 IMAGE 聊天输出
            String intendedContent = extractDynamicContent(originalTask);
            if (intendedContent != null && !intendedContent.isBlank()) {
                BilibiliToolContext.setIntendedDynamicContent(userId, intendedContent);
                log.info("BILIBILI step: 已提取用户指定配文并存入工具上下文: content={}", intendedContent);
            }

            // 封面意图确定性检测（正则，优于 LLM 抽取）
            if (originalTask != null) {
                if (COVER_HINT_PATTERN.matcher(originalTask).find()) {
                    BilibiliToolContext.setCoverChoice(userId, "hint");
                    log.info("BILIBILI step: 检测到「封面如下」类提示");
                } else if (originalTask.contains("默认封面")) {
                    BilibiliToolContext.setCoverChoice(userId, "default");
                    log.info("BILIBILI step: 检测到「使用默认封面」选择");
                }
            }

            String conversationId = userId + "_bilibili_" + sessionId;
            var promptBuilder = bilibiliChatClient.prompt()
                    .tools(enabledTools(bilibiliChatTools))
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, conversationId);
                        a.param("chat_memory_retrieve_size", memorySize);
                    })
                    .user(effectiveTask);

            // 有生成图片时，通过 system message 明确告知 Agent 使用带图发布工具
            if (generatedImage != null) {
                StringBuilder sysMsg = new StringBuilder("【图片可用】前置步骤已生成一张图片，"
                        + "请使用 postBilibiliDynamicWithImage 工具发布带图动态。"
                        + "直接调用工具执行发布，不要询问用户是否需要图片。");
                if (intendedContent != null && !intendedContent.isBlank()) {
                    sysMsg.append("\n【强制】content 参数必须严格等于以下文本，一字不差，"
                            + "禁止添加任何来自上下文的其他文字：\n>>>")
                            .append(intendedContent).append("<<<");
                }
                promptBuilder.system(sysMsg.toString());
            }

            ChatResponse response = promptBuilder.call().chatResponse();
            recordTokenUsage(response, "BILIBILI");
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("BILIBILI 步骤执行失败", e);
            return "B站操作失败：" + e.getMessage();
        } finally {
            // 不在此清理 BilibiliToolContext：IlinkBotRunner 还需要从中获取二维码图片
        }
    }

    /**
     * 调用图片子 Agent（imageChatClient，含 ImageTools：文生图/识图）。
     * <p>
     * 使用每轮唯一的 conversation ID（imageRunId），确保不同编排轮之间
     * ChatMemory 完全隔离——上轮的水彩画对话不会污染本轮的识图请求。
     * <p>
     * 附件生命周期：有新图片时，先清除旧附件再存入新附件；纯文本追问时保留旧附件，
     * 使 LLM 可以通过 ref:0 引用上一轮的图片。
     * <p>
     * 【兜底机制】IMAGE Agent 的 LLM 负责决策和工具调用（正常路径）。如果 LLM
     * 产出包含臆测图片 URL 但没有调用 generateImage 工具的情况，此处检测并补偿
     * 生成真实图片，确保后续 BILIBILI 等步骤能拿到图片数据。
     */
    private String executeImageStep(String imageRunId, String input,
                                     List<ReceivedContent> attachments, String task) {
        // 图像工具被禁用时直接返回，避免 LLM 在空工具列表下臆造结果
        if (enabledTools(imageChatTools).length == 0) {
            log.info("IMAGE Agent 已被禁用，跳过执行: input={}", input);
            return "当前图片生成/识别功能已被管理员禁用，暂时无法处理此请求。";
        }

        // 筛选图片类附件
        List<ReceivedContent> imageAttachments = attachments != null
                ? attachments.stream().filter(ReceivedContent::isImage).toList()
                : List.of();
        boolean hasImage = !imageAttachments.isEmpty();

        // 有新图片上传：清除旧附件（ConcurrentHashMap → 跨线程回退路径），再存入新附件
        // 同时清理 Bot 生成图片缓存（新图片 = 新上下文，旧生成图已无关）
        if (hasImage) {
            ImageTools.clearLastGenerated(ImageTools.getCurrentUserId());
            ImageTools.clearAllImageAttachments();
            ImageTools.storeImageAttachments(imageAttachments);
        }
        // 纯文本追问：不清理，保留上轮的 ConcurrentHashMap 条目供 resolveImageUrl 回退

        String result;
        try {
            ChatResponse imageResponse = imageChatClient.prompt()
                    .tools(enabledTools(imageChatTools))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, imageRunId))
                    .user(input)
                    .call()
                    .chatResponse();
            recordTokenUsage(imageResponse, "IMAGE");
            result = imageResponse.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("IMAGE 步骤执行失败", e);
            return "图片处理失败：" + e.getMessage();
        }

        // ===== 兜底：检测 LLM 是否没有调用 generateImage 工具 =====
        // 条件：输入是生图请求 + 工具确实没被调用（GENERATED_IMAGE_STORE 为空）
        // 不检测 LLM 输出格式——qwen-plus 对简短 prompt 的 function calling
        // 可靠性不足，任何形式的未调工具都需要补偿
        // 若 ImageTools 已被禁用，直接跳过重试（空工具列表重试无意义）
        if (result != null && isLikelyImageGeneration(task)
                && ImageTools.peekGeneratedImage() == null
                && enabledTools(imageChatTools).length > 0) {
            log.warn("IMAGE Agent 未调用 generateImage 工具，通过增强 prompt 重试: input={}", input);
            String retryInput = "★ 重要：你必须立即调用 generateImage 工具来生成图片。" +
                    "不要在调用工具之前输出任何文字、链接或 Markdown 图片语法。\n" +
                    "任务：" + input;
            try {
                ChatResponse retryResponse = imageChatClient.prompt()
                        .tools(enabledTools(imageChatTools))
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, imageRunId))
                        .user(retryInput)
                        .call()
                        .chatResponse();
                recordTokenUsage(retryResponse, "IMAGE");
                result = retryResponse.getResult().getOutput().getText();
                log.info("IMAGE Agent 重试完成, generateImage 是否被调: {}",
                        ImageTools.peekGeneratedImage() != null);
            } catch (Exception e) {
                log.error("IMAGE Agent 重试失败: {}", e.getMessage());
            }
        }

        // ===== 二次兜底：重试后仍未生成图片，直接强制生成 =====
        // 避免 IMAGE Agent 反复"描述"而不生图，导致最终无图返回。
        // 从原始 input 中提取生图描述（去掉"参考数据"等污染前缀）。
        if (isLikelyImageGeneration(task)
                && ImageTools.peekGeneratedImage() == null
                && enabledTools(imageChatTools).length > 0) {
            log.warn("IMAGE Agent 重试后仍未生成图片，启动强制兜底生图: input={}", input);
            try {
                String genPrompt = extractImagePrompt(input);
                String url = imageTools.fallbackGenerateImage(genPrompt, null, null);
                if (url != null) {
                    result = "画好了！🎨\n" + url;
                    log.info("IMAGE Agent 强制兜底生图成功: url={}", LogUtils.truncateUrlForLog(url));
                }
            } catch (Exception e) {
                log.error("IMAGE Agent 强制兜底生图失败: {}", e.getMessage());
            }
        }

        return result;
    }

    /** 判读输入是否为生图请求（用于兜底检测） */
    private static boolean isLikelyImageGeneration(String input) {
        if (input == null) return false;
        String s = input.replaceAll("\\s+", "");
        boolean gen = s.contains("画") || s.contains("生成") || s.contains("创作")
                || s.contains("制作") || s.contains("创建");
        boolean nonGen = s.contains("识别") || s.contains("描述") || s.contains("修改")
                || s.contains("看看") || s.contains("改一下") || s.contains("有什么");
        return gen && !nonGen;
    }

    /**
     * 从 IMAGE 步骤输入中提取生图描述，供强制兜底生图使用。
     * <p>
     * 输入可能含"参考数据"等前置步骤污染文本，此处截取任务主体部分：
     * 优先取首个句号/换行前的内容（通常是 Router 给的原始 task），
     * 若含"参考数据"标记则截断到该标记之前。
     */
    private static String extractImagePrompt(String input) {
        if (input == null) return "";
        String s = input;
        // 去掉"参考数据"标记及其后的前置步骤输出
        int refIdx = s.indexOf("【参考数据");
        if (refIdx > 0) {
            s = s.substring(0, refIdx);
        }
        // 取首行作为生图描述（Router 的 task 通常是一句话指令）
        int newline = s.indexOf('\n');
        if (newline > 0) {
            s = s.substring(0, newline);
        }
        s = s.trim();
        return s.isBlank() ? input.trim() : s;
    }

    /**
     * 从 BILIBILI task 指令中提取用户指定的动态配文。
     * 匹配模式如："发布一条B站动态，配文为：xxx" / "发B站动态，文案：xxx" 等。
     * @return 用户指定的配文，或 null（无法提取时）
     */
    private static String extractDynamicContent(String taskInstruction) {
        if (taskInstruction == null || taskInstruction.isBlank()) return null;
        Matcher m = DYNAMIC_CONTENT_PATTERN.matcher(taskInstruction);
        if (m.find()) {
            String content = m.group(1).trim();
            // 如果内容以引号包裹，剥离引号
            if ((content.startsWith("\"") && content.endsWith("\""))
                    || (content.startsWith("「") && content.endsWith("」"))
                    || (content.startsWith("'") && content.endsWith("'"))) {
                content = content.substring(1, content.length() - 1).trim();
            }
            return content;
        }
        // 兜底：主正则未命中时，用简单规则截断到图片/附图追加语之前
        return fallbackExtractContent(taskInstruction);
    }

    /**
     * 兜底提取：找到「配文/文案/内容...[:：]」后，截取到第一个图片追加语
     * （"，并" / "，且" / "，附" / "，配" 等）或字符串结尾之前的内容。
     * 防止 Router 追加的"并附上...插画（文件名：...）"污染动态文本。
     */
    private static String fallbackExtractContent(String taskInstruction) {
        java.util.regex.Matcher kw = Pattern.compile(
                "(?:配文|文案|内容|文字|文本)(?:为|是)?[:：]").matcher(taskInstruction);
        if (!kw.find()) return null;
        String after = taskInstruction.substring(kw.end()).trim();
        // 在 after 中查找图片追加语起始位置
        java.util.regex.Matcher cut = Pattern.compile(
                "[，,、]\\s*(?:并|且|同时|另)[^。]*?(?:图片|配图|插画|图)[^。]*"
                        + "|[，,、]\\s*(?:附上|加上|配上|使用)[^。]*?(?:图片|配图|插画|图)[^。]*"
                        + "|(?:(?:配图|图片|插画|附图)(?:如下|见图|如图|见下|在下面|（|\\())")
                .matcher(after);
        String content = cut.find() ? after.substring(0, cut.start()).trim() : after;
        if (content.isEmpty()) return null;
        // 若截断处仍带尾随标点（如结尾逗号），清理
        content = content.replaceAll("[，,、\\s]+$", "");
        return content;
    }

    /**
     * 调用文件子 Agent（fileChatClient，含 FileTools：文档读取/检索/生成）。
     * <p>
     * 绑定附件中的文档类文件（PDF/Word/TXT）到 FileTools，使 LLM 可以通过
     * readDocument 工具按文件名读取。
     * <p>
     * bindFiles 采用追加模式：同名文件会被覆盖，不同名文件累积。
     * 纯文本追问（如"总结文件内容"）不触发绑定，保留已有缓存，
     * 实现多轮文档上传统一会话的连续性。
     */
    private String executeFileStep(String input, List<ReceivedContent> attachments) {
        // 文件工具被禁用时直接返回
        if (enabledTools(fileChatTools).length == 0) {
            log.info("FILE Agent 已被禁用，跳过执行: input={}", input);
            return "当前文件处理功能已被管理员禁用，暂时无法处理此请求。";
        }

        // 筛选文档类附件
        List<ReceivedContent> docs = attachments != null
                ? attachments.stream().filter(ReceivedContent::isDocument).toList()
                : List.of();
        boolean hasNewDocs = !docs.isEmpty();

        // 新文档上传：追加到已有文件列表（不清理旧缓存）
        if (hasNewDocs) {
            FileTools.bindFiles(docs);
        }

        // 跨轮文件上下文：当前轮无新文档但已有绑定文件时，注入文件名列表，
        // 使 FILE Agent 知道可以直接对已有文件进行检索/总结/生成，无需用户重新上传
        String effectiveInput = input;
        if (!hasNewDocs) {
            String summary = FileTools.getBoundFilesSummary();
            if (summary != null) {
                effectiveInput = input + "\n" + summary
                        + "\n提示：上述文件此前已上传并完成解析，可直接使用 searchDocument/summarizeDocument 获取内容，无需重新上传。";
            }
        }
        // 纯文本追问：不清理，保留已有 ConcurrentHashMap 条目供 readDocument 回退

        try {
            ChatResponse response = fileChatClient.prompt()
                    .tools(enabledTools(fileChatTools))
                    .user(effectiveInput)
                    .call()
                    .chatResponse();
            recordTokenUsage(response, "FILE");
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("FILE 步骤执行失败", e);
            return "文件处理失败：" + e.getMessage();
        } finally {
            // 消费生成的文档（如有），暂存到 ThreadLocal 供 aggregateResults 聚合
            ReceivedContent genFile = FileTools.consumeGeneratedFile();
            if (genFile != null) {
                PENDING_GENERATED_FILES.set(List.of(genFile));
                log.info("FILE 步骤生成了文档: fileName={}", genFile.fileName());
            }
            // 不在此清除会话：文件绑定数据需跨轮保留供后续追问使用。
            // 与 IMAGE Agent 对齐——清除仅在下次有新文档上传时触发（见上方 clearSession）。
        }
    }

    // ==================== 结果聚合 ====================

    /** 匹配 DashScope 图片 URL 的正则，用于跨线程回退提取 */
    private static final Pattern DASHSCOPE_URL_PATTERN =
            Pattern.compile("https?://[^\\s]*dashscope[^\\s>)]*");

    /**
     * 聚合所有步骤的输出为最终 {@link MatchReplyResult}。
     * <p>
     * 单步骤：直接返回该步骤文本 + 可能的生成图片。
     * 多步骤：按序拼接所有文本，提取最后步骤生成的图片。
     * <p>
     * 取图片逻辑：优先从 ThreadLocal 取（同线程），
     * 若为空则扫描所有步骤输出中的 DashScope URL，通过 ConcurrentHashMap 回退查询（跨线程）。
     */
    private MatchReplyResult aggregateResults(List<PlanStep> sortedSteps,
                                               Map<Integer, String> stepOutputs) {
        // 优先 ThreadLocal（同线程快速路径）
        ReceivedContent genImg = ImageTools.consumeGeneratedImage();

        // 收集 FILE Agent 生成的文档文件（executeFileStep 中已消费并暂存）
        List<ReceivedContent> generatedFiles = PENDING_GENERATED_FILES.get();
        PENDING_GENERATED_FILES.remove();

        // 跨线程回退：扫描 LLM 输出中的 DashScope URL，从 ConcurrentHashMap 查询
        if (genImg == null) {
            for (String output : stepOutputs.values()) {
                if (output != null) {
                    Matcher m = DASHSCOPE_URL_PATTERN.matcher(output);
                    while (m.find()) {
                        String url = m.group();
                        genImg = ImageTools.consumeGeneratedImageByUrl(url);
                        if (genImg != null) {
                            log.info("编排聚合: 通过 URL 回退取得生成图片, url={}", LogUtils.truncateUrlForLog(url));
                            break;
                        }
                    }
                }
                if (genImg != null) break;
            }
        }

        if (sortedSteps.size() == 1) {
            String text = stepOutputs.get(sortedSteps.get(0).index());
            if (genImg != null) {
                return new MatchReplyResult(text, genImg, false, generatedFiles);
            }
            return new MatchReplyResult(text, null, false, generatedFiles);
        }

        // 多步骤：拼接所有文本输出
        StringBuilder sb = new StringBuilder();

        // 若计划中包含 IMAGE 生图步骤且已成功产出图片，则只保留 IMAGE 步骤自身的
        // 简短说明，不再附加前置步骤（如 TEXT 查天气）的冗长文字输出——
        // 否则 TEXT 可能因"简笔画"字样自作主张写一段"文字简笔画"，与真图一起发给用户。
        boolean hasImageStep = sortedSteps.stream().anyMatch(s -> "IMAGE".equalsIgnoreCase(s.agent()));
        // 计划中包含 IMAGE 生图步骤时，最终文本只保留 IMAGE 步骤自身的输出，
        // 无论图片是否成功生成都不再附加前置步骤（如 TEXT 查数据）的冗长/文字画内容，
        // 避免前置步骤越权写的"文字简笔画/文字海报"透传给用户。
        if (hasImageStep) {
            for (PlanStep step : sortedSteps) {
                if ("IMAGE".equalsIgnoreCase(step.agent())) {
                    String output = stepOutputs.get(step.index());
                    if (output != null && !output.isBlank()) {
                        sb.append(output);
                    }
                }
            }
            if (genImg != null) {
                log.info("编排聚合: 含 IMAGE 生图步骤，仅保留 IMAGE 步骤说明（已忽略前置步骤文字） + 生成图片");
                return new MatchReplyResult(sb.toString(), genImg, false, generatedFiles);
            }
            log.info("编排聚合: 含 IMAGE 生图步骤但未产出图片，仅返回 IMAGE 步骤文本（已忽略前置步骤文字）");
            return new MatchReplyResult(sb.toString(), null, false, generatedFiles);
        }

        for (int i = 0; i < sortedSteps.size(); i++) {
            PlanStep step = sortedSteps.get(i);
            String output = stepOutputs.get(step.index());
            if (output != null && !output.isBlank()) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append(output);
            }
        }

        if (genImg != null) {
            log.info("编排聚合: 文本 {} 字符 + 生成图片", sb.length());
            return new MatchReplyResult(sb.toString(), genImg, false, generatedFiles);
        }
        log.info("编排聚合: 文本 {} 字符，无图片", sb.length());
        return new MatchReplyResult(sb.toString(), null, false, generatedFiles);
    }

    // ==================== Token 用量统计辅助方法 ====================

    /**
     * 从 ChatResponse 中提取 Usage 并记录到 TokenUsageTracker。
     */
    private void recordTokenUsage(ChatResponse response, String agentName) {
        if (response == null) return;
        Usage usage = response.getMetadata().getUsage();
        if (usage != null) {
            long cachedTokens = extractCachedPromptTokens(usage);
            if (cachedTokens > 0) {
                long rawPrompt = usage.getPromptTokens() + cachedTokens;
                log.info("recordTokenUsage: agent={}, promptTokens={}, completionTokens={}, "
                                + "totalTokens={}, cachedTokens={}, cacheHitRate={:.1f}%",
                        agentName, usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), cachedTokens,
                        (cachedTokens * 100.0) / rawPrompt);
            } else {
                log.info("recordTokenUsage: agent={}, promptTokens={}, completionTokens={}, totalTokens={}",
                        agentName, usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens());
            }
            tokenUsageTracker.record(agentName,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens(),
                    cachedTokens);
        }
    }

    /**
     * 从 Usage 中提取 DashScope 上下文缓存命中的 token 数。
     * 使用反射避免硬编译依赖。若平台不支持，静默返回 0。
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
        }
        return 0;
    }
}
