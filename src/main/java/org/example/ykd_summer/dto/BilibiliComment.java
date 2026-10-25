package org.example.ykd_summer.dto;

/**
 * B站评论数据。
 *
 * @param rpid       评论 ID（去重唯一键）
 * @param mid        评论者 MID
 * @param uname      评论者昵称
 * @param avatar     评论者头像 URL
 * @param content    评论内容（纯文本）
 * @param likeCount  点赞数
 * @param replyCount 回复数
 * @param ctime      发布时间戳（秒）
 * @param sourceTitle 来源标题（视频标题 / 动态摘要）
 * @param sourceUrl  来源链接
 * @param sourceType 来源类型：1=视频, 11/17=动态（即评论 API type）
 * @param oid        内容 ID（视频为 aid, 动态为 comment_id_str 或 dynamic_id，用于删除 API）
 */
public record BilibiliComment(
        long rpid,
        long mid,
        String uname,
        String avatar,
        String content,
        int likeCount,
        int replyCount,
        long ctime,
        String sourceTitle,
        String sourceUrl,
        int sourceType,
        String oid
) {}
