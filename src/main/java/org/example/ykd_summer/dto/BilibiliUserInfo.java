package org.example.ykd_summer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B站用户基础信息（粉丝数等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BilibiliUserInfo {
    /** 用户 mid */
    private String mid;
    /** 用户名 */
    private String name;
    /** 粉丝数 */
    private int followerCount;
}
