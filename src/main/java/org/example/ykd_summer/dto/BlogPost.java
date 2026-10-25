package org.example.ykd_summer.dto;

/**
 * Spring 官方博客文章数据模型。
 */
public record BlogPost(
        String title,
        String url,
        String date,
        String summary) {
}
