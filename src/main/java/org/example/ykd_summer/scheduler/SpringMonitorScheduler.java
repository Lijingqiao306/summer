package org.example.ykd_summer.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.SpringMonitorService;
import org.example.ykd_summer.tools.MailSendTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring 官网监测定时任务。
 * <p>
 * 调度器直接调用 {@link SpringMonitorService} 获取数据，构建 HTML 日报邮件发送，
 * 不再依赖 LLM Agent 生成邮件内容（LLM Agent 保留给用户交互场景使用）。
 */
@Slf4j
@Component
public class SpringMonitorScheduler {

    private final SpringMonitorService monitorService;
    private final MailSendTools mailSendTools;

    @Value("${spring.mail.username}")
    private String senderUsername;

    @Value("${spring-monitor.simulated-date:}")
    private String simulatedDate;

    public SpringMonitorScheduler(SpringMonitorService monitorService,
                                   MailSendTools mailSendTools) {
        this.monitorService = monitorService;
        this.mailSendTools = mailSendTools;
    }

    /**
     * 定时执行：采集数据 → 构建 HTML 日报 → 发送邮件。
     * cron 表达式通过 application.yml 中的 spring-monitor.cron 配置。
     */
    @Scheduled(cron = "${spring-monitor.cron}")
    public void dailySpringUpdateCheck() {
        String today = isSimulatedDate()
                ? simulatedDate.trim()
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        log.info("=== Spring 监测定时任务开始（{}，模拟={}）===", today, isSimulatedDate());

        String selfQq = extractQqNumber(senderUsername);
        if (selfQq == null) {
            log.warn("无法从 spring.mail.username 解析出 QQ 号，跳过邮件推送。username={}", senderUsername);
            return;
        }

        try {
            List<String> dimensions = monitorService.getMonitoringDimensions();
            boolean checkReleases = dimensions.contains("releases");
            boolean checkBlogs = dimensions.contains("blogs");
            log.info("监测配置: 维度={}, 版本={}, 博客={}", dimensions, checkReleases, checkBlogs);

            // 1. 采集数据
            String releasesText = null;
            String blogText = null;
            if (checkReleases) {
                releasesText = monitorService.checkNewVersionsSlim(null);
            }
            if (checkBlogs) {
                blogText = monitorService.checkNewBlogPostsSlim();
            }

            // 2. 解析数据
            Map<String, List<ReleaseItem>> releaseGroups = parseReleaseSlim(releasesText);
            List<BlogItem> blogItems = parseBlogSlim(blogText);

            boolean hasReleases = !releaseGroups.isEmpty();
            boolean hasBlogs = !blogItems.isEmpty();
            boolean hasAnyUpdate = hasReleases || hasBlogs;

            // 3. 构建 HTML 邮件
            String htmlContent = buildReportHtml(today, checkReleases, checkBlogs,
                    hasReleases, hasBlogs, releaseGroups, blogItems);

            // 4. 发送邮件
            String title = hasAnyUpdate
                    ? "Spring 更新报告 - " + today
                    : "Spring 更新报告 - " + today + "（今日无更新）";
            String result = mailSendTools.sendHtmlMail(selfQq, title, htmlContent, null, null);

            log.info("Spring 监测日报发送完成: qq={}, date={}, hasUpdate={}, result={}",
                    selfQq, today, hasAnyUpdate, result);
        } catch (Exception e) {
            log.error("Spring 监测定时任务执行异常", e);
        }

        log.info("=== Spring 监测定时任务结束（{}）===", today);
    }

    // ==================== HTML 构建 ====================

