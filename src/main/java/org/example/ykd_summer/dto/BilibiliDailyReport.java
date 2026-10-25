package org.example.ykd_summer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B站日报聚合数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilibiliDailyReport {
    /** 日报日期 */
    private String date;
    /** 用户信息 */
    private BilibiliUserInfo userInfo;
    /** 最新视频统计 */
    private BilibiliVideoStat videoStat;
    /** 最新动态统计 */
    private BilibiliDynamicStat dynamicStat;
}
