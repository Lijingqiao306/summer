package org.example.ykd_summer.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.BilibiliDailyReport;
import org.example.ykd_summer.dto.BilibiliDynamicStat;
import org.example.ykd_summer.dto.BilibiliTrendingVideo;
import org.example.ykd_summer.dto.BilibiliUserInfo;
import org.example.ykd_summer.dto.BilibiliVideoStat;
import org.example.ykd_summer.dto.WeeklyTrendingAnalysis;
import org.example.ykd_summer.service.BilibiliLoginService;
import org.example.ykd_summer.service.BilibiliService;
import org.example.ykd_summer.service.TrendingAnalysisService;
import org.example.ykd_summer.tools.BilibiliStoreTools;
import org.example.ykd_summer.tools.MailSendTools;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * B站数据日报定时任务。
 * <p>
 * 每天早上 9:30 自动采集用户粉丝数、最新视频和最新动态数据，组装日报并通过 QQ 邮件发送。
 * <p>
 * Cookie 过期时自动触发 Playwright 扫码登录流程，登录成功后继续采集并发送当日日报。
 */
@Slf4j
@Component
public class BilibiliStatScheduler {

    private final BilibiliService bilibiliService;
    private final BilibiliLoginService bilibiliLoginService;
    private final TrendingAnalysisService trendingAnalysisService;
    private final BilibiliStoreTools bilibiliStoreTools;
    private final MailSendTools mailSendTools;

    @Value("${bilibili.report-qq}")
    private String reportQq;

    public BilibiliStatScheduler(BilibiliService bilibiliService,
                                 BilibiliLoginService bilibiliLoginService,
                                 TrendingAnalysisService trendingAnalysisService,
                                 BilibiliStoreTools bilibiliStoreTools,
                                 MailSendTools mailSendTools) {
        this.bilibiliService = bilibiliService;
        this.bilibiliLoginService = bilibiliLoginService;
        this.trendingAnalysisService = trendingAnalysisService;
        this.bilibiliStoreTools = bilibiliStoreTools;
        this.mailSendTools = mailSendTools;
    }

    @Scheduled(cron = "0 30 9 * * *")
    public void dailyReport() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        log.info("=== B站数据日报定时任务开始（{}）===", today);

        if (reportQq == null || reportQq.isBlank()) {
            log.warn("bilibili.report-qq 未配置，跳过日报推送");
            return;
        }

        try {
            // 0. 检查 Cookie 有效性
            if (!bilibiliService.checkCookieValid()) {
                log.warn("B站 Cookie 已过期或无效，启动自动登录流程...");
                boolean loginOk = bilibiliLoginService.loginAndSaveCookie();
                if (loginOk) {
                    // Cookie 已更新，刷新 WBI 签名密钥
                    bilibiliService.refreshWbiKeys();
                    log.info("登录成功，继续执行数据采集");
                } else {
                    log.error("自动登录失败，本次日报将使用旧 Cookie 尝试采集（数据可能不完整）");
                }
            }

            // 1. 采集数据
            BilibiliUserInfo userInfo = bilibiliService.getUserInfo();
            BilibiliVideoStat videoStat = bilibiliService.getLatestVideoStat();
            BilibiliDynamicStat dynamicStat = bilibiliService.getLatestDynamic();

            // 1.5 周一热门视频分析与创作建议
            WeeklyTrendingAnalysis weeklyAnalysis = null;
            if (isMonday()) {
                log.info("今天是周一，开始拉取热门视频并生成创作建议...");
                try {
                    List<BilibiliTrendingVideo> trending = bilibiliService.getTrendingVideos(200);
                    if (trending != null && !trending.isEmpty()) {
                        // 检索用户创作档案
                        List<Document> userUploadHistory = bilibiliStoreTools.getUserUploadHistory(50);
                        log.info("检索到 {} 条历史创作档案", userUploadHistory.size());

                        weeklyAnalysis = trendingAnalysisService.analyze(trending, userUploadHistory);
                        log.info("周一热门分析完成: {} 个分区, 主导分区={}, AI建议={}, 用户档案={}条",
                                weeklyAnalysis.partitionDistribution().size(),
                                weeklyAnalysis.dominantPartition(),
                                weeklyAnalysis.creativeAdvice() != null ? "已生成" : "未生成",
                                userUploadHistory.size());
                    } else {
                        log.warn("热门视频数据为空，跳过周一分析");
                    }
                } catch (Exception e) {
                    log.error("周一热门分析失败（不阻断日报发送）", e);
                }
            }

            // 2. 组装日报
            BilibiliDailyReport report = BilibiliDailyReport.builder()
                    .date(today)
                    .userInfo(userInfo)
                    .videoStat(videoStat)
                    .dynamicStat(dynamicStat)
                    .build();

            // 3. 构建 HTML 邮件
            String htmlContent = buildReportHtml(report, weeklyAnalysis);

            // 4. 发送邮件
            String title = "【B站数据日报】" + today;
            mailSendTools.sendHtmlMail(reportQq, title, htmlContent, null, null);

            log.info("B站数据日报发送成功: qq={}, date={}", reportQq, today);
        } catch (Exception e) {
            log.error("B站数据日报定时任务执行异常", e);
        }