    /**
     * 构建 HTML 格式的 Spring 监测日报邮件。
     */
    private String buildReportHtml(String date,
                                    boolean checkReleases, boolean checkBlogs,
                                    boolean hasReleases, boolean hasBlogs,
                                    Map<String, List<ReleaseItem>> releaseGroups,
                                    List<BlogItem> blogItems) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<style>\n");
        html.append("  body { font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif; "
                + "background: #f5f6fa; margin: 0; padding: 20px; color: #2d3436; }\n");
        html.append("  .container { max-width: 650px; margin: 0 auto; "
                + "background: #fff; border-radius: 12px; "
                + "box-shadow: 0 2px 12px rgba(0,0,0,0.08); overflow: hidden; }\n");
        html.append("  .header { background: linear-gradient(135deg, #6db33f 0%, #4a8c2a 100%); "
                + "color: #fff; padding: 28px 30px; text-align: center; }\n");
        html.append("  .header h1 { margin: 0; font-size: 22px; font-weight: 600; }\n");
        html.append("  .header .date { margin-top: 6px; font-size: 13px; opacity: 0.85; }\n");
        html.append("  .body { padding: 24px 30px; }\n");
        html.append("  .section { margin-bottom: 28px; }\n");
        html.append("  .section-title { font-size: 16px; font-weight: 600; color: #6db33f; "
                + "border-left: 3px solid #6db33f; padding-left: 10px; margin-bottom: 14px; }\n");
        html.append("  .repo-group { background: #fafafa; border-radius: 8px; "
                + "padding: 14px 18px; margin-bottom: 10px; }\n");
        html.append("  .repo-name { font-size: 14px; font-weight: 600; color: #2d3436; "
                + "margin-bottom: 8px; }\n");
        html.append("  .release-item { display: flex; align-items: center; "
                + "padding: 6px 0; font-size: 13px; border-bottom: 1px solid #f0f0f0; }\n");
        html.append("  .release-item:last-child { border-bottom: none; }\n");
        html.append("  .release-tag { display: inline-block; background: #e8f5e0; "
                + "color: #4a8c2a; padding: 2px 8px; border-radius: 4px; "
                + "font-size: 12px; font-weight: 600; font-family: monospace; "
                + "margin-right: 10px; white-space: nowrap; flex-shrink: 0; }\n");
        html.append("  .release-tag.prerelease { background: #fff3e0; color: #e67e22; }\n");
        html.append("  .release-name { flex: 1; color: #2d3436; min-width: 0; "
                + "overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n");
        html.append("  .release-date { color: #b2bec3; font-size: 11px; "
                + "margin: 0 12px; white-space: nowrap; flex-shrink: 0; }\n");
        html.append("  .release-link { color: #0984e3; text-decoration: none; "
                + "font-size: 12px; white-space: nowrap; flex-shrink: 0; }\n");
        html.append("  .release-link:hover { text-decoration: underline; }\n");
        html.append("  .blog-item { padding: 10px 0; border-bottom: 1px solid #f0f0f0; }\n");
        html.append("  .blog-item:last-child { border-bottom: none; }\n");
        html.append("  .blog-title { font-size: 14px; font-weight: 500; }\n");
        html.append("  .blog-title a { color: #0984e3; text-decoration: none; }\n");
        html.append("  .blog-title a:hover { text-decoration: underline; }\n");
        html.append("  .blog-date { font-size: 11px; color: #b2bec3; margin-top: 3px; }\n");
        html.append("  .no-data { color: #b2bec3; font-size: 13px; "
                + "font-style: italic; }\n");
        html.append("  .summary-badge { background: #e8f5e0; color: #4a8c2a; "
                + "border-radius: 12px; padding: 8px 16px; font-size: 13px; "
                + "text-align: center; margin-bottom: 10px; }\n");
        html.append("  .footer { background: #fafafa; padding: 16px 30px; "
                + "text-align: center; font-size: 11px; color: #b2bec3; "
                + "border-top: 1px solid #eee; }\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");

        /* ===== Header ===== */
        html.append("<div class=\"header\">\n");
        html.append("  <h1>Spring 监测日报</h1>\n");
        html.append("  <div class=\"date\">").append(date).append("</div>\n");
        html.append("</div>\n");

        /* ===== Body ===== */
        html.append("<div class=\"body\">\n");

        boolean hasAnyUpdate = hasReleases || hasBlogs;

        if (!hasAnyUpdate) {
            // 完全无更新
            html.append("<div class=\"summary-badge\">今日无新增 Release 或博客文章，所有监测项目均为最新状态。</div>\n");
        }

        // Section: 版本更新
        if (checkReleases) {
            html.append("<div class=\"section\">\n");
            html.append("  <div class=\"section-title\">版本更新</div>\n");
            if (hasReleases) {
                for (Map.Entry<String, List<ReleaseItem>> entry : releaseGroups.entrySet()) {
                    String repoName = repoShortName(entry.getKey());
                    List<ReleaseItem> items = entry.getValue();
                    html.append("  <div class=\"repo-group\">\n");
                    html.append("    <div class=\"repo-name\">").append(escapeHtml(repoName))
                            .append("（").append(items.size()).append(" 个新版本）</div>\n");
                    for (ReleaseItem item : items) {
                        String tagClass = item.prerelease ? "release-tag prerelease" : "release-tag";
                        String tagLabel = item.prerelease ? item.tagName + " [预发布]" : item.tagName;
                        html.append("    <div class=\"release-item\">\n");
                        html.append("      <span class=\"").append(tagClass).append("\">")
                                .append(escapeHtml(tagLabel)).append("</span>\n");
                        html.append("      <span class=\"release-name\" title=\"")
                                .append(escapeHtml(item.name)).append("\">")
                                .append(escapeHtml(item.name)).append("</span>\n");
                        html.append("      <span class=\"release-date\">")
                                .append(escapeHtml(item.date)).append("</span>\n");
                        html.append("      <a class=\"release-link\" href=\"")
                                .append(escapeHtml(item.url)).append("\" target=\"_blank\">查看</a>\n");
                        html.append("    </div>\n");
                    }
                    html.append("  </div>\n");
                }
            } else {
                html.append("  <div class=\"no-data\">无</div>\n");
            }
            html.append("</div>\n");
        }

