package org.example.ykd_summer.dto;

import java.util.List;
import java.util.Map;

/**
 * B站每周热门视频分析结果。
 * <p>
 * 包含分区统计分布和 AI 生成的创作建议。
 */
public record WeeklyTrendingAnalysis(
        /** 采集到的热门视频列表 */
        List<BilibiliTrendingVideo> topVideos,
        /** 分区 → 热门视频数量 */
        Map<String, Integer> partitionDistribution,
        /** 分区 → 平均播放量 */
        Map<String, Long> partitionAvgView,
        /** 分区 → 总点赞数 */
        Map<String, Long> partitionTotalLike,
        /** 最热分区名（上榜视频最多） */
        String dominantPartition,
        /** 统计层面的关键发现文本 */
        List<String> keyObservations,
        /** AI 生成的创作建议（可能为 null，表示 AI 调用失败） */
        String creativeAdvice,
        /** 用户历史创作档案摘要文本（可能为 null，表示无历史记录） */
        String userUploadHistorySummary
) {}
