package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.MatchReplyResult;
import org.example.ykd_summer.dto.OrchestrationPlan;
import org.example.ykd_summer.dto.PausedTaskState;
import org.example.ykd_summer.dto.ReceivedContent;

import java.util.List;
import java.util.function.Consumer;

/**
 * 编排服务接口。
 * <p>
 * 接收路由 Agent 输出的编排计划，按拓扑顺序逐步执行各子 Agent，
 * 并将前置步骤的输出作为上下文注入后续步骤，最终聚合结果。
 */
public interface OrchestrationService {

    /**
     * 执行编排计划并返回聚合后的回复结果。
     *
     * @param plan        路由 Agent 输出的步骤计划
     * @param userId      用户标识
     * @param sessionId   会话标识（Actor 创建时生成，用于 ChatMemory 隔离）
     * @param userText    用户原始消息文本
     * @param attachments 用户上传的附件列表（使用 {@link ReceivedContent} 统一载体），可为 null 或空
     * @param onProgress  进度回调，步骤切换时调用；可为 null（不发送进度提示）
     * @return 聚合后的回复结果
     */
    MatchReplyResult execute(OrchestrationPlan plan, String userId, String sessionId,
                             String userText, List<ReceivedContent> attachments,
                             Consumer<String> onProgress);

    /**
     * 恢复被暂停的编排任务。
     * <p>
     * 从 {@link PausedTaskState#nextStepIndex()} 对应的步骤继续执行，
     * 已完成步骤的输出直接复用，不再重新执行。
     * 执行中仍支持再次暂停/取消。
     *
     * @param state      暂停时保存的执行快照
     * @param onProgress 进度回调，可为 null（不发送进度提示）
     * @return 聚合后的回复结果
     */
    MatchReplyResult resume(PausedTaskState state, Consumer<String> onProgress);

    /**
     * 清理因暂停任务被丢弃而残留的跨线程资源。
     * <p>
     * 场景：用户暂停后，又发了其他消息（触发新的编排），旧的暂停任务被丢弃。
     * 此时需清理其遗留的生成图片、文档等中间产物，防止内存泄漏。
     *
     * @param userId 用户标识
     */
    void clearPausedResources(String userId);
}
