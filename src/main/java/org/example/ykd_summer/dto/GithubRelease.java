package org.example.ykd_summer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub Release 数据模型。
 * 来自 GitHub REST API v3 的 /repos/{owner}/{repo}/releases 响应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRelease(
        @JsonProperty("name") String name,
        @JsonProperty("tag_name") String tagName,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("prerelease") boolean prerelease,
        @JsonProperty("draft") boolean draft,
        @JsonProperty("published_at") String publishedAt,
        @JsonProperty("body") String body) {
}
