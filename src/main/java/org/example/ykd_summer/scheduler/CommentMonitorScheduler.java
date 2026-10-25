package org.example.ykd_summer.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.BilibiliComment;
import org.example.ykd_summer.dto.BilibiliDynamicStat;
import org.example.ykd_summer.dto.BilibiliVideoStat;
import org.example.ykd_summer.dto.ModerationResult;
import org.example.ykd_summer.service.BilibiliLoginService;
import org.example.ykd_summer.service.BilibiliService;
import org.example.ykd_summer.tools.CommentModerationTool;
import org.example.ykd_summer.tools.MailSendTools;
import org.example.ykd_summer.tools.NotifiedCommentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * B站评论监控定时任务。
 * <p>
 * 每 30 分钟执行一次：
 * <ol>
 *   <li>拉取最新视频 / 动态的全部评论（分页，最多 200 条）</li>
 *   <li>自动审核：关键词 + AI 批量判定违规评论</li>
 *   <li>自动删除违规评论（限速 2 秒/条，单次最多 10 条）</li>
 *   <li>高赞评论（≥ 阈值）邮件提醒</li>
 *   <li>批量删除 ≥ 阈值时发送人工干预提醒邮件</li>
 * </ol>
 */
@Slf4j
@Component
public class CommentMonitorScheduler {

    private final BilibiliService bilibiliService;
    private final BilibiliLoginService bilibiliLoginService;
    private final MailSendTools mailSendTools;
    private final NotifiedCommentStore store;
    private final CommentModerationTool moderationTool;

    @Value("${bilibili.report-qq:}")
    private String reportQq;

    @Value("${bilibili.comment-monitor.enabled:true}")
    private boolean enabled;

    @Value("${bilibili.comment-monitor.min-likes:3}")
    private int minLikes;

    /** 批量删除阈值：达到此数量时发送人工干预邮件 */
    @Value("${bilibili.comment-monitor.moderation-alert-threshold:5}")
    private int moderationAlertThreshold;

    /** 单次最大自动删除数 */
    private static final int MAX_DELETE_PER_RUN = 10;

    /** 删除间隔（毫秒） */
    private static final long DELETE_INTERVAL_MS = 2_000;

