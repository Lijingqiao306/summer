package org.example.ykd_summer.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.example.ykd_summer.web.ConsoleEvent;
import org.example.ykd_summer.web.ConsoleEventBus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.example.ykd_summer.web.ToolCallTracker;
import org.example.ykd_summer.web.ToolStateManager;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 工具调用切面。
 * <p>
 * 拦截所有 {@code @Tool} 注解的 Spring AI 工具方法，
 * 调用开始/结束时发布事件到 SSE 事件总线，驱动监控页面工具方块点亮/熄灭。
 * <p>
 * 同时作为启用/禁用机制的安全兜底：即使 LLM 绕过了 {@code .tools()} 过滤
 * 仍发起工具调用，此处也会拒绝已禁用工具类的执行。
 */
@Aspect
@Component
public class ToolCallAspect {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai"));

    private final ConsoleEventBus eventBus;
    private final ToolCallTracker tracker;
    private final ToolStateManager toolStateManager;

    public ToolCallAspect(ConsoleEventBus eventBus, ToolCallTracker tracker,
                          ToolStateManager toolStateManager) {
        this.eventBus = eventBus;
        this.tracker = tracker;
        this.toolStateManager = toolStateManager;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        String toolClass = pjp.getTarget().getClass().getSimpleName();
        // 去掉 CGLIB 代理类名后缀（如 AmapTools$$SpringCGLIB$$0）
        int proxyIdx = toolClass.indexOf("$$");
        if (proxyIdx > 0) {
            toolClass = toolClass.substring(0, proxyIdx);
        }
        String method = pjp.getSignature().getName();

        // 安全兜底：拒绝执行已禁用的工具类
        if (toolStateManager.isDisabled(toolClass)) {
            eventBus.publish(new ConsoleEvent(TIME_FMT.format(Instant.now()), "TOOL_START", toolClass, "TOOL",
                    "✗ " + method + "() — 已禁用，拒绝执行", null, null));
            return "工具 [" + toolClass + "] 已被禁用，无法执行此操作。";
        }

        tracker.start(toolClass);
        eventBus.publish(new ConsoleEvent(TIME_FMT.format(Instant.now()), "TOOL_START", toolClass, "TOOL",
                "▶ 调用 " + method + "()", null, null));
        try {
            return pjp.proceed();
        } finally {
            tracker.end(toolClass);
            eventBus.publish(new ConsoleEvent(TIME_FMT.format(Instant.now()), "TOOL_END", toolClass, "TOOL",
                    "✓ " + method + "() 完成", null, null));
        }
    }
}
