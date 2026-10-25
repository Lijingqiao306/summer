package org.example.ykd_summer.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.ykd_summer.dto.BlogPost;
import org.example.ykd_summer.dto.GithubRelease;
import org.example.ykd_summer.service.SpringMonitorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spring 官网监测服务实现。
 */
@Slf4j
@Service
public class SpringMonitorServiceImpl implements SpringMonitorService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${spring-monitor.github-api-base:https://api.github.com}")
    private String githubApiBase;

    @Value("${spring-monitor.mirror-url:https://ghproxy.com}")
    private String mirrorUrl;

    @Value("${spring-monitor.blog-url:https://spring.io/blog}")
    private String blogUrl;

    @Value("${spring-monitor.lookback-hours:48}")
    private int lookbackHours;

    @Value("${spring-monitor.simulated-date:}")
    private String simulatedDate;

    @Value("${spring-monitor.monitor-repos:spring-projects/spring-boot,spring-projects/spring-framework,spring-projects/spring-cloud,spring-projects/spring-security,spring-projects/spring-ai,spring-projects/spring-data-commons,spring-projects/spring-batch}")
    private String monitorReposStr;

    private List<String> monitorRepos;
    private WatchData watchData;
    private static final Path WATCH_DATA_PATH = Path.of("./data/spring-watch.json");

