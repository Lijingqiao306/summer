package org.example.ykd_summer.dto;

/**
 * B站热门视频数据（对应 ranking/v2 API 返回的单条视频）。
 */
public record BilibiliTrendingVideo(
        String bvid,          // BV号
        String title,         // 标题
        String partition,     // 分区名，如"知识""生活"
        int tid,              // 分区ID
        int viewCount,        // 播放量
        int likeCount,        // 点赞数
        int coinCount,        // 硬币数
        int favoriteCount,    // 收藏数
        int shareCount,       // 分享数
        int replyCount,       // 评论数
        String ownerName,     // UP主名
        int duration,         // 时长(秒)
        long pubdate          // 发布时间戳(秒)
) {}
