package org.example.ykd_summer.web;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.billing.BillingService;
import org.example.ykd_summer.billing.TokenUsageSnapshot;
import org.example.ykd_summer.billing.TokenUsageTracker;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 控制台监控 REST / SSE 接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/console")
public class ConsoleController {

    private final ConsoleEventBus eventBus;
    private final TaskTracker taskTracker;
    private final ToolRegistry toolRegistry;
    private final ToolCallTracker toolCallTracker;
    private final ToolStateManager toolStateManager;
    private final TokenUsageTracker tokenUsageTracker;
    private final BillingService billingService;

    /** 应用启动时间 */
    private static final Instant START_TIME = Instant.now();

    public ConsoleController(ConsoleEventBus eventBus, TaskTracker taskTracker,
                             ToolRegistry toolRegistry, ToolCallTracker toolCallTracker,
                             ToolStateManager toolStateManager,
                             TokenUsageTracker tokenUsageTracker,
                             BillingService billingService) {
        this.eventBus = eventBus;
        this.taskTracker = taskTracker;
        this.toolRegistry = toolRegistry;
        this.toolCallTracker = toolCallTracker;
        this.toolStateManager = toolStateManager;
        this.tokenUsageTracker = tokenUsageTracker;
        this.billingService = billingService;
    }

