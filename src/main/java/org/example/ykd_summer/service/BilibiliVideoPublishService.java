package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.VideoMetadata;
import org.example.ykd_summer.dto.VideoPublishResult;

/**
 * B站视频稿件发布服务（Playwright 浏览器操控）。
 * <p>
 * 视频已通过 API 上传并存入草稿箱后，由本服务负责打开发布页、
 * 补全页面端必填项（创作声明等），并点击"立即投稿"完成发布。
 */
public interface BilibiliVideoPublishService {

    /**
     * 发布指定草稿。
     *
     * @param draftAid 草稿 ID（aid，来自草稿保存接口）
     * @param metadata 稿件元数据（用于页面端校验/补填）
     * @return 发布结果（含 BV 号或失败原因）
     */
    VideoPublishResult publishDraft(long draftAid, VideoMetadata metadata);
}
