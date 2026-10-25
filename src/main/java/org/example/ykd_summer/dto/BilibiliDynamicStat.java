package org.example.ykd_summer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B站动态统计数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilibiliDynamicStat {
    /** 动态 ID */
    private String dynamicId;
    /** 动态类型描述（如"纯文字""带图动态""转发动态"等） */
    private String type;
    /** 动态内容摘要（截取前 100 字） */
    private String content;
    /** 动态链接 */
    private String url;
    /** 点赞数 */
    private int likeCount;
    /** 评论数 */
    private int commentCount;
    /** 转发数 */
    private int forwardCount;
}
