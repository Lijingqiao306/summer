package org.example.ykd_summer.utils;

import org.example.ykd_summer.dto.PausedTaskState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-user 任务控制上下文。
 * <p>
 * 三态：NORMAL / PAUSED / CANCELLED
 * <p>
 * 写路径：
 * <ul>
 *   <li>IlinkBotRunner.handleMessages()（SDK 回调线程）：
 *       识别到"暂停"→ setPaused，"取消"→ setCancelled</li>
 *   <li>OrchestrationServiceImpl.execute()（Actor 虚拟线程）：
 *       for 循环中检测到 PAUSED → savePausedTask；
 *       检测到 CANCELLED → 清理资源</li>
 * </ul>
 * 读路径：
 * <ul>
 *   <li>OrchestrationServiceImpl for 循环：isPaused / isCancelled</li>
 *   <li>AiChatServiceImpl.matchReply()：hasPausedTask</li>
 *   <li>OrchestrationServiceImpl.resume()：consumePausedTask</li>
 * </ul>
 */
public final class TaskControlContext {

    public enum ControlState {
        NORMAL,      // 正常运行
        PAUSED,      // 已暂停（资源保留等待恢复）
        CANCELLED    // 已取消（资源已清理）
    }

    private static final ConcurrentHashMap<String, AtomicReference<ControlState>> STATES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PausedTaskState> PAUSED_TASKS =
            new ConcurrentHashMap<>();

    private TaskControlContext() {}

    // ======================== 状态读写 ========================

    public static void setPaused(String userId) {
        STATES.computeIfAbsent(userId, k -> new AtomicReference<>()).set(ControlState.PAUSED);
    }

    public static void setCancelled(String userId) {
        STATES.computeIfAbsent(userId, k -> new AtomicReference<>()).set(ControlState.CANCELLED);
    }

    public static void clear(String userId) {
        AtomicReference<ControlState> ref = STATES.get(userId);
        if (ref != null) ref.set(ControlState.NORMAL);
    }

    private static ControlState getState(String userId) {
        AtomicReference<ControlState> ref = STATES.get(userId);
        return ref != null ? ref.get() : ControlState.NORMAL;
    }

    public static boolean isPaused(String userId) {
        return getState(userId) == ControlState.PAUSED;
    }

    public static boolean isCancelled(String userId) {
        return getState(userId) == ControlState.CANCELLED;
    }

    // ======================== 暂停任务持久化 ========================

    /** 写入暂停快照并设置 PAUSED 状态（由 OrchestrationServiceImpl for 循环调用） */
    public static void savePausedTask(String userId, PausedTaskState state) {
        PAUSED_TASKS.put(userId, state);
        setPaused(userId);
    }

    /** 是否存在可恢复的暂停任务 */
    public static boolean hasPausedTask(String userId) {
        return PAUSED_TASKS.containsKey(userId);
    }

    /** 取出暂停任务（用于恢复），同时清除状态 */
    public static PausedTaskState consumePausedTask(String userId) {
        PausedTaskState state = PAUSED_TASKS.remove(userId);
        clear(userId);
        return state;
    }

    /** 丢弃暂停任务（用户取消/发其他消息覆盖时），同时清除状态 */
    public static PausedTaskState discardPausedTask(String userId) {
        return consumePausedTask(userId);
    }
}
