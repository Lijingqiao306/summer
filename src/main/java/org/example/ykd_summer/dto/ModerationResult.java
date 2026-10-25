package org.example.ykd_summer.dto;

/**
 * 单条评论的审核结果。
 *
 * @param rpid   评论 ID
 * @param action 审核动作（DELETE / KEEP）
 * @param reason 删除原因（仅 action=DELETE 时有意义）
 * @param source 判定来源（KEYWORD / AI）
 */
public record ModerationResult(
        long rpid,
        Action action,
        String reason,
        Source source
) {
    public enum Action { DELETE, KEEP }
    public enum Source { KEYWORD, AI }
}
