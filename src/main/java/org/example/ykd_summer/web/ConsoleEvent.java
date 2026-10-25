package org.example.ykd_summer.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 控制台事件的 DTO。
 * <p>
 * 涵盖日志输出（LOG）、关键步骤（STEP）、任务状态变更（TASK）三类事件，
 * 通过 SSE 推送至浏览器监控页面。
 */
public record ConsoleEvent(
        String timestamp,    // 已格式化的北京时间字符串（HH:mm:ss.SSS）
        String level,       // INFO / DEBUG / WARN / ERROR / STEP / TASK
        String source,      // 来源类名（如 "IlinkBotRunner"）
        String type,        // LOG / STEP / TASK
        String message,     // 事件内容
        String userId,      // 关联用户（可为 null）
        String taskId       // 关联任务（可为 null）
) {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai"));

    public static ConsoleEvent log(String level, String source, String message) {
        return new ConsoleEvent(TIME_FMT.format(java.time.Instant.now()), level, source, "LOG", message, null, null);
    }

    public static ConsoleEvent step(String source, String message, String userId) {
        return new ConsoleEvent(TIME_FMT.format(java.time.Instant.now()), "STEP", source, "STEP", message, userId, null);
    }

    public static ConsoleEvent step(String source, String message) {
        return step(source, message, null);
    }

    public static ConsoleEvent task(String message, String userId, String taskId) {
        return new ConsoleEvent(TIME_FMT.format(java.time.Instant.now()), "TASK", "TaskTracker", "TASK", message, userId, taskId);
    }
}
