package org.example.ykd_summer.service;

import java.util.List;

/**
 * Spring 官网监测服务接口。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>GitHub Release 轮询：通过镜像 URL 请求 /repos/{owner}/{repo}/releases</li>
 *   <li>Blog 监测：通过 HTTP 抓取 spring.io/blog 页面解析文章列表</li>
 *   <li>本地增量缓存：data/spring-watch.json 持久化已见版本号与博客链接</li>
 * </ul>
 */
public interface SpringMonitorService {

    // ==================== Release 查询 ====================

    /** 获取指定仓库的最近 N 个 Release。 */
    String getReleases(String repo, int count);

    /** 检查指定仓库（或全部监测仓库）是否有新版本。 */
    String checkNewVersions(String repo);

    /** 获取某个 Release 的完整信息（含 Release Notes）。 */
    String getReleaseDetail(String repo, String tag);

    /** 在 Release 中按关键词搜索。 */
    String searchReleases(String repo, String keyword);

    // ==================== 精简查询 ====================

    /** 检查新版本的精简版（供 LLM 提炼梗概使用）。 */
    String checkNewVersionsSlim(String repo);

    /** 检查新博客的精简版（供 LLM 提炼梗概使用）。 */
    String checkNewBlogPostsSlim();

    // ==================== Blog 查询 ====================

    /** 获取 Spring 官方博客最新文章。 */
    String getBlogPosts(int maxCount);

    /** 检查博客是否有新文章。 */
    String checkNewBlogPosts();

    // ==================== 缓存管理 ====================

    /** 强制刷新所有缓存数据。 */
    String forceRefreshAll();

    // ==================== 监测范围管理 ====================

    /** 修改监测范围为指定的仓库列表。 */
    String updateMonitorScope(List<String> repos);

    /** 查看当前监测范围。 */
    String getMonitorScope();

    /** 重置为默认监测范围。 */
    String resetMonitorScope();

    // ==================== 监测维度管理 ====================

    /** 设置监测维度（如 releases, blogs），决定定时任务执行哪些检查。 */
    String setMonitoringDimensions(List<String> dimensions);

    /** 查看当前完整监测配置（仓库范围、维度等）。 */
    String getMonitoringConfig();

    /** 获取当前启用的监测维度列表。 */
    List<String> getMonitoringDimensions();

    /** 判断指定维度是否已启用。 */
    boolean isDimensionEnabled(String dimension);
}
