package org.example.ykd_summer.dto;

/**
 * B站视频发布结果。
 *
 * @param success  是否发布成功（已进入审核流程）
 * @param bvid     稿件 BV 号（可能为 null，如发布成功但未提取到）
 * @param message  结果描述（失败原因或附加信息）
 * @param draftUrl 草稿编辑页 URL（发布失败时供用户手动完成）
 */
public record VideoPublishResult(boolean success, String bvid, String message, String draftUrl) {

    public static VideoPublishResult ok(String bvid) {
        return new VideoPublishResult(true, bvid, "发布成功", null);
    }

    public static VideoPublishResult fail(String message, String draftUrl) {
        return new VideoPublishResult(false, null, message, draftUrl);
    }
}