    /** 单次拉取评论上限 */
    private static final int MAX_COMMENTS_PER_SOURCE = 200;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public CommentMonitorScheduler(BilibiliService bilibiliService,
                                   BilibiliLoginService bilibiliLoginService,
                                   MailSendTools mailSendTools,
                                   NotifiedCommentStore store,
                                   CommentModerationTool moderationTool) {
        this.bilibiliService = bilibiliService;
        this.bilibiliLoginService = bilibiliLoginService;
        this.mailSendTools = mailSendTools;
        this.store = store;
        this.moderationTool = moderationTool;
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void monitorComments() {
        if (!enabled) {
            log.debug("评论监控已禁用，跳过");
            return;
        }
        if (reportQq == null || reportQq.isBlank()) {
            log.warn("未配置 bilibili.report-qq，跳过评论监控");
            return;
        }

        log.info("========== 评论监控扫描开始（阈值: {} 赞）==========", minLikes);

        try {
            // 0. Cookie 有效性
            if (!bilibiliService.checkCookieValid()) {
                log.warn("Cookie 已过期，尝试自动登录...");
                if (bilibiliLoginService.loginAndSaveCookie()) {
                    bilibiliService.refreshWbiKeys();
                    log.info("登录成功");
                } else {
                    log.error("自动登录失败，跳过本次扫描");
                    return;
                }
            }

            // 1. 拉取全量评论（分页，最多 200 条/源）
            List<BilibiliComment> allComments = fetchAllComments();

            // 2. 分离已审核/未审核
            List<BilibiliComment> newComments = allComments.stream()
                    .filter(c -> !store.isModerated(c.rpid()))
                    .toList();

            if (newComments.isEmpty()) {
                log.info("本次无新评论，跳过审核");
                store.trimToSize(2000);
                return;
            }
            log.info("发现 {} 条新评论（总拉取 {} 条），开始审核", newComments.size(), allComments.size());

            // 3. 审核新评论
            List<ModerationResult> allResults = moderationTool.moderate(newComments);

            // 4. 标记全部新评论为已审核
            Set<Long> moderatedRpids = new HashSet<>();
            for (BilibiliComment c : newComments) {
                moderatedRpids.add(c.rpid());
            }
            store.markModerated(moderatedRpids);

            // 5. 处理高赞提醒
            List<BilibiliComment> highLikeComments = newComments.stream()
                    .filter(c -> c.likeCount() >= minLikes)
                    .filter(c -> !store.isNotified(c.rpid()))
                    .toList();
            if (!highLikeComments.isEmpty()) {
                for (BilibiliComment c : highLikeComments) {
                    store.markNotified(c.rpid());
                }
                sendHighLikeAlert(highLikeComments);
            }

            // 6. 执行删除
            List<ModerationResult> deleteList = allResults.stream()
                    .filter(r -> r.action() == ModerationResult.Action.DELETE)
                    .toList();

            if (deleteList.isEmpty()) {
                log.info("审核完成：无需删除的评论");
            } else {
                executeDeletes(deleteList, newComments);
            }

            store.persist();
            store.trimToSize(2000);

        } catch (Exception e) {
            log.error("评论监控执行异常", e);
        }

        log.info("========== 评论监控扫描完成 ==========");
    }

    // ==================== 评论拉取 ====================

    /**
     * 并行拉取最新视频和最新动态的全量评论。
     */
    private List<BilibiliComment> fetchAllComments() {
        List<BilibiliComment> all = new ArrayList<>();

        // 视频评论
        BilibiliVideoStat videoStat = bilibiliService.getLatestVideoStat();
        if (videoStat != null && videoStat.getAid() != null && !videoStat.getAid().isBlank()) {
            log.debug("拉取视频全量评论: aid={}", videoStat.getAid());
            List<BilibiliComment> list = bilibiliService.getAllVideoComments(
                    videoStat.getAid(), MAX_COMMENTS_PER_SOURCE);
            for (BilibiliComment c : list) {
                all.add(new BilibiliComment(
                        c.rpid(), c.mid(), c.uname(), c.avatar(),
                        c.content(), c.likeCount(), c.replyCount(), c.ctime(),
                        videoStat.getTitle(), videoStat.getUrl(), 1,
                        videoStat.getAid()
                ));
            }
            log.info("视频评论: 共拉取 {} 条", list.size());
        } else {
            log.debug("暂无视频，跳过视频评论");
        }

        // 动态评论
        BilibiliDynamicStat dynamicStat = bilibiliService.getLatestDynamic();
        if (dynamicStat != null && dynamicStat.getDynamicId() != null && !dynamicStat.getDynamicId().isBlank()) {
            log.debug("拉取动态全量评论: dynamicId={}", dynamicStat.getDynamicId());
            List<BilibiliComment> list = bilibiliService.getAllDynamicComments(
                    dynamicStat.getDynamicId(), MAX_COMMENTS_PER_SOURCE);
            for (BilibiliComment c : list) {
                all.add(new BilibiliComment(
                        c.rpid(), c.mid(), c.uname(), c.avatar(),
                        c.content(), c.likeCount(), c.replyCount(), c.ctime(),
                        "[" + dynamicStat.getType() + "] " + truncate(dynamicStat.getContent(), 40),
                        dynamicStat.getUrl(), c.sourceType(), c.oid()
                ));
            }
            log.info("动态评论: 共拉取 {} 条", list.size());
        } else {
            log.debug("暂无动态，跳过动态评论");
        }

        return all;
    }

    // ==================== 删除执行 ====================

    private void executeDeletes(List<ModerationResult> deleteList, List<BilibiliComment> newComments) {
        int toDelete = Math.min(deleteList.size(), MAX_DELETE_PER_RUN);
        log.info("审核完成：{} 条违规，将自动删除 {} 条（上限 {}）",
                deleteList.size(), toDelete, MAX_DELETE_PER_RUN);

        // 构建 rpid → comment 索引
        Map<Long, BilibiliComment> commentMap = new HashMap<>();
        for (BilibiliComment c : newComments) {
            commentMap.put(c.rpid(), c);
        }

        List<BilibiliComment> deletedComments = new ArrayList<>();
        List<ModerationResult> deletedResults = new ArrayList<>();
        List<BilibiliComment> failedComments = new ArrayList<>();

        int executed = 0;
        for (int i = 0; i < toDelete; i++) {
            ModerationResult r = deleteList.get(i);
            BilibiliComment c = commentMap.get(r.rpid());
            if (c == null) continue;

            try {
                // 限流保护
                if (executed > 0) {
                    Thread.sleep(DELETE_INTERVAL_MS);
                }

                boolean ok = bilibiliService.deleteComment(c.sourceType(), c.oid(), c.rpid());
                executed++;
                if (ok) {
                    deletedComments.add(c);
                    deletedResults.add(r);
                    log.info("已删除评论: rpid={}, uname={}, reason={}",
                            c.rpid(), c.uname(), r.reason());
                } else {
                    failedComments.add(c);
                    log.warn("删除失败: rpid={}, uname={}", c.rpid(), c.uname());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failedComments.add(c);
                log.error("删除异常: rpid={}", c.rpid(), e);
            }
        }

        if (!failedComments.isEmpty()) {
            log.warn("{} 条评论删除失败（可能无权限或被限流），已跳过", failedComments.size());
        }

        // 超过最大值 → 额外提示
        List<ModerationResult> pendingResults = deleteList.size() > MAX_DELETE_PER_RUN
                ? deleteList.subList(MAX_DELETE_PER_RUN, deleteList.size())
                : Collections.emptyList();

        // 发送审核邮件
        sendModerationReport(deletedComments, deletedResults, pendingResults,
                commentMap, deleteList.size());
    }

    // ==================== 邮件构建 ====================

    private void sendHighLikeAlert(List<BilibiliComment> comments) {
        String html = buildHighLikeHtml(comments);
        String title = "【B站评论提醒】你有 " + comments.size() + " 条高赞评论待互动";
        mailSendTools.sendHtmlMail(reportQq, title, html, null, null);
        log.info("高赞提醒邮件已发送: {} 条 → {}@qq.com", comments.size(), reportQq);
    }

    private void sendModerationReport(List<BilibiliComment> deletedComments,
                                      List<ModerationResult> deletedResults,
                                      List<ModerationResult> pendingResults,
                                      Map<Long, BilibiliComment> commentMap,
                                      int totalViolations) {
        // 仅当删除数 ≥ 阈值时才发邮件
        boolean shouldSend = deletedComments.size() >= moderationAlertThreshold
                || !pendingResults.isEmpty();

        if (!shouldSend && deletedComments.isEmpty()) {
            return;
        }

        String html = buildModerationHtml(deletedComments, deletedResults,
                pendingResults, commentMap, totalViolations);
        String title = deletedComments.isEmpty()
                ? "【B站评论预警】检测到 " + totalViolations + " 条违规评论（未自动删除）"
                : "【B站评论预警】已删除 " + deletedComments.size() + " 条违规评论";
        mailSendTools.sendHtmlMail(reportQq, title, html, null, null);
        log.info("审核报告邮件已发送: {} 条删除, {} 条待处理 → {}@qq.com",
                deletedComments.size(), pendingResults.size(), reportQq);
    }

    // ==================== 审核报告 HTML ====================

    private String buildModerationHtml(List<BilibiliComment> deletedComments,
                                       List<ModerationResult> deletedResults,
                                       List<ModerationResult> pendingResults,
                                       Map<Long, BilibiliComment> commentMap,
                                       int totalViolations) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        html.append("<style>");
        html.append("body{margin:0;padding:0;font-family:'Microsoft YaHei',sans-serif;background:#f4f6f9;color:#2d3436;}");
        html.append(".container{max-width:600px;margin:0 auto;background:#fff;}");
        html.append(".header{background:linear-gradient(135deg,#e53e3e,#c53030);padding:28px 24px;text-align:center;}");
        html.append(".header h2{color:#fff;margin:0 0 4px;font-size:18px;}");
        html.append(".header span{color:rgba(255,255,255,0.8);font-size:12px;}");
        html.append(".body{padding:20px 24px;}");
        html.append(".item{border:1px solid #fed7d7;border-left:3px solid #e53e3e;border-radius:6px;padding:12px 14px;margin-bottom:10px;}");
        html.append(".item-hd{display:flex;align-items:center;margin-bottom:6px;}");
        html.append(".uname{font-size:13px;font-weight:600;color:#c53030;}");
        html.append(".reason{font-size:11px;background:#fff5f5;color:#c53030;padding:2px 8px;border-radius:8px;margin-left:auto;}");
        html.append(".reason-keyword{background:#fffaf0;color:#c05621;}");
        html.append(".reason-ai{background:#f0fff4;color:#276749;}");
        html.append(".item-body{font-size:14px;color:#4a5568;line-height:1.5;margin-bottom:4px;}");
        html.append(".stats{font-size:12px;color:#a0aec0;margin-bottom:4px;}");
        html.append(".source-tag{display:inline-block;font-size:11px;padding:1px 6px;border-radius:8px;}");
        html.append(".tag-video{background:#fff0f3;color:#fb7299;}");
        html.append(".tag-dynamic{background:#f0f4ff;color:#6366f1;}");
        html.append(".summary{background:#fffbeb;border:1px solid #fbd38d;border-radius:8px;padding:14px 16px;margin-top:16px;font-size:13px;color:#744210;line-height:1.8;}");
        html.append(".summary b{color:#c05621;}");
        html.append(".footer{text-align:center;padding:16px;color:#b2bec3;font-size:12px;border-top:1px solid#f1f3f5;}");
        html.append("</style></head><body>");
        html.append("<div class=\"container\">");

        // Header
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        html.append("<div class=\"header\">");
        html.append("<h2>B站评论审核报告</h2>");
        html.append("<span>").append(now).append(" · 检测到 ").append(totalViolations).append(" 条违规</span>");
        html.append("</div>");

        html.append("<div class=\"body\">");

        // 已删除列表
        if (!deletedComments.isEmpty()) {
            html.append("<p style=\"font-weight:600;color:#c53030;margin:0 0 10px;\">")
                    .append("已删除 ").append(deletedComments.size()).append(" 条</p>");
            for (int i = 0; i < deletedComments.size(); i++) {
                BilibiliComment c = deletedComments.get(i);
                ModerationResult r = deletedResults.get(i);
                appendModerationItem(html, c, r);
            }
        }

        // 待处理列表
        if (!pendingResults.isEmpty()) {
            html.append("<p style=\"font-weight:600;color:#c05621;margin:16px 0 10px;\">")
                    .append("待人工复核 ").append(pendingResults.size()).append(" 条</p>");
            for (ModerationResult r : pendingResults) {
                BilibiliComment c = commentMap.get(r.rpid());
                if (c != null) {
                    appendModerationItem(html, c, r);
                }
            }
        }

        // 摘要
        html.append("<div class=\"summary\">");
        html.append("<b>统计</b><br>");
        long keywordCount = deletedResults.stream()
                .filter(r -> r.source() == ModerationResult.Source.KEYWORD).count();
        long aiCount = deletedResults.stream()
                .filter(r -> r.source() == ModerationResult.Source.AI).count();
        html.append("关键词命中: ").append(keywordCount).append(" 条<br>");
        html.append("AI 判定: ").append(aiCount).append(" 条<br>");
        if (!deletedComments.isEmpty()) {
            html.append("已自动删除: ").append(deletedComments.size()).append(" 条<br>");
            html.append("<b>单次删除超 ").append(moderationAlertThreshold).append(" 条")
                    .append("，建议登录B站确认评论区状态</b>");
        }
        if (!pendingResults.isEmpty()) {
            html.append("<br>⚠ 有 ").append(pendingResults.size())
                    .append(" 条超限未处理，请登录B站手动处理");
        }
        html.append("</div>");

        html.append("</div>"); // .body

        html.append("<div class=\"footer\">评论监控 · 自动审核 · 每30分钟</div>");
        html.append("</div>"); // .container
        html.append("</body></html>");
        return html.toString();
    }

    private void appendModerationItem(StringBuilder html, BilibiliComment c, ModerationResult r) {
        boolean isVideo = c.sourceType() == 1;
        html.append("<div class=\"item\">");
        html.append("<div class=\"item-hd\">");
        html.append("<span class=\"uname\">").append(escapeHtml(c.uname())).append("</span>");
        String reasonClass = r.source() == ModerationResult.Source.KEYWORD ? "reason-keyword" : "reason-ai";
        html.append("<span class=\"reason ").append(reasonClass).append("\">")
                .append(escapeHtml(r.reason())).append("</span>");
        html.append("</div>");
        html.append("<div class=\"item-body\">").append(escapeHtml(c.content())).append("</div>");
        html.append("<div class=\"stats\">");
        html.append("<span class=\"source-tag ")
                .append(isVideo ? "tag-video" : "tag-dynamic").append("\">");
        html.append(isVideo ? "视频" : "动态").append("</span>");
        html.append(" ❤ ").append(c.likeCount()).append(" · ")
                .append(formatRelativeTime(c.ctime()));
        html.append("</div>");
        html.append("</div>");
    }

    // ==================== 高赞提醒 HTML ====================

    private String buildHighLikeHtml(List<BilibiliComment> comments) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        html.append("<style>");
        html.append("body{margin:0;padding:0;font-family:'Microsoft YaHei',sans-serif;background:#f4f6f9;color:#2d3436;}");
        html.append(".container{max-width:600px;margin:0 auto;background:#fff;}");
        html.append(".header{background:linear-gradient(135deg,#fb7299,#ff6b81);padding:28px 24px;text-align:center;}");
        html.append(".header h2{color:#fff;margin:0;font-size:18px;font-weight:600;}");
        html.append(".header span{color:rgba(255,255,255,0.8);font-size:12px;}");
        html.append(".body{padding:20px 24px;}");
        html.append(".comment{border:1px solid #edf2f7;border-radius:8px;padding:14px 16px;margin-bottom:12px;}");
        html.append(".comment:last-child{margin-bottom:0;}");
        html.append(".comment-hd{display:flex;align-items:center;margin-bottom:8px;}");
        html.append(".avatar{width:28px;height:28px;border-radius:50%;margin-right:10px;flex-shrink:0;}");
        html.append(".uname{font-size:13px;font-weight:600;color:#333;}");
        html.append(".meta{font-size:12px;color:#a0aec0;margin-left:auto;flex-shrink:0;}");
        html.append(".comment-body{font-size:14px;color:#4a5568;line-height:1.6;margin-bottom:8px;}");
        html.append(".comment-ft{font-size:12px;color:#a0aec0;}");
        html.append(".comment-ft a{color:#fb7299;text-decoration:none;}");
        html.append(".source-tag{display:inline-block;font-size:11px;padding:2px 8px;border-radius:10px;margin-bottom:6px;}");
        html.append(".tag-video{background:#fff0f3;color:#fb7299;}");
        html.append(".tag-dynamic{background:#f0f4ff;color:#6366f1;}");
        html.append(".footer{text-align:center;padding:16px;color:#b2bec3;font-size:12px;border-top:1px solid #f1f3f5;}");
        html.append("</style></head><body>");
        html.append("<div class=\"container\">");

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        html.append("<div class=\"header\">");
        html.append("<h2>B站评论提醒</h2>");
        html.append("<span>").append(now).append(" · 扫描到 ")
                .append(comments.size()).append(" 条高赞评论</span>");
        html.append("</div>");

        html.append("<div class=\"body\">");
        for (BilibiliComment c : comments) {
            boolean isVideo = c.sourceType() == 1;
            html.append("<div class=\"comment\">");
            html.append("<span class=\"source-tag ")
                    .append(isVideo ? "tag-video" : "tag-dynamic").append("\">");
            html.append(isVideo ? "视频" : "动态");
            html.append(" · ").append(escapeHtml(c.sourceTitle()));
            html.append("</span>");
            html.append("<div class=\"comment-hd\">");
            html.append("<img class=\"avatar\" src=\"").append(escapeHtml(c.avatar())).append("\" "
                    + "onerror=\"this.style.display='none'\" />");
            html.append("<span class=\"uname\">").append(escapeHtml(c.uname())).append("</span>");
            html.append("<span class=\"meta\">").append(formatRelativeTime(c.ctime())).append("</span>");
            html.append("</div>");
            html.append("<div class=\"comment-body\">").append(escapeHtml(c.content())).append("</div>");
            html.append("<div class=\"comment-ft\">");
            html.append("❤ ").append(c.likeCount()).append(" 赞");
            if (c.replyCount() > 0) {
                html.append(" · 💬 ").append(c.replyCount()).append(" 回复");
            }
            if (c.sourceUrl() != null && !c.sourceUrl().isBlank()) {
                html.append(" · <a href=\"").append(escapeHtml(c.sourceUrl()))
                        .append("\">查看原文 →</a>");
            }
            html.append("</div>");
            html.append("</div>");
        }
        html.append("</div>");

        html.append("<div class=\"footer\">评论监控 · 每30分钟自动检查 · 阈值 ≥ ")
                .append(minLikes).append(" 赞</div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    // ==================== 工具方法 ====================

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String formatRelativeTime(long timestampSec) {
        long now = Instant.now().getEpochSecond();
        long diff = now - timestampSec;
        if (diff < 60) return "刚刚";
        if (diff < 3600) return (diff / 60) + "分钟前";
        if (diff < 86400) return (diff / 3600) + "小时前";
        if (diff < 604800) return (diff / 86400) + "天前";
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestampSec), ZoneId.systemDefault())
                .format(TIME_FMT);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen) + "...";
    }
}
