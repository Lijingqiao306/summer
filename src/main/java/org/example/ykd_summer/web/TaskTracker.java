package org.example.ykd_summer.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 任务追踪器。
 * <p>
 * 跟踪正在运行的 AI 处理任务，记录其当前步骤和状态。
 * 任务完成后保留 30 秒再移除，让浏览器监控页能观察到最终状态。
 */
@Slf4j
@Component
public class TaskTracker {

    /** 任务完成后保留时间 */
    private static final long RETENTION_MS = 30_000;

    private final ConcurrentHashMap<String, TrackedTask> tasks = new ConcurrentHashMap<>();
    private final ConsoleEventBus eventBus;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory());

    public TaskTracker(ConsoleEventBus eventBus) {
        this.eventBus = eventBus;
        // 每 10 秒清理过期已完成任务
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 注册一个新任务（开始处理时调用）。
     */
    public TrackedTask register(String userId, String title) {
        String taskId = userId + "_" + System.currentTimeMillis();
        TrackedTask task = new TrackedTask(taskId, userId, title);
        tasks.put(taskId, task);

        eventBus.publish(ConsoleEvent.task(
                "任务开始: " + title, userId, taskId));
        log.debug("TaskTracker: 注册任务 {} userId={}", taskId, userId);
        return task;
    }

    /**
     * 更新任务当前步骤。
     */
    public void updateStep(String taskId, String stepName) {
        TrackedTask task = tasks.get(taskId);
        if (task == null) return;
        task.currentStep = stepName;
        task.steps.add(new StepRecord(Instant.now(), stepName, StepStatus.RUNNING));
        task.elapsedMs = System.currentTimeMillis() - task.startTime;

        eventBus.publish(ConsoleEvent.task(
                "步骤: " + stepName, task.userId, taskId));
    }

    /**
     * 完成当前进行中的步骤。
     */
    public void completeStep(String taskId, String stepName) {
        TrackedTask task = tasks.get(taskId);
        if (task == null) return;
        // 把最后一个 matching 的 RUNNING 步骤改为 DONE
        for (int i = task.steps.size() - 1; i >= 0; i--) {
            StepRecord s = task.steps.get(i);
            if (s.name.equals(stepName) && s.status == StepStatus.RUNNING) {
                s.status = StepStatus.DONE;
                break;
            }
        }
        task.elapsedMs = System.currentTimeMillis() - task.startTime;
        eventBus.publish(ConsoleEvent.task(
                "步骤完成: " + stepName, task.userId, taskId));
    }

    /**
     * 标记任务成功完成（无 token 统计）。
     */
    public void complete(String taskId) {
        complete(taskId, 0L, 0L, 0L, 0.0);
    }

    /**
     * 标记任务成功完成并记录本次任务的 token 消耗与费用。
     *
     * @param taskId           任务 ID
     * @param promptTokens     本次任务的输入 token
     * @param completionTokens 本次任务的输出 token
     * @param totalTokens      本次任务的合计 token
     * @param costRmb          本次任务折合人民币费用
     */
    public void complete(String taskId, long promptTokens, long completionTokens, long totalTokens, double costRmb) {
        TrackedTask task = tasks.get(taskId);
        if (task == null) return;
        task.status = TaskStatus.SUCCESS;
        task.elapsedMs = System.currentTimeMillis() - task.startTime;
        task.finishTime = System.currentTimeMillis();
        task.promptTokens = Math.max(0L, promptTokens);
        task.completionTokens = Math.max(0L, completionTokens);
        task.tokenTotal = Math.max(0L, totalTokens);
        task.tokenCostRmb = Math.max(0.0, costRmb);

        eventBus.publish(ConsoleEvent.task(
                "任务完成 (耗时 " + formatMs(task.elapsedMs) + ")", task.userId, taskId));
        log.debug("TaskTracker: 任务完成 {} userId={} elapsed={}ms tokens={} cost=¥{:.6f}",
                taskId, task.userId, task.elapsedMs, task.tokenTotal, task.tokenCostRmb);
    }

    /**
     * 标记任务失败。
     */
    public void fail(String taskId, String errorMsg) {
        TrackedTask task = tasks.get(taskId);
        if (task == null) {
            // 任务在注册前就失败了：创建一条简短记录
            task = new TrackedTask(taskId, "unknown", "未知任务");
            tasks.put(taskId, task);
        }
        task.status = TaskStatus.FAILED;
        task.error = errorMsg;
        task.elapsedMs = System.currentTimeMillis() - task.startTime;
        task.finishTime = System.currentTimeMillis();

        eventBus.publish(ConsoleEvent.task(
                "任务失败: " + errorMsg, task.userId, taskId));
    }

    /**
     * 查找指定用户当前 active 的任务（用于步骤更新时无需 taskId 的场景）。
     */
    public TrackedTask findByUser(String userId) {
        return tasks.values().stream()
                .filter(t -> userId.equals(t.userId) && t.status == TaskStatus.RUNNING)
                .findFirst().orElse(null);
    }

    /**
     * 获取所有活跃（含最近完成未过期）的任务快照。
     */
    public List<TrackedTask> getActiveTasks() {
        return tasks.values().stream()
                .filter(t -> t.status == TaskStatus.RUNNING
                        || (t.finishTime > 0 && System.currentTimeMillis() - t.finishTime < RETENTION_MS))
                .toList();
    }

    /** 清理 30 秒前完成的过期任务 */
    private void cleanupExpired() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        tasks.entrySet().removeIf(e -> {
            TrackedTask t = e.getValue();
            return t.status != TaskStatus.RUNNING && t.finishTime > 0 && t.finishTime < cutoff;
        });
    }

    private static String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    // ==================== 内部类型 ====================

    public enum TaskStatus { RUNNING, SUCCESS, FAILED }

    public enum StepStatus { RUNNING, DONE }

    public static class StepRecord {
        public final Instant time;
        public final String name;
        public StepStatus status;

        StepRecord(Instant time, String name, StepStatus status) {
            this.time = time;
            this.name = name;
            this.status = status;
        }
    }

    public static class TrackedTask {
        public final String taskId;
        public final String userId;
        public final String title;
        public String currentStep;
        public final List<StepRecord> steps = new ArrayList<>();
        public TaskStatus status = TaskStatus.RUNNING;
        public String error;
        public final long startTime = System.currentTimeMillis();
        public long elapsedMs;
        public long finishTime;

        // 本次任务的 token 消耗与折合费用（任务完成时填充）
        public long promptTokens;
        public long completionTokens;
        public long tokenTotal;
        public double tokenCostRmb;

        TrackedTask(String taskId, String userId, String title) {
            this.taskId = taskId;
            this.userId = userId;
            this.title = title;
        }
    }
}
