package org.example.ykd_summer.billing;

import java.time.Instant;
import java.util.Map;

/**
 * Token 用量汇总快照，通过 SSE / API 推送给前端。
 */
public record TokenUsageSnapshot(
        /** 累计输入 token */
        long totalPromptTokens,
        /** 累计输出 token */
        long totalCompletionTokens,
        /** 累计总量 */
        long totalTokens,
        /** 月配额（0 = 不限） */
        long monthlyQuota,
        /** 剩余 token（配额 - 累计），配额为 0 时返回 -1 表示无限制 */
        long remainingTokens,
        /** 配额使用百分比（0-100），配额为 0 时返回 0 */
        double quotaPercent,
        /** 累计费用（元） */
        double totalCost,
        /** 各 Agent 用量分布 */
        Map<String, AgentTokenUsage> byAgent,
        /** 最后更新时间 */
        Instant lastUpdated,
        /** 累计通过 DashScope 上下文缓存节省的 prompt token（不计费部分） */
        long totalCachedPromptTokens,
        /** 全局缓存命中率（cached / (prompt + cached)），无缓存时返回 0 */
        double globalCacheHitRate
) {
    public record AgentTokenUsage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            double cost,
            /** 该 Agent 累计通过上下文缓存节省的 prompt token */
            long cachedPromptTokens,
            /** 该 Agent 的缓存命中率 */
            double cacheHitRate
    ) {}
}
