package org.example.ykd_summer.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.BilibiliTrendingVideo;
import org.example.ykd_summer.dto.WeeklyTrendingAnalysis;
import org.example.ykd_summer.service.TrendingAnalysisService;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * B站热门视频分析服务实现。
 * <p>
 * 统计 + AI 生成创作建议。AI 调用失败不抛异常，creativeAdvice 字段为 null。
 */
@Slf4j
@Service
public class TrendingAnalysisServiceImpl implements TrendingAnalysisService {

    private final OpenAiChatModel chatModel;

    public TrendingAnalysisServiceImpl(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public WeeklyTrendingAnalysis analyze(List<BilibiliTrendingVideo> trendingVideos) {
        return analyze(trendingVideos, null);
    }

    @Override
    public WeeklyTrendingAnalysis analyze(List<BilibiliTrendingVideo> trendingVideos,
                                           List<Document> userUploadHistory) {
        if (trendingVideos == null || trendingVideos.isEmpty()) {
            return new WeeklyTrendingAnalysis(List.of(), Map.of(), Map.of(), Map.of(), "", List.of(), null, null);
        }

        // ======================== 纯统计 ========================
        Map<String, Integer> partitionDistribution = buildPartitionDistribution(trendingVideos);
        Map<String, Long> partitionAvgView = buildPartitionAvgView(trendingVideos);
        Map<String, Long> partitionTotalLike = buildPartitionTotalLike(trendingVideos);
        String dominantPartition = findDominantPartition(partitionDistribution);
        List<String> keyObservations = buildKeyObservations(trendingVideos,
                partitionDistribution, partitionAvgView, dominantPartition);

        // ======================== AI 建议 ========================
        String creativeAdvice = null;
        String userUploadHistorySummary = null;
        try {
            userUploadHistorySummary = buildUserHistorySummary(userUploadHistory);
            creativeAdvice = generateCreativeAdvice(trendingVideos, partitionDistribution,
                    partitionAvgView, dominantPartition, keyObservations,
                    userUploadHistorySummary);
        } catch (Exception e) {
            log.error("AI 生成创作建议失败，将只输出统计数据", e);
        }

        return new WeeklyTrendingAnalysis(trendingVideos, partitionDistribution,
                partitionAvgView, partitionTotalLike, dominantPartition,
                keyObservations, creativeAdvice, userUploadHistorySummary);
    }

    // ------------------------ 统计方法 ------------------------

    private Map<String, Integer> buildPartitionDistribution(List<BilibiliTrendingVideo> videos) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (BilibiliTrendingVideo v : videos) {
            dist.merge(v.partition(), 1, Integer::sum);
        }
        // 按数量降序排列
        return dist.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }

