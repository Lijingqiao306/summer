package org.example.ykd_summer.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.SpringMonitorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring 官网监测查询工具集合，挂载到 SPRING Agent。
 * <p>
 * 提供 GitHub Release 轮询、版本对比、博客监测三大能力。
 * 所有 GitHub API 请求统一走可配置镜像（ghproxy.com），无需直连。
 * <p>
 * 注意：这些工具仅注册到 SPRING Agent，不挂载到 TEXT Agent。
 * 用户必须通过「监测管理」「检查更新」「查看版本」等监测相关意图触达 SPRING Agent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringMonitorTools {

    private final SpringMonitorService monitorService;

    @Tool(description = "获取指定 Spring 项目的最新 Release 列表。"
            + "当用户询问「Spring Boot 最新版本」「Spring Framework 最近发布了什么」时使用。"
            + "参数 repo 为 GitHub 仓库全名，如 spring-projects/spring-boot。"
            + "参数 count 为获取数量，默认 5，最多 30。")
    public String getLatestReleases(
            @ToolParam(description = "GitHub 仓库全名，如 spring-projects/spring-boot") String repo,
            @ToolParam(description = "获取数量，默认 5", required = false) Integer count) {
        log.info("getLatestReleases: repo={}, count={}", repo, count);
        int n = count != null && count > 0 ? count : 5;
        return monitorService.getReleases(repo, n);
    }

    @Tool(description = "检查 Spring 项目是否有新版本发布。"
            + "当用户询问「有没有新版本」「有新版本吗」「检查 Spring 更新」时使用。"
            + "参数 repo 为可选的仓库名，如 spring-projects/spring-boot。"
            + "不填则检查所有已配置的监测仓库（spring-boot, spring-framework, spring-cloud 等）。"
            + "返回结果会与本地缓存对比，只列出未见过的新版本，并自动更新缓存。")
    public String checkNewVersions(
            @ToolParam(description = "指定仓库名，如 spring-projects/spring-boot。不填则检查所有监测仓库", required = false) String repo) {
        log.info("checkNewVersions: repo={}", repo);
        return monitorService.checkNewVersions(repo);
    }

    @Tool(description = "获取某个 Spring Release 的完整信息（含 Release Notes 变更日志）。"
            + "当用户询问「某个版本的更新内容」「v3.4.3 改了什么」时使用。"
            + "参数 repo 为仓库全名，参数 tag 为 Release 标签（如 v3.4.3, v6.2.1）。")
    public String getReleaseDetail(
            @ToolParam(description = "仓库全名，如 spring-projects/spring-boot") String repo,
            @ToolParam(description = "Release 标签，如 v3.4.3、v6.2.1") String tag) {
        log.info("getReleaseDetail: repo={}, tag={}", repo, tag);
        return monitorService.getReleaseDetail(repo, tag);
    }

    @Tool(description = "在 Spring 项目的 Release Notes 中搜索指定关键词。"
            + "当用户询问「哪个版本支持了XX」「有没有关于XX的更新」时使用。"
            + "参数 repo 为仓库全名，参数 keyword 为搜索关键词。")
    public String searchReleaseByKeyword(
            @ToolParam(description = "仓库全名，如 spring-projects/spring-boot") String repo,
            @ToolParam(description = "搜索关键词，如 virtual thread、graalvm") String keyword) {
        log.info("searchReleaseByKeyword: repo={}, keyword={}", repo, keyword);
        return monitorService.searchReleases(repo, keyword);
    }

    @Tool(description = "获取 Spring 官方博客（spring.io/blog）的最新文章列表。"
            + "当用户询问「Spring 博客最近有什么新文章」「Spring 官方有什么新动态」时使用。"
            + "参数 count 为获取数量，默认 10。")
    public String getSpringBlogPosts(
            @ToolParam(description = "获取文章数量，默认 10", required = false) Integer count) {
        log.info("getSpringBlogPosts: count={}", count);
        int n = count != null && count > 0 ? count : 10;
        return monitorService.getBlogPosts(n);
    }

    @Tool(description = "检查 Spring 官方博客是否有新文章发布（与本地缓存对比）。"
            + "当用户询问「博客有新文章吗」「有没有新的博客更新」时使用。"
            + "返回未读过的新文章列表，并自动更新缓存。")
    public String checkNewBlogPosts() {
        log.info("checkNewBlogPosts");
        return monitorService.checkNewBlogPosts();
    }

    @Tool(description = "强制刷新所有 Spring 项目监测缓存（清除已见过的版本号和博客链接记录）。"
            + "当用户说「重新检测」「刷新缓存」「从头检查」时使用。"
            + "执行后下次 checkNewVersions 会把所有 Release 都视为新版本。")
    public String forceRefreshAll() {
        log.info("forceRefreshAll");
        return monitorService.forceRefreshAll();
    }

    // ==================== 精简查询（供邮件总结使用） ====================

    @Tool(description = "检查 Spring 项目新版本的精简版。只返回 tag、名称、日期、链接等关键信息，"
            + "不含完整 Release Notes。专门用于定时任务中获取数据后交由 LLM 提炼梗概。"
            + "参数 repo 为可选的仓库名，不填则检查所有已配置的监测仓库。")
    public String checkNewVersionsSlim(
            @ToolParam(description = "指定仓库名，如 spring-projects/spring-boot。不填则检查所有监测仓库", required = false) String repo) {
        log.info("checkNewVersionsSlim: repo={}", repo);
        return monitorService.checkNewVersionsSlim(repo);
    }

    @Tool(description = "检查 Spring 博客新文章的精简版。只返回标题、日期、链接等关键信息，"
            + "不含 HTML 解析附带的多余内容。专门用于定时任务中获取数据后交由 LLM 提炼梗概。")
    public String checkNewBlogPostsSlim() {
        log.info("checkNewBlogPostsSlim");
        return monitorService.checkNewBlogPostsSlim();
    }
}