        log.info("=== B站数据日报定时任务结束（{}）===", today);
    }

    /**
     * 构建 HTML 格式的日报邮件。
     */
    private String buildReportHtml(BilibiliDailyReport report, WeeklyTrendingAnalysis weeklyAnalysis) {
        String date = report.getDate();
        BilibiliUserInfo userInfo = report.getUserInfo();
        BilibiliVideoStat videoStat = report.getVideoStat();
        BilibiliDynamicStat dynamicStat = report.getDynamicStat();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<style>\n");
        html.append("  body { font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif; "
                + "background: #f5f6fa; margin: 0; padding: 20px; color: #2d3436; }\n");
        html.append("  .container { max-width: 600px; margin: 0 auto; "
                + "background: #fff; border-radius: 12px; "
                + "box-shadow: 0 2px 12px rgba(0,0,0,0.08); overflow: hidden; }\n");
        html.append("  .header { background: linear-gradient(135deg, #fb7299 0%, #ff6b8a 100%); "
                + "color: #fff; padding: 28px 30px; text-align: center; }\n");
        html.append("  .header h1 { margin: 0; font-size: 22px; font-weight: 600; }\n");
        html.append("  .header .date { margin-top: 6px; font-size: 13px; opacity: 0.85; }\n");
        html.append("  .body { padding: 24px 30px; }\n");
        html.append("  .section { margin-bottom: 24px; }\n");
        html.append("  .section-title { font-size: 16px; font-weight: 600; color: #fb7299; "
                + "border-left: 3px solid #fb7299; padding-left: 10px; margin-bottom: 12px; }\n");
        html.append("  .follower-card { background: #fff5f7; border-radius: 8px; "
                + "padding: 16px 20px; display: flex; align-items: center; "
                + "justify-content: space-between; }\n");
        html.append("  .follower-label { font-size: 14px; color: #636e72; }\n");
        html.append("  .follower-count { font-size: 28px; font-weight: 700; "
                + "color: #fb7299; }\n");
        html.append("  .follower-count .unit { font-size: 14px; font-weight: 400; "
                + "margin-left: 2px; }\n");
        html.append("  .card { background: #fafafa; border-radius: 8px; "
                + "padding: 16px 20px; }\n");
        html.append("  .card-title { font-size: 15px; font-weight: 600; "
                + "color: #2d3436; margin-bottom: 8px; }\n");
        html.append("  .card-title a { color: #0984e3; text-decoration: none; }\n");
        html.append("  .card-content { font-size: 13px; color: #636e72; "
                + "margin-bottom: 12px; line-height: 1.6; }\n");
        html.append("  .stat-row { display: flex; flex-wrap: wrap; gap: 12px; }\n");
        html.append("  .stat-item { background: #fff; border-radius: 6px; "
                + "padding: 8px 14px; text-align: center; min-width: 70px; "
                + "box-shadow: 0 1px 3px rgba(0,0,0,0.06); }\n");
        html.append("  .stat-num { font-size: 18px; font-weight: 700; "
                + "color: #2d3436; }\n");
        html.append("  .stat-label { font-size: 11px; color: #b2bec3; margin-top: 2px; }\n");
        html.append("  .no-data { color: #b2bec3; font-size: 13px; "
                + "font-style: italic; }\n");
        /* === 周一创作建议专用样式 === */
        html.append("  .section-subtitle { font-size: 13px; font-weight: 600; "
                + "color: #4a5568; margin: 18px 0 8px; "
                + "letter-spacing: 0.3px; }\n");
        /* 分区热度柱状图 */
        html.append("  .bar-chart { background: #fafbfc; border-radius: 8px; "
                + "padding: 12px 16px; border: 1px solid #f1f3f5; }\n");
        html.append("  .bar-item { display: flex; align-items: center; "
                + "margin-bottom: 6px; font-size: 13px; }\n");
        html.append("  .bar-item:last-child { margin-bottom: 0; }\n");
        html.append("  .bar-label { width: 68px; text-align: right; "
                + "color: #4a5568; margin-right: 10px; flex-shrink: 0; "
                + "overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n");
        html.append("  .bar-track { flex: 1; background: #edf2f7; "
                + "border-radius: 5px; height: 20px; overflow: hidden; }\n");
        html.append("  .bar-fill { display: inline-block; height: 100%; "
                + "border-radius: 5px; vertical-align: top; "
                + "background: linear-gradient(90deg, #fc8181, #f687b3); "
                + "min-width: 3px; }\n");
        html.append("  .bar-val { width: 48px; text-align: right; "
                + "color: #a0aec0; font-size: 12px; margin-left: 10px; "
                + "flex-shrink: 0; }\n");
        /* AI 创作建议卡片 */
        html.append("  .advice-card { background: #fff; "
                + "border: 1px solid #fed7e2; border-left: 3px solid #fb7299; "
                + "border-radius: 8px; padding: 16px 20px; font-size: 14px; "
                + "color: #2d3436; line-height: 1.8; }\n");
        html.append("  .advice-card p { margin: 6px 0; }\n");
        html.append("  .advice-fallback { color: #a0aec0; font-size: 13px; "
                + "font-style: italic; }\n");
        html.append("  .footer { background: #fafafa; padding: 16px 30px; "
                + "text-align: center; font-size: 11px; color: #b2bec3; "
                + "border-top: 1px solid #eee; }\n");
        html.append("</style>\n</head>\n<body>\n");
        html.append("<div class=\"container\">\n");

        /* ===== Header ===== */
        html.append("<div class=\"header\">\n");
        html.append("  <h1>B站数据日报</h1>\n");
        html.append("  <div class=\"date\">").append(date).append("</div>\n");
        html.append("</div>\n");

        /* ===== Body ===== */
        html.append("<div class=\"body\">\n");

        // Section 1: 粉丝概况
        html.append("<div class=\"section\">\n");
        html.append("  <div class=\"section-title\">粉丝概况</div>\n");
        if (userInfo != null) {
            html.append("  <div class=\"follower-card\">\n");
            html.append("    <span class=\"follower-label\">")
                    .append(escapeHtml(userInfo.getName()))
                    .append(" 的粉丝数</span>\n");
            html.append("    <span class=\"follower-count\">")
                    .append(formatNumber(userInfo.getFollowerCount()))
                    .append("<span class=\"unit\"> 粉</span></span>\n");
            html.append("  </div>\n");
        } else {
            html.append("  <div class=\"no-data\">粉丝数据获取失败</div>\n");
        }
        html.append("</div>\n");

        // Section 2: 最新视频
        html.append("<div class=\"section\">\n");
        html.append("  <div class=\"section-title\">最新视频</div>\n");
        if (videoStat != null) {
            html.append("  <div class=\"card\">\n");
            html.append("    <div class=\"card-title\">"
                    + "<a href=\"").append(escapeHtml(videoStat.getUrl())).append("\">")
                    .append(escapeHtml(videoStat.getTitle())).append("</a></div>\n");
            html.append("    <div class=\"stat-row\">\n");
            html.append(buildStatItem("播放", videoStat.getPlayCount()));
            html.append(buildStatItem("点赞", videoStat.getLikeCount()));
            html.append(buildStatItem("评论", videoStat.getReplyCount()));
            html.append(buildStatItem("弹幕", videoStat.getDanmakuCount()));
            html.append("    </div>\n");
            html.append("  </div>\n");
        } else {
            html.append("  <div class=\"no-data\">暂无视频或数据获取失败</div>\n");
        }
        html.append("</div>\n");

        // Section 3: 最新动态
        html.append("<div class=\"section\">\n");
        html.append("  <div class=\"section-title\">最新动态</div>\n");
        if (dynamicStat != null) {
            html.append("  <div class=\"card\">\n");
            html.append("    <div class=\"card-title\">")
                    .append("[").append(escapeHtml(dynamicStat.getType())).append("] ")
                    .append("<a href=\"").append(escapeHtml(dynamicStat.getUrl())).append("\">查看详情</a>")
                    .append("</div>\n");
            String content = dynamicStat.getContent();
            if (content != null && !content.isBlank()) {
                html.append("    <div class=\"card-content\">")
                        .append(escapeHtml(content)).append("</div>\n");
            }
            html.append("    <div class=\"stat-row\">\n");
            html.append(buildStatItem("点赞", dynamicStat.getLikeCount()));
            html.append(buildStatItem("评论", dynamicStat.getCommentCount()));
            html.append(buildStatItem("转发", dynamicStat.getForwardCount()));
            html.append("    </div>\n");
            html.append("  </div>\n");
        } else {
            html.append("  <div class=\"no-data\">暂无动态或数据获取失败</div>\n");
        }
        html.append("</div>\n"); // Section 3: 最新动态

        // Section 4: 周一创作建议（仅周一显示）
        if (weeklyAnalysis != null) {
            html.append("<div class=\"section\">\n");
            html.append("  <div class=\"section-title\">本周创作风向建议</div>\n");

            // 4a. 分区热度分布（柱状图）
            Map<String, Integer> dist = weeklyAnalysis.partitionDistribution();
            if (dist != null && !dist.isEmpty()) {
                html.append("  <div class=\"bar-chart\">\n");
                int maxCount = dist.values().stream().mapToInt(Integer::intValue).max().orElse(1);
                int rank = 1;
                for (Map.Entry<String, Integer> e : dist.entrySet()) {
                    if (rank > 8) break;
                    int pct = maxCount > 0 ? e.getValue() * 100 / maxCount : 0;
                    html.append("    <div class=\"bar-item\">")
                            .append("<span class=\"bar-label\">").append(escapeHtml(e.getKey())).append("</span>")
                            .append("<span class=\"bar-track\"><span class=\"bar-fill\" style=\"width:")
                            .append(pct).append("%\"></span></span>")
                            .append("<span class=\"bar-val\">").append(e.getValue()).append("</span>")
                            .append("</div>\n");
                    rank++;
                }
                html.append("  </div>\n");
            }

            // 4b. AI 创作建议
            html.append("  <div class=\"section-subtitle\">创作建议</div>\n");
            html.append("  <div class=\"advice-card\">\n");
            if (weeklyAnalysis.creativeAdvice() != null && !weeklyAnalysis.creativeAdvice().isBlank()) {
                String advice = weeklyAnalysis.creativeAdvice()
                        .replace("\n", "<br>").replace("\r", "");
                html.append(advice);
            } else {
                html.append("    <div class=\"advice-fallback\">"
                        + "AI 分析暂不可用，请参考分区热度分布选择创作方向。</div>\n");
            }
            html.append("  </div>\n");
            html.append("</div>\n"); // Section 4: 创作建议
        }

        html.append("</div>\n"); // end body

        /* ===== Footer ===== */
        html.append("<div class=\"footer\">\n");
        html.append("  B站数据日报 · 每日 9:30 自动发送 · Generated by ykd_summer\n");
        html.append("</div>\n");

        html.append("</div>\n"); // end container
        html.append("</body>\n</html>");

        return html.toString();
    }

    /**
     * 构建单个统计指标 HTML。
     */
    private String buildStatItem(String label, int value) {
        return "      <div class=\"stat-item\">"
                + "<div class=\"stat-num\">" + formatNumber(value) + "</div>"
                + "<div class=\"stat-label\">" + label + "</div>"
                + "</div>\n";
    }

    /**
     * 格式化数字：>= 10000 显示为 N.N万。
     */
    private String formatNumber(int num) {
        if (num >= 10000) {
            double v = num / 10000.0;
            return String.format("%.1f万", v);
        }
        return String.valueOf(num);
    }

    /**
     * HTML 实体转义。
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 判断今天是否为周一。
     */
    private boolean isMonday() {
        return LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY;
    }
}