    private Map<String, Long> buildPartitionAvgView(List<BilibiliTrendingVideo> videos) {
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (BilibiliTrendingVideo v : videos) {
            grouped.computeIfAbsent(v.partition(), k -> new ArrayList<>())
                    .add((long) v.viewCount());
        }
        Map<String, Long> avg = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> e : grouped.entrySet()) {
            long sum = 0;
            for (Long v : e.getValue()) sum += v;
            avg.put(e.getKey(), sum / e.getValue().size());
        }
        return avg.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }

    private Map<String, Long> buildPartitionTotalLike(List<BilibiliTrendingVideo> videos) {
        Map<String, Long> likes = new LinkedHashMap<>();
        for (BilibiliTrendingVideo v : videos) {
            likes.merge(v.partition(), (long) v.likeCount(), Long::sum);
        }
        return likes.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }

    private String findDominantPartition(Map<String, Integer> dist) {
        if (dist.isEmpty()) return "";
        return dist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private List<String> buildKeyObservations(List<BilibiliTrendingVideo> videos,
                                              Map<String, Integer> dist,
                                              Map<String, Long> avgView,
                                              String dominantPartition) {
        List<String> obs = new ArrayList<>();
        int total = videos.size();

        // 主导分区
        if (!dominantPartition.isEmpty() && dist.containsKey(dominantPartition)) {
            int dominantCount = dist.get(dominantPartition);
            obs.add(String.format("本周%s上榜视频最多（%d/%d），占比 %d%%",
                    dominantPartition, dominantCount, total,
                    dominantCount * 100 / total));
        }

        // 平均播放最高的分区
        if (!avgView.isEmpty()) {
            String topViewPartition = avgView.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("");
            if (!topViewPartition.isEmpty()) {
                obs.add(String.format("%s平均播放量最高（%s），内容消费意愿极强",
                        topViewPartition, formatNumber(avgView.get(topViewPartition))));
            }
        }

        // 分区多样性
        int partitionCount = dist.size();
        obs.add(String.format("榜单共覆盖 %d 个分区，内容生态多样化程度%s", partitionCount,
                partitionCount > 20 ? "高" : "中等"));

        // Top 分区汇总
        List<String> topPartitions = dist.keySet().stream().limit(3).toList();
        if (topPartitions.size() >= 3) {
            obs.add(String.format("分区前三: %s(%d个)、%s(%d个)、%s(%d个)",
                    topPartitions.get(0), dist.get(topPartitions.get(0)),
                    topPartitions.get(1), dist.get(topPartitions.get(1)),
                    topPartitions.get(2), dist.get(topPartitions.get(2))));
        }

        return obs;
    }

    // ------------------------ AI 生成建议 ------------------------

    private String generateCreativeAdvice(List<BilibiliTrendingVideo> videos,
                                          Map<String, Integer> dist,
                                          Map<String, Long> avgView,
                                          String dominantPartition,
                                          List<String> observations,
                                          String userUploadHistorySummary) {
        String prompt = buildAdvicePrompt(videos, dist, avgView, dominantPartition,
                observations, userUploadHistorySummary);
        try {
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            log.info("AI 创作建议生成成功，长度: {} 字", response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            log.error("调用 AI 生成创作建议失败", e);
            return null;
        }
    }

    private String buildAdvicePrompt(List<BilibiliTrendingVideo> videos,
                                     Map<String, Integer> dist,
                                     Map<String, Long> avgView,
                                     String dominantPartition,
                                     List<String> observations,
                                     String userUploadHistorySummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 B站内容创作顾问。以下是 B站全站排行榜的近期热门视频数据：\n\n");

        // 【新增】用户创作档案
        if (userUploadHistorySummary != null && !userUploadHistorySummary.isBlank()) {
            sb.append("【用户历史创作档案】\n");
            sb.append(userUploadHistorySummary);
            sb.append("\n\n");
        }

        // 分区分布
        sb.append("【分区分布】\n");
        int rank = 1;
        for (Map.Entry<String, Integer> e : dist.entrySet()) {
            if (rank > 10) break;
            sb.append(String.format("%d. %s：%d个视频",
                    rank++, e.getKey(), e.getValue()));
            if (avgView.containsKey(e.getKey())) {
                sb.append(String.format("，平均播放 %s", formatNumber(avgView.get(e.getKey()))));
            }
            sb.append("\n");
        }
        sb.append("\n");

        // 热门视频样例
        sb.append("【热门视频样例（前10）】\n");
        List<BilibiliTrendingVideo> samples = videos.stream().limit(10).toList();
        for (BilibiliTrendingVideo v : samples) {
            sb.append(String.format("- %s「%s」（%s区，播放 %s）\n",
                    v.ownerName(), v.title(), v.partition(), formatNumber((long) v.viewCount())));
        }
        sb.append("\n");

        // 关键发现
        sb.append("【关键数据发现】\n");
        for (String obs : observations) {
            sb.append("- ").append(obs).append("\n");
        }
        sb.append("\n");

        // 任务
        sb.append("请基于以上数据，为用户提供接下来一周的视频创作建议。要求：\n");
        sb.append("1. 分析哪些分区当前最热门、哪些分区竞争小但流量高\n");
        sb.append("2. 归纳热门视频的共性（题材、时长、标题风格等）\n");
        sb.append("3. 给出 2-3 个具体的创作方向建议，每个建议包括：推荐分区、选题方向、理由\n");
        sb.append("4. 整体语调积极、有建设性\n");
        sb.append("5. 控制在 300 字以内\n");
        if (userUploadHistorySummary != null && !userUploadHistorySummary.isBlank()) {
            sb.append("6. 结合「用户历史创作档案」，分析用户一贯的创作风格和选题偏好\n");
            sb.append("7. 推荐的创作方向要能与用户的已有风格自然衔接，"
                    + "如果是全新方向，也要说明如何利用用户已有的创作经验进行过渡\n");
            sb.append("8. 如果热门趋势中有与用户现有创作方向高度契合的内容类型，优先推荐\n");
        }
        sb.append("\n你的回复直接作为邮件内容发送给用户，请直接输出建议文本，不要加「你好」「感谢」等问候语。");

        return sb.toString();
    }

    /**
     * 从创作档案文档列表中构建用户历史创作摘要文本。
     */
    private String buildUserHistorySummary(List<Document> userUploadHistory) {
        if (userUploadHistory == null || userUploadHistory.isEmpty()) {
            return null;
        }

        // 统计分区偏好
        Map<String, Long> partitionCounts = userUploadHistory.stream()
                .map(d -> d.getMetadata() != null
                        ? (String) d.getMetadata().getOrDefault("partition", "未知") : "未知")
                .collect(Collectors.groupingBy(p -> p, LinkedHashMap::new, Collectors.counting()));

        // 收集所有标签
        Set<String> allTags = userUploadHistory.stream()
                .map(d -> d.getMetadata() != null
                        ? (String) d.getMetadata().getOrDefault("tags", "") : "")
                .filter(s -> !s.isBlank())
                .flatMap(s -> Arrays.stream(s.split("[,，、]")))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        StringBuilder sb = new StringBuilder();
        sb.append("该用户过去上传过以下视频：\n");

        int count = 0;
        for (Document doc : userUploadHistory) {
            if (count >= 10) break;
            String title = doc.getMetadata() != null
                    ? (String) doc.getMetadata().getOrDefault("title", "") : "";
            String partition = doc.getMetadata() != null
                    ? (String) doc.getMetadata().getOrDefault("partition", "") : "";
            String tags = doc.getMetadata() != null
                    ? (String) doc.getMetadata().getOrDefault("tags", "") : "";
            if (!title.isBlank()) {
                sb.append(String.format("- 标题「%s」分区:%s 标签:[%s]\n",
                        title, partition, tags));
                count++;
            }
        }

        if (userUploadHistory.size() > 10) {
            sb.append(String.format("  ... 及其他 %d 条记录\n",
                    userUploadHistory.size() - 10));
        }

        sb.append("\n从这些记录可以看出用户的**主要创作方向**：\n");
        if (!partitionCounts.isEmpty()) {
            sb.append("- 分区偏好: ");
            partitionCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> sb.append(String.format("%s(%d次) ",
                            e.getKey(), e.getValue())));
            sb.append("\n");
        }
        if (!allTags.isEmpty()) {
            sb.append("- 标签风格: ");
            allTags.stream().limit(10)
                    .forEach(t -> sb.append(t).append("、"));
            sb.setLength(sb.length() - 1);
            sb.append("\n");
        }
        sb.append(String.format("- 累计投稿: %d 次\n", userUploadHistory.size()));

        return sb.toString();
    }

    private static String formatNumber(long num) {
        if (num >= 100_000_000) return String.format("%.1f亿", num / 100_000_000.0);
        if (num >= 10_000) return String.format("%.1f万", num / 10_000.0);
        return String.valueOf(num);
    }
}
