package org.example.ykd_summer.dto;

import java.util.List;
import java.util.Map;

/**
 * 暂停任务的执行快照。
 * <p>
 * 包含恢复执行所需的全部上下文字段，其中：
 * <ul>
 *   <li>{@code steps} — 拓扑排序后的步骤列表</li>
 *   <li>{@code stepOutputs} — 已完成步骤的输出（step index → 文本）</li>
 *   <li>{@code nextStepIndex} — 恢复时应从 sorted[this] 开始执行</li>
 *   <li>{@code pendingGeneratedFiles} — FILE Agent 已生成但 aggregateResults 尚未消费的文档，
 *       跨线程恢复时需要从 ThreadLocal 恢复（可为 null）</li>
 *   <li>{@code pendingModifyInstruction} — 暂停期间用户补充/修改的指令（如"把配文改成test"），
 *       恢复执行时注入到未完成步骤；可为 null</li>
 * </ul>
 */
public record PausedTaskState(
        List<PlanStep> steps,                          // 拓扑排序后的步骤列表
        Map<Integer, String> stepOutputs,               // 已完成步骤的输出
        int nextStepIndex,                             // 恢复时从步骤列表的哪个索引开始
        String userId,
        String sessionId,
        String userText,
        List<ReceivedContent> attachments,
        String imageRunId,
        // 跨线程安全：FILE Agent 生成的文档暂存于 ThreadLocal，
        // 恢复线程无法读取，需显式保存
        List<ReceivedContent> pendingGeneratedFiles,
        // 暂停期间用户累积的修改/补充指令（由 LLM 意图识别判定为 MODIFY）
        String pendingModifyInstruction
) {

    /** 返回叠加了新修改指令的副本（多次修改累积，用分号分隔） */
    public PausedTaskState withModifyInstruction(String extra) {
        String merged = (this.pendingModifyInstruction() == null || this.pendingModifyInstruction().isBlank())
                ? extra
                : this.pendingModifyInstruction() + "；" + extra;
        return new PausedTaskState(steps, stepOutputs, nextStepIndex, userId, sessionId,
                userText, attachments, imageRunId, pendingGeneratedFiles, merged);
    }
}
