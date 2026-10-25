package org.example.ykd_summer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B站视频统计数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilibiliVideoStat {
    /** 视频 aid */
    private String aid;
    /** 视频 bvid */
    private String bvid;
    /** 视频标题 */
    private String title;
    /** 视频链接 */
    private String url;
    /** 播放量 */
    private int playCount;
    /** 点赞数 */
    private int likeCount;
    /** 评论数 */
    private int replyCount;
    /** 弹幕数 */
    private int danmakuCount;
}