    /**
     * SSE 流。
     * 先补发最近 100 条历史，再实时推送。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = eventBus.subscribe();

        // 补发历史（在注册后补发，避免丢失注册和补发之间的新事件）
        List<ConsoleEvent> history = eventBus.getHistory(100);
        try {
            for (ConsoleEvent e : history) {
                emitter.send(SseEmitter.event()
                        .name("console-event")
                        .data(eventBus.toJson(e)));
            }
            // 发一条 heartbeat，告知历史结束
            emitter.send(SseEmitter.event()
                    .name("history-end")
                    .data("{\"ok\":true}"));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 状态快照。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uptime", formatDuration(Duration.between(START_TIME, Instant.now())));

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapUsed = mem.getHeapMemoryUsage().getUsed();
        long heapMax = mem.getHeapMemoryUsage().getMax();
        map.put("heapUsedMB", heapUsed / (1024 * 1024));
        map.put("heapMaxMB", heapMax / (1024 * 1024));
        map.put("heapUsagePercent", heapMax > 0 ? (heapUsed * 100 / heapMax) : 0);

        map.put("sseSubscribers", eventBus.subscriberCount());
        map.put("activeTasks", taskTracker.getActiveTasks().size());

        return map;
    }

    /**
     * 历史事件。
     */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "200") int limit) {
        return eventBus.getHistory(Math.min(limit, 500)).stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    /**
     * 活跃任务列表（用于轮询，SSE 已实时推送，此接口作快照）。
     */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return taskTracker.getActiveTasks().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", t.taskId);
                    m.put("userId", t.userId);
                    m.put("title", t.title);
                    m.put("currentStep", t.currentStep);
                    m.put("status", t.status.name());
                    m.put("elapsedMs", t.elapsedMs);
                    m.put("error", t.error);
                    // 本次任务的 token 消耗与折合费用（仅已完成时有值）
                    m.put("promptTokens", t.promptTokens);
                    m.put("completionTokens", t.completionTokens);
                    m.put("tokenTotal", t.tokenTotal);
                    m.put("tokenCostRmb", t.tokenCostRmb);
                    m.put("steps", t.steps.stream().map(s -> {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("name", s.name);
                        sm.put("status", s.status.name());
                        sm.put("time", s.time.toString());
                        return sm;
                    }).toList());
                    return m;
                })
                .toList();
    }

    /**
     * 工具类列表（含活跃状态、调用计数、禁用状态），用于监控页面工具方块渲染与状态同步。
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return toolRegistry.listTools().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("className", t.className());
                    m.put("displayName", t.displayName());
                    m.put("icon", t.icon());
                    m.put("activeCount", toolCallTracker.activeCount(t.className()));
                    m.put("totalCalls", toolCallTracker.totalCount(t.className()));
                    m.put("disabled", toolStateManager.isDisabled(t.className()));
                    m.put("isSupportComponent", t.isSupportComponent());
                    return m;
                })
                .toList();
    }

    /**
     * 切换工具类的启用/禁用状态。
     * 支撑组件（如 CommentModerationTool、NotifiedCommentStore、BilibiliStoreTools）
     * 不在此处切换，对它们始终返回 disabled=false。
     */
    @PostMapping("/tools/toggle")
    public Map<String, Object> toggleTool(@RequestParam String className) {
        boolean nowDisabled = toolStateManager.toggle(className);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("className", className);
        m.put("disabled", nowDisabled);
        return m;
    }

    /**
     * Agent 模型信息列表，用于监控页面展示各 Agent 使用的大模型。
     */
    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return List.of(
                agentInfo("router", "路由 Agent", "🧭", "qwen-vl-plus", "多模态任务编排与路由分发"),
                agentInfo("text", "文本 Agent", "💬", "qwen-plus", "通用对话、位置感知、联网搜索、邮件"),
                agentInfo("image", "图像 Agent", "🖼️", "qwen-plus", "文生图、图像识别"),
                agentInfo("file", "文件 Agent", "📁", "qwen-plus", "文档读取、检索、生成"),
                agentInfo("browser", "浏览器 Agent", "🌐", "qwen-plus", "网页浏览、页面操作"),
                agentInfo("bilibili", "B站 Agent", "📺", "qwen-turbo", "B站动态发布、评论管理"),
                agentInfo("spring", "Spring 监测 Agent", "🌱", "qwen-turbo", "Spring 项目版本监测、邮件通知")
        );
    }

    /**
     * Token 用量实时快照（内部调试接口，供余额估算等读取）。
     */
    @GetMapping("/token-usage")
    public TokenUsageSnapshot tokenUsage() {
        return tokenUsageTracker.getSnapshot();
    }

    /**
     * 余额 SSE 流。
     * <p>
     * 注册后立即推送当前缓存的余额；任务完成后由 {@link BillingService#pushToAll()} 主动推送。
     */
    @GetMapping(path = "/billing-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter billingStream() {
        return billingService.subscribe();
    }

    /**
     * 阿里云账户余额查询（按需触发，谨慎调用以避免 BSS 限流）。
     */
    @GetMapping("/billing")
    public Map<String, Object> billing() {
        BillingService.BillingBalance balance = billingService.queryBalance();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", balance.success());
        if (balance.availableAmount() != null) {
            m.put("balance", balance.availableAmount());
            m.put("currency", balance.currency());

            // 计算预估：结合累计费用
            TokenUsageSnapshot snapshot = tokenUsageTracker.getSnapshot();
            m.put("totalCost", snapshot.totalCost());
            m.put("estimatedBalance", Math.max(0, balance.availableAmount() - snapshot.totalCost()));
        }
        if (balance.error() != null) {
            m.put("error", balance.error());
        }
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    private Map<String, Object> agentInfo(String id, String name, String icon,
                                          String model, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("model", model);
        m.put("description", description);
        return m;
    }

    private Map<String, Object> toMap(ConsoleEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", e.timestamp().toString());
        m.put("level", e.level());
        m.put("source", e.source());
        m.put("type", e.type());
        m.put("message", e.message());
        if (e.userId() != null) m.put("userId", e.userId());
        if (e.taskId() != null) m.put("taskId", e.taskId());
        return m;
    }

    private static String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        long secs = d.getSeconds() % 60;
        if (days > 0) return String.format("%dd %02d:%02d:%02d", days, hours, mins, secs);
        if (hours > 0) return String.format("%d:%02d:%02d", hours, mins, secs);
        return String.format("%02d:%02d", mins, secs);
    }
}
