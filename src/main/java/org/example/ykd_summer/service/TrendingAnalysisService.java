package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.BilibiliTrendingVideo;
import org.example.ykd_summer.dto.WeeklyTrendingAnalysis;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * B站热门视频分析服务。
 * <p>
 * 对榜单数据进行统计 + 调用 AI 生成创作建议。
 */
public interface TrendingAnalysisService {

    /**
     * 分析热门视频数据，产出分区分布统计和 AI 创作建议。
     *
     * @param trendingVideos 热门视频列表
     * @return 分析结果（AI 建议可能为 null 表示调用失败）
     */
    WeeklyTrendingAnalysis analyze(List<BilibiliTrendingVideo> trendingVideos);

    /**
     * 分析热门视频数据 + 用户创作档案，产出分区分布统计和个性化 AI 创作建议。
     *
     * @param trendingVideos    热门视频列表
     * @param userUploadHistory 用户历史上传记录（来自创作档案向量库）
     * @return 分析结果（含用户创作档案摘要在内）
     */
    WeeklyTrendingAnalysis analyze(List<BilibiliTrendingVideo> trendingVideos,
                                    List<Document> userUploadHistory);
}