        // Section: 博客更新
        if (checkBlogs) {
            html.append("<div class=\"section\">\n");
            html.append("  <div class=\"section-title\">博客更新</div>\n");
            if (hasBlogs) {
                for (BlogItem item : blogItems) {
                    html.append("  <div class=\"blog-item\">\n");
                    html.append("    <div class=\"blog-title\">"
                            + "<a href=\"").append(escapeHtml(item.url))
                            .append("\" target=\"_blank\">")
                            .append(escapeHtml(item.title)).append("</a></div>\n");
                    html.append("    <div class=\"blog-date\">").append(escapeHtml(item.date))
                            .append("</div>\n");
                    html.append("  </div>\n");
                }
            } else {
                html.append("  <div class=\"no-data\">无</div>\n");
            }
            html.append("</div>\n");
        }

        html.append("</div>\n"); // end body

        /* ===== Footer ===== */
        html.append("<div class=\"footer\">\n");
        html.append("  Spring 监测日报 · 自动推送 · Generated by ykd_summer\n");
        html.append("</div>\n");

        html.append("</div>\n"); // end container
        html.append("</body>\n</html>");

        return html.toString();
    }

    // ==================== 数据解析 ====================

    /**
     * 解析 {@code checkNewVersionsSlim} 返回的文本，结构化提取 Release 信息。
     * <p>
     * 输入格式：
     * <pre>
     * [spring-projects/spring-boot]
     * v4.1.0 | Spring Boot 4.1.0 | 2026-07-29 | https://...
     * v4.0.7 [预发布] | Spring Boot 4.0.7 RC1 | 2026-07-28 | https://...
     *
     * [spring-projects/spring-framework]
     * v7.0.8 | Spring Framework 7.0.8 | 2026-07-28 | https://...
     * </pre>
     */
    private static Map<String, List<ReleaseItem>> parseReleaseSlim(String text) {
        Map<String, List<ReleaseItem>> groups = new LinkedHashMap<>();
        if (text == null || text.isBlank() || text.contains("无新版本")) {
            return groups;
        }

        String currentRepo = null;
        for (String line : text.split("\\R")) {  // \\R matches any line break
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("[") && line.endsWith("]")) {
                currentRepo = line.substring(1, line.length() - 1);
                groups.putIfAbsent(currentRepo, new ArrayList<>());
            } else if (currentRepo != null && line.contains(" | ")) {
                String[] parts = line.split(" \\| ");
                if (parts.length >= 4) {
                    String tagPart = parts[0].trim();
                    boolean prerelease = tagPart.contains("[预发布]");
                    String tagName = tagPart.replace(" [预发布]", "").replace(" [预发布]", "").trim();
                    groups.get(currentRepo).add(new ReleaseItem(
                            tagName, parts[1].trim(), parts[2].trim(), parts[3].trim(), prerelease));
                }
            }
        }
        return groups;
    }

    /**
     * 解析 {@code checkNewBlogPostsSlim} 返回的文本，结构化提取博客信息。
     * <p>
     * 输入格式：
     * <pre>
     * This Week in Spring | 2026-07-28 | https://spring.io/blog/...
     * Spring AI 1.2.0 正式发布 | 2026-07-27 | https://spring.io/blog/...
     * </pre>
     */
    private static List<BlogItem> parseBlogSlim(String text) {
        List<BlogItem> items = new ArrayList<>();
        if (text == null || text.isBlank() || text.contains("没有新文章")) {
            return items;
        }

        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains(" | ")) continue;

            String[] parts = line.split(" \\| ");
            if (parts.length >= 3) {
                items.add(new BlogItem(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        }
        return items;
    }

    // ==================== 工具方法 ====================

    /**
     * 从仓库全名中提取短名（spring-projects/spring-boot → spring-boot）。
     */
    private static String repoShortName(String fullName) {
        int idx = fullName.indexOf('/');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    /**
     * HTML 实体转义。
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private boolean isSimulatedDate() {
        return simulatedDate != null && !simulatedDate.isBlank();
    }

    private static String extractQqNumber(String username) {
        if (username == null || username.isBlank()) return null;
        String cleaned = username.trim();
        int atIdx = cleaned.indexOf('@');
        if (atIdx >= 0) {
            cleaned = cleaned.substring(0, atIdx);
        }
        return cleaned.matches("\\d{5,12}") ? cleaned : null;
    }

    // ==================== 数据模型 ====================

    /** Release 数据项。 */
    private record ReleaseItem(String tagName, String name, String date, String url, boolean prerelease) {}

    /** 博客数据项。 */
    private record BlogItem(String title, String date, String url) {}
}