    public SpringMonitorServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @PostConstruct
    public void init() {
        this.watchData = loadWatchData();

        if (watchData.monitorRepos != null && !watchData.monitorRepos.isEmpty()) {
            this.monitorRepos = watchData.monitorRepos;
        } else {
            this.monitorRepos = Arrays.stream(monitorReposStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        if (watchData.monitoringDimensions == null || watchData.monitoringDimensions.isEmpty()) {
            watchData.monitoringDimensions = List.of("releases", "blogs");
        }
        log.info("Spring 监测服务初始化完成: 镜像={}, 监测仓库={}, 维度={}",
                mirrorUrl, monitorRepos, watchData.monitoringDimensions);
    }

    // ==================== Release 查询 ====================

    @Override
    public String getReleases(String repo, int count) {
        String url = buildApiUrl("/repos/" + repo + "/releases?per_page=" + Math.min(count, 30));
        String body = httpGet(url);
        if (body == null) return errorResult("GitHub API 请求失败（仓库: " + repo + "）");

        List<GithubRelease> releases = parseReleases(body);
        if (releases.isEmpty()) return "仓库 " + repo + " 没有找到 Release 记录。";

        return formatReleases(releases, repo);
    }

    @Override
    public String checkNewVersions(String repo) {
        List<String> reposToCheck;
        if (repo != null && !repo.isBlank()) {
            reposToCheck = List.of(repo.trim());
        } else {
            reposToCheck = monitorRepos;
        }

        StringBuilder result = new StringBuilder("## Spring 项目版本检查「");
        result.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        result.append("」\n\n");
        boolean hasNew = false;

        for (String r : reposToCheck) {
            String url = buildApiUrl("/repos/" + r + "/releases?per_page=5");
            String body = httpGet(url);
            if (body == null) {
                result.append("- **").append(repoShortName(r)).append("**: API 请求失败\n");
                continue;
            }

            List<GithubRelease> releases = parseReleases(body);
            Set<String> seen = new LinkedHashSet<>(
                    watchData.seenReleases.getOrDefault(r, List.of()));
            List<GithubRelease> newReleases = releases.stream()
                    .filter(rel -> !seen.contains(rel.tagName()) && !rel.draft() && isWithinLookback(rel.publishedAt()))
                    .toList();

            if (newReleases.isEmpty()) {
                String latestTag = releases.isEmpty() ? "N/A" : releases.get(0).tagName();
                result.append("- **").append(repoShortName(r)).append("**: 无新版本（最新: ")
                        .append(latestTag).append("）\n");
            } else {
                hasNew = true;
                result.append("- **").append(repoShortName(r)).append("**: ")
                        .append(newReleases.size()).append(" 个新版本\n");
                for (GithubRelease rel : newReleases) {
                    result.append("  - ").append(formatSingleRelease(rel)).append("\n");
                }
                List<String> allTags = new ArrayList<>();
                releases.forEach(rel -> allTags.add(rel.tagName()));
                watchData.seenReleases.put(r, allTags);
            }
        }

        if (hasNew) {
            watchData.lastCheckTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            saveWatchData();
            result.append("\n> 缓存已更新");
        }

        return result.toString();
    }

    @Override
    public String getReleaseDetail(String repo, String tag) {
        String url = buildApiUrl("/repos/" + repo + "/releases/tags/" + tag);
        String body = httpGet(url);
        if (body == null) return errorResult("获取 Release 详情失败（仓库: " + repo + ", tag: " + tag + "）");

        try {
            GithubRelease release = objectMapper.readValue(body, GithubRelease.class);
            return formatReleaseDetail(release, repo);
        } catch (Exception e) {
            log.error("解析 Release 详情失败: repo={}, tag={}", repo, tag, e);
            return errorResult("解析 Release 详情失败: " + e.getMessage());
        }
    }

    @Override
    public String searchReleases(String repo, String keyword) {
        String url = buildApiUrl("/repos/" + repo + "/releases?per_page=30");
        String body = httpGet(url);
        if (body == null) return errorResult("搜索请求失败（仓库: " + repo + "）");

        List<GithubRelease> releases = parseReleases(body);
        String kw = keyword.toLowerCase();
        List<GithubRelease> matched = releases.stream()
                .filter(r -> containsIgnoreCase(r.name(), kw)
                        || containsIgnoreCase(r.tagName(), kw)
                        || containsIgnoreCase(r.body(), kw))
                .toList();

        if (matched.isEmpty()) {
            return "在 " + repo + " 的 Release 中未找到包含「" + keyword + "」的结果。";
        }
        return formatReleases(matched, repo);
    }

    // ==================== 精简查询 ====================

    @Override
    public String checkNewVersionsSlim(String repo) {
        List<String> reposToCheck;
        if (repo != null && !repo.isBlank()) {
            reposToCheck = List.of(repo.trim());
        } else {
            reposToCheck = monitorRepos;
        }
        log.debug("checkNewVersionsSlim: 仓库列表={}, simulatedDate={}, lookbackHours={}, referenceInstant={}",
                reposToCheck, simulatedDate, lookbackHours, getReferenceInstant());

        StringBuilder result = new StringBuilder();
        boolean hasNew = false;

        for (String r : reposToCheck) {
            String url = buildApiUrl("/repos/" + r + "/releases?per_page=5");
            String body = httpGet(url);
            if (body == null) {
                log.debug("checkNewVersionsSlim: {} HTTP GET 返回 null", r);
                continue;
            }

            List<GithubRelease> releases = parseReleases(body);
            log.debug("checkNewVersionsSlim: {} 获取到 {} 个 release", r, releases.size());
            Set<String> seen = new LinkedHashSet<>(
                    watchData.seenReleases.getOrDefault(r, List.of()));
            List<GithubRelease> newReleases = releases.stream()
                    .filter(rel -> {
                        boolean notSeen = !seen.contains(rel.tagName());
                        boolean notDraft = !rel.draft();
                        boolean inWindow = isWithinLookback(rel.publishedAt());
                        if (log.isDebugEnabled()) {
                            log.debug("  {}: tag={} publishedAt={} notSeen={} notDraft={} inWindow={}",
                                    r, rel.tagName(), rel.publishedAt(), notSeen, notDraft, inWindow);
                        }
                        return notSeen && notDraft && inWindow;
                    })
                    .toList();

            if (!newReleases.isEmpty()) {
                hasNew = true;
                if (!result.isEmpty()) result.append("\n");
                result.append("[").append(r).append("]\n");
                for (GithubRelease rel : newReleases) {
                    String date = rel.publishedAt() != null && rel.publishedAt().length() >= 10
                            ? rel.publishedAt().substring(0, 10) : "";
                    String flags = rel.prerelease() ? " [预发布]" : "";
                    result.append(rel.tagName()).append(flags)
                            .append(" | ").append(rel.name())
                            .append(" | ").append(date)
                            .append(" | ").append(rel.htmlUrl())
                            .append("\n");
                }
                List<String> allTags = new ArrayList<>();
                releases.forEach(rel -> allTags.add(rel.tagName()));
                watchData.seenReleases.put(r, allTags);
            }
        }

        if (hasNew) {
            watchData.lastCheckTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            saveWatchData();
        } else {
            result.append("所有监测仓库均无新版本发布。");
        }

        return result.toString();
    }

    @Override
    public String checkNewBlogPostsSlim() {
        if (!isDimensionEnabled("blogs")) {
            return "博客监测未启用（当前监测维度: " + watchData.monitoringDimensions + "）。";
        }
        log.debug("checkNewBlogPostsSlim: 开始抓取博客，url={}, simulatedDate={}", blogUrl, simulatedDate);
        String html = httpGet(blogUrl);
        if (html == null) {
            log.warn("checkNewBlogPostsSlim: HTTP GET 返回 null");
            return "获取 Spring 博客页面失败。";
        }
        log.debug("checkNewBlogPostsSlim: HTML 获取成功，长度={}", html.length());

        List<BlogPost> posts = parseBlogHtml(html, 10);
        log.debug("checkNewBlogPostsSlim: 解析到 {} 篇文章", posts.size());
        for (BlogPost p : posts) {
            log.debug("  - {} | date={} | url={}", p.title(), p.date(), p.url());
        }
        if (posts.isEmpty()) return "未从 Spring 博客页面解析到文章。";

        Set<String> seen = new LinkedHashSet<>(watchData.seenBlogPosts);
        log.debug("checkNewBlogPostsSlim: seenBlogPosts 缓存={}, lookbackHours={}, referenceDate={}",
                seen, lookbackHours, getReferenceDate());
        List<BlogPost> newPosts = posts.stream()
                .filter(p -> {
                    boolean notSeen = !seen.contains(p.url());
                    boolean inWindow = isWithinLookbackDate(p.date());
                    log.debug("  过滤: {} | date={} | notSeen={} | inWindow={}", p.title(), p.date(), notSeen, inWindow);
                    return notSeen && inWindow;
                })
                .toList();

        log.debug("checkNewBlogPostsSlim: 过滤后 {} 篇新文章", newPosts.size());
        if (newPosts.isEmpty()) {
            return "Spring 博客没有新文章。";
        }

        StringBuilder sb = new StringBuilder();
        for (BlogPost post : newPosts) {
            sb.append(post.title())
                    .append(" | ").append(post.date())
                    .append(" | ").append(post.url())
                    .append("\n");
        }

        posts.forEach(p -> watchData.seenBlogPosts.add(p.url()));
        watchData.lastCheckTime = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveWatchData();

        String result = sb.toString();
        log.info("checkNewBlogPostsSlim: 返回结果 = {}", result);
        return result;
    }

    // ==================== Blog 查询 ====================

    @Override
    public String getBlogPosts(int maxCount) {
        String html = httpGet(blogUrl);
        if (html == null) return errorResult("获取 Spring 博客页面失败");

        List<BlogPost> posts = parseBlogHtml(html, maxCount);
        if (posts.isEmpty()) return "未从 Spring 博客页面解析到文章，页面结构可能已变化。";

        StringBuilder sb = new StringBuilder("## Spring 官方博客最新文章\n\n");
        for (int i = 0; i < posts.size(); i++) {
            BlogPost post = posts.get(i);
            sb.append(i + 1).append(". **").append(post.title()).append("**\n");
            sb.append("   - 日期: ").append(post.date()).append("\n");
            sb.append("   - 链接: ").append(post.url()).append("\n");
            if (post.summary() != null && !post.summary().isBlank()) {
                sb.append("   - 摘要: ").append(post.summary()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public String checkNewBlogPosts() {
        String html = httpGet(blogUrl);
        if (html == null) return errorResult("获取 Spring 博客页面失败");

        List<BlogPost> posts = parseBlogHtml(html, 10);
        if (posts.isEmpty()) return "未从 Spring 博客页面解析到文章。";

        Set<String> seen = new LinkedHashSet<>(watchData.seenBlogPosts);
        List<BlogPost> newPosts = posts.stream()
                .filter(p -> !seen.contains(p.url()) && isWithinLookbackDate(p.date()))
                .toList();

        if (newPosts.isEmpty()) {
            return "Spring 博客没有新文章。";
        }

        StringBuilder sb = new StringBuilder("## Spring 博客新文章（")
                .append(newPosts.size()).append(" 篇）\n\n");
        for (BlogPost post : newPosts) {
            sb.append("- **").append(post.title()).append("** (").append(post.date())
                    .append(")\n  ").append(post.url()).append("\n\n");
        }

        posts.forEach(p -> watchData.seenBlogPosts.add(p.url()));
        watchData.lastCheckTime = LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveWatchData();

        return sb.toString();
    }

    // ==================== 缓存管理 ====================

    @Override
    public String forceRefreshAll() {
        watchData.seenReleases.clear();
        watchData.seenBlogPosts.clear();
        watchData.lastCheckTime = null;
        saveWatchData();
        log.info("Spring 监测缓存已全部清除");
        return "已清除所有 Spring 监测缓存，下次检查将重新获取最新数据。";
    }

    // ==================== 监测范围管理 ====================

    @Override
    public String updateMonitorScope(List<String> repos) {
        if (repos == null || repos.isEmpty()) {
            return "监测范围不能为空，请至少指定一个仓库。";
        }
        this.monitorRepos = List.copyOf(repos);
        watchData.monitorRepos = this.monitorRepos;
        saveWatchData();
        log.info("监测范围已更新: {}", monitorRepos);
        return "已更新监测范围: " + monitorRepos;
    }

    @Override
    public String getMonitorScope() {
        return "当前监测范围: " + monitorRepos + "\n"
                + "（共 " + monitorRepos.size() + " 个仓库）";
    }

    @Override
    public String resetMonitorScope() {
        List<String> defaultRepos = Arrays.stream(monitorReposStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        this.monitorRepos = defaultRepos;
        watchData.monitorRepos = null;
        saveWatchData();
        log.info("监测范围已重置为默认: {}", defaultRepos);
        return "已重置为默认监测范围: " + defaultRepos;
    }

    @Override
    public String setMonitoringDimensions(List<String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "监测维度不能为空，可选项: releases, blogs";
        }
        List<String> normalized = dimensions.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(d -> d.equals("releases") || d.equals("blogs"))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return "未识别的监测维度，可选项: releases, blogs。输入: " + dimensions;
        }
        watchData.monitoringDimensions = normalized;
        saveWatchData();
        log.info("监测维度已更新: {}", normalized);
        return "已更新监测维度: " + normalized;
    }

    @Override
    public String getMonitoringConfig() {
        return "当前监测配置:\n"
                + "- 仓库范围: " + monitorRepos + "（共 " + monitorRepos.size() + " 个）\n"
                + "- 监测维度: " + watchData.monitoringDimensions + "\n"
                + "- 回溯时长: " + lookbackHours + " 小时\n"
                + "- 模拟日期: " + (simulatedDate != null && !simulatedDate.isBlank() ? simulatedDate : "未启用");
    }

    @Override
    public List<String> getMonitoringDimensions() {
        return watchData.monitoringDimensions != null
                ? List.copyOf(watchData.monitoringDimensions) : List.of("releases", "blogs");
    }

    @Override
    public boolean isDimensionEnabled(String dimension) {
        return watchData.monitoringDimensions != null
                && watchData.monitoringDimensions.contains(dimension.toLowerCase());
    }

    // ==================== 内部 HTTP ====================

    private String buildApiUrl(String path) {
        String fullUrl = githubApiBase + path;
        if (mirrorUrl != null && !mirrorUrl.isBlank()) {
            String base = mirrorUrl;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/" + fullUrl;
        }
        return fullUrl;
    }

    private String httpGet(String url) {
        try {
            Request request = new Request.Builder().url(url).get()
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SpringMonitor/1.0")
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("HTTP 请求失败: url={}, code={}", url, response.code());
                    return null;
                }
                return response.body() != null ? response.body().string() : "";
            }
        } catch (Exception e) {
            log.warn("HTTP 请求异常: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    // ==================== Release 解析与格式化 ====================

    private List<GithubRelease> parseReleases(String body) {
        try {
            GithubRelease[] arr = objectMapper.readValue(body, GithubRelease[].class);
            return Arrays.asList(arr);
        } catch (Exception e) {
            log.error("解析 Release 列表 JSON 失败", e);
            return List.of();
        }
    }

    private String formatReleases(List<GithubRelease> releases, String repo) {
        StringBuilder sb = new StringBuilder("## ").append(repo).append(" 最新 Release\n\n");
        for (int i = 0; i < releases.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(formatSingleRelease(releases.get(i)));
        }
        return sb.toString();
    }

    private String formatSingleRelease(GithubRelease r) {
        String flags = "";
        if (r.prerelease()) flags = " [预发布]";
        if (r.draft()) flags = " [草稿]";
        String date = r.publishedAt() != null ? "T".equals(r.publishedAt()) ? "" : r.publishedAt().substring(0, 10) : "";
        return "**" + r.tagName() + "**" + flags + (date.isEmpty() ? "" : " (" + date + ")") + " - " + r.htmlUrl();
    }

    private String formatReleaseDetail(GithubRelease r, String repo) {
        StringBuilder sb = new StringBuilder("## ").append(repo).append(" **")
                .append(r.tagName()).append("**\n\n");
        if (r.prerelease()) sb.append("> ⚠️ 预发布版本\n\n");
        if (r.draft()) sb.append("> 📝 草稿版本\n\n");
        sb.append("- 发布日期: ").append(r.publishedAt() != null ? r.publishedAt() : "未知").append("\n");
        sb.append("- 链接: ").append(r.htmlUrl()).append("\n\n");
        sb.append("### Release Notes\n\n");
        String body = r.body();
        if (body != null && !body.isBlank()) {
            if (body.length() > 3000) {
                body = body.substring(0, 3000) + "\n\n...（内容过长已截断，完整内容请查看上方链接）";
            }
            sb.append(body);
        } else {
            sb.append("（无 Release Notes）");
        }
        return sb.toString();
    }

    private static boolean containsIgnoreCase(String str, String keyword) {
        return str != null && str.toLowerCase().contains(keyword);
    }

    private static String repoShortName(String fullName) {
        int idx = fullName.indexOf('/');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    private static String errorResult(String msg) {
        return "【" + msg + "】";
    }

    // ==================== 时间窗口过滤 ====================

    /**
     * 获取时间窗口的参考基准时间。模拟日期非空时使用模拟日期，否则使用真实时间。
     */
    private Instant getReferenceInstant() {
        if (simulatedDate != null && !simulatedDate.isBlank()) {
            return LocalDate.parse(simulatedDate.trim()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
        return Instant.now();
    }

    private LocalDate getReferenceDate() {
        if (simulatedDate != null && !simulatedDate.isBlank()) {
            return LocalDate.parse(simulatedDate.trim());
        }
        return LocalDate.now();
    }

    private boolean isWithinLookback(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) return false;
        try {
            Instant published = Instant.parse(publishedAt);
            Instant now = getReferenceInstant();
            Instant threshold = now.minus(Duration.ofHours(lookbackHours));
            return published.isAfter(threshold) || published.equals(threshold);
        } catch (DateTimeParseException e) {
            log.debug("Release publishedAt 时间解析失败: {}", publishedAt);
            return false;
        }
    }

    private boolean isWithinLookbackDate(String date) {
        if (date == null || date.isBlank()) return false;
        try {
            LocalDate blogDate;
            if (date.length() >= 10) {
                blogDate = LocalDate.parse(date.substring(0, 10));
            } else {
                blogDate = LocalDate.parse(date);
            }
            LocalDate now = getReferenceDate();
            LocalDate threshold = now.minusDays(lookbackHours / 24);
            return !blogDate.isBefore(threshold);
        } catch (DateTimeParseException e) {
            log.debug("博客日期解析失败: {}", date);
            return false;
        }
    }

    // ==================== Blog HTML 解析 ====================

    private List<BlogPost> parseBlogHtml(String html, int maxCount) {
        List<BlogPost> posts = new ArrayList<>();

        Pattern articlePattern = Pattern.compile(
                "<article[^>]*>(.*?)</article>",
                Pattern.DOTALL);
        Matcher articleMatcher = articlePattern.matcher(html);

        while (articleMatcher.find() && posts.size() < maxCount) {
            String articleHtml = articleMatcher.group(1);
            BlogPost post = extractPostFromBlock(articleHtml);
            if (post != null) posts.add(post);
        }

        if (posts.isEmpty()) {
            Pattern linkPattern = Pattern.compile(
                    "<a[^>]*href=\"(/blog/\\d{4}/\\d{2}/\\d{2}/[^\"]+)\"[^>]*>(.*?)</a>",
                    Pattern.DOTALL);
            Matcher linkMatcher = linkPattern.matcher(html);

            while (linkMatcher.find() && posts.size() < maxCount) {
                String url = linkMatcher.group(1);
                String title = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                if (title.length() > 3 && !posts.stream().anyMatch(p -> p.url().equals(url))) {
                    String fullUrl = url.startsWith("http") ? url : "https://spring.io" + url;
                    String date = extractDateFromUrl(url);
                    posts.add(new BlogPost(title, fullUrl, date, null));
                }
            }
        }

        return posts;
    }

    private BlogPost extractPostFromBlock(String html) {
        Pattern titlePattern = Pattern.compile(
                "<(?:h[1-4]|a)[^>]*>(.*?)</(?:h[1-4]|a)>", Pattern.DOTALL);
        Matcher titleMatcher = titlePattern.matcher(html);
        String title = null;
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).replaceAll("<[^>]+>", "").trim();
        }

        Pattern urlPattern = Pattern.compile(
                "href=\"(/blog/\\d{4}/\\d{2}/\\d{2}/[^\"]+)\"");
        Matcher urlMatcher = urlPattern.matcher(html);
        String url = null;
        if (urlMatcher.find()) {
            url = urlMatcher.group(1);
        }

        Pattern datePattern = Pattern.compile(
                "<time[^>]*datetime=\"([^\"]+)\"[^>]*>|<time[^>]*>(\\d{4}-\\d{2}-\\d{2})</time>");
        Matcher dateMatcher = datePattern.matcher(html);
        String date = null;
        if (dateMatcher.find()) {
            date = dateMatcher.group(1) != null ? dateMatcher.group(1) : dateMatcher.group(2);
        }
        if (date == null && url != null) {
            date = extractDateFromUrl(url);
        }

        Pattern summaryPattern = Pattern.compile(
                "<p[^>]*>(.{20,200}?)</p>", Pattern.DOTALL);
        Matcher summaryMatcher = summaryPattern.matcher(html);
        String summary = null;
        if (summaryMatcher.find()) {
            summary = summaryMatcher.group(1).replaceAll("<[^>]+>", "").trim();
            if (summary.length() > 200) summary = summary.substring(0, 200) + "...";
        }

        if (title != null && url != null) {
            String fullUrl = url.startsWith("http") ? url : "https://spring.io" + url;
            return new BlogPost(title, fullUrl, date != null ? date : "", summary);
        }
        return null;
    }

    private static String extractDateFromUrl(String url) {
        Pattern datePattern = Pattern.compile("/blog/(\\d{4})/(\\d{2})/(\\d{2})/");
        Matcher m = datePattern.matcher(url);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3);
        }
        return "";
    }

    // ==================== 持久化 ====================

    private WatchData loadWatchData() {
        try {
            if (Files.exists(WATCH_DATA_PATH)) {
                String json = Files.readString(WATCH_DATA_PATH);
                return objectMapper.readValue(json, WatchData.class);
            }
            log.info("监测缓存文件不存在，将创建新文件: {}", WATCH_DATA_PATH);
        } catch (Exception e) {
            log.warn("加载监测缓存失败，将使用空数据: {}", e.getMessage());
        }
        return new WatchData();
    }

    private void saveWatchData() {
        try {
            Files.createDirectories(WATCH_DATA_PATH.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(watchData);
            Files.writeString(WATCH_DATA_PATH, json);
            log.debug("监测缓存已保存: size={}bytes", json.length());
        } catch (IOException e) {
            log.error("保存监测缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 本地缓存数据结构。
     */
    private static class WatchData {
        public List<String> monitorRepos;
        /** 启用的监测维度：releases / blogs。默认为两者。 */
        public List<String> monitoringDimensions;
        public Map<String, List<String>> seenReleases = new LinkedHashMap<>();
        public List<String> seenBlogPosts = new ArrayList<>();
        public String lastCheckTime;
    }
}
