package org.example.ykd_summer.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.config.properties.DashScopeUsageProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token 用量追踪器。
 * <p>
 * 每次 LLM 调用后记录 token 消耗，实时累加并计算费用，
 * 持久化到文件保证重启不丢失。累计费用供余额估算（estimatedBalance）使用。
 */
@Slf4j
@Component
public class TokenUsageTracker {

    // ===== 内存累加器 =====
    private final AtomicLong totalPromptTokens = new AtomicLong(0);
    private final AtomicLong totalCompletionTokens = new AtomicLong(0);
    private final AtomicLong totalCachedPromptTokens = new AtomicLong(0);
    private final Map<String, AgentStats> byAgent = new LinkedHashMap<>();
    private final ReentrantLock statsLock = new ReentrantLock();

    // ===== 费用累加 =====
    private double totalCost = 0.0;
    private final ReentrantLock costLock = new ReentrantLock();

    // ===== 持久化 =====
    private final Path persistPath;
    private volatile boolean dirty = false;
    private boolean persistScheduled = false;
    private final ReentrantLock persistLock = new ReentrantLock();

    // ===== 依赖 =====
    private final DashScopeUsageProperties usageProperties;
    private final ObjectMapper objectMapper;

    private static final class AgentStats {
        final AtomicLong promptTokens = new AtomicLong(0);
        final AtomicLong completionTokens = new AtomicLong(0);
        final AtomicLong cachedPromptTokens = new AtomicLong(0);
        double cost = 0.0;
    }

    public TokenUsageTracker(DashScopeUsageProperties usageProperties,
                             ObjectMapper objectMapper) {
        this.usageProperties = usageProperties;
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String path = usageProperties.persistPath();
        this.persistPath = (path != null && !path.isBlank())
                ? Paths.get(path)
                : Paths.get("data/token_usage.json");
    }

    /**
     * 启动时从文件恢复历史数据。
     */
    @PostConstruct
    void loadFromFile() {
        try {
            if (Files.exists(persistPath)) {
                String json = Files.readString(persistPath);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(json, Map.class);

                totalPromptTokens.set(toLong(data.get("totalPromptTokens")));
                totalCompletionTokens.set(toLong(data.get("totalCompletionTokens")));
                totalCachedPromptTokens.set(toLong(data.getOrDefault("totalCachedPromptTokens", 0)));

                Number costFromFile = (Number) data.get("totalCost");
                totalCost = costFromFile != null ? costFromFile.doubleValue() : 0.0;

                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> agents = (Map<String, Map<String, Object>>) data.get("byAgent");
                if (agents != null) {
                    statsLock.lock();
                    try {
                        for (var entry : agents.entrySet()) {
                            AgentStats stats = new AgentStats();
                            stats.promptTokens.set(toLong(entry.getValue().get("promptTokens")));
                            stats.completionTokens.set(toLong(entry.getValue().get("completionTokens")));
                            stats.cachedPromptTokens.set(toLong(entry.getValue().getOrDefault("cachedPromptTokens", 0)));
                            Number c = (Number) entry.getValue().get("cost");
                            stats.cost = c != null ? c.doubleValue() : 0.0;
                            byAgent.put(entry.getKey(), stats);
                        }
                    } finally {
                        statsLock.unlock();
                    }
                }
                log.info("Token 用量已从文件恢复: totalTokens={}, cost=¥{:.6f}",
                        totalPromptTokens.get() + totalCompletionTokens.get(), totalCost);
            }
        } catch (Exception e) {
            log.warn("Token 用量文件加载失败，将从零开始统计", e);
        }
    }

    /**
     * 记录一次 LLM 调用的 token 消耗。
     *
     * @param agentName        Agent 名称（如 "Router", "TEXT", "IMAGE"）
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      合计 token 数
     */
    public void record(String agentName, long promptTokens, long completionTokens, long totalTokens) {
        record(agentName, promptTokens, completionTokens, totalTokens, 0);
    }

    /**
     * 记录一次 LLM 调用的 token 消耗，包含上下文缓存命中信息。
     *
     * @param agentName          Agent 名称（如 "Router", "TEXT", "IMAGE"）
     * @param promptTokens       输入 token 数（已计费部分）
     * @param completionTokens   输出 token 数
     * @param totalTokens        合计 token 数
     * @param cachedPromptTokens 上下文缓存命中的 prompt token 数（不计费部分）
     */
    public void record(String agentName, long promptTokens, long completionTokens,
                       long totalTokens, long cachedPromptTokens) {
        if (totalTokens <= 0 && cachedPromptTokens <= 0) return;

        // 累加全局统计
        totalPromptTokens.addAndGet(promptTokens);
        totalCompletionTokens.addAndGet(completionTokens);
        if (cachedPromptTokens > 0) {
            totalCachedPromptTokens.addAndGet(cachedPromptTokens);
        }

        // 按 Agent 累加
        statsLock.lock();
        try {
            AgentStats stats = byAgent.computeIfAbsent(agentName, k -> new AgentStats());
            stats.promptTokens.addAndGet(promptTokens);
            stats.completionTokens.addAndGet(completionTokens);
            if (cachedPromptTokens > 0) {
                stats.cachedPromptTokens.addAndGet(cachedPromptTokens);
            }
        } finally {
            statsLock.unlock();
        }

        // 计算费用
        computeCost(agentName, promptTokens, completionTokens);

        // 标记脏数据，触发持久化
        dirty = true;
        schedulePersist();
    }

    private void computeCost(String agentName, long promptTokens, long completionTokens) {
        DashScopeUsageProperties.Pricing pricing = null;
        if (usageProperties.pricing() != null) {
            // 先按 agent 名精确匹配
            pricing = usageProperties.pricing().get(agentName);
        }

        if (pricing == null) return;

        double cost = (promptTokens * pricing.inputPrice() + completionTokens * pricing.outputPrice()) / 1000.0;

        costLock.lock();
        try {
            totalCost += cost;
        } finally {
            costLock.unlock();
        }

        // 更新 Agent 费用
        statsLock.lock();
        try {
            AgentStats stats = byAgent.get(agentName);
            if (stats != null) {
                stats.cost += cost;
            }
        } finally {
            statsLock.unlock();
        }
    }

    /**
     * 轻量快照：仅返回当前累计的 prompt/completion tokens 与费用。
     * 用于在任务开始/结束处采样，计算本次任务的差值。线程安全。
     */
    public Totals getCurrentTotals() {
        long prompt = totalPromptTokens.get();
        long completion = totalCompletionTokens.get();
        long cached = totalCachedPromptTokens.get();

        double cost;
        costLock.lock();
        try {
            cost = totalCost;
        } finally {
            costLock.unlock();
        }

        return new Totals(prompt, completion, prompt + completion, cost, cached);
    }

    /**
     * 当前累计用量的轻量快照。
     */
    public record Totals(long promptTokens, long completionTokens, long totalTokens,
                         double cost, long cachedPromptTokens) {}

    /**
     * 获取当前用量快照。
     */
    public TokenUsageSnapshot getSnapshot() {
        long quota = usageProperties.monthlyQuota();
        long prompt = totalPromptTokens.get();
        long completion = totalCompletionTokens.get();
        long cached = totalCachedPromptTokens.get();
        long total = prompt + completion;
        long remaining = quota > 0 ? Math.max(0, quota - total) : -1;
        double percent = quota > 0 ? Math.min(100.0, (total * 100.0) / quota) : 0;

        // 全局缓存命中率 = cached / (prompt + cached)，避免除以 0
        double globalHitRate = (prompt + cached > 0)
                ? (cached * 100.0) / (prompt + cached)
                : 0;

        Map<String, TokenUsageSnapshot.AgentTokenUsage> agentMap = new LinkedHashMap<>();
        statsLock.lock();
        try {
            for (var entry : byAgent.entrySet()) {
                AgentStats s = entry.getValue();
                long agentPrompt = s.promptTokens.get();
                long agentCached = s.cachedPromptTokens.get();
                double agentHitRate = (agentPrompt + agentCached > 0)
                        ? (agentCached * 100.0) / (agentPrompt + agentCached)
                        : 0;
                agentMap.put(entry.getKey(), new TokenUsageSnapshot.AgentTokenUsage(
                        agentPrompt, s.completionTokens.get(),
                        agentPrompt + s.completionTokens.get(), s.cost,
                        agentCached, agentHitRate));
            }
        } finally {
            statsLock.unlock();
        }

        double cost;
        costLock.lock();
        try {
            cost = totalCost;
        } finally {
            costLock.unlock();
        }

        return new TokenUsageSnapshot(prompt, completion, total, quota, remaining, percent,
                cost, agentMap, Instant.now(), cached, globalHitRate);
    }

    /**
     * 重置当月统计。
     */
    public void reset() {
        totalPromptTokens.set(0);
        totalCompletionTokens.set(0);
        totalCachedPromptTokens.set(0);

        costLock.lock();
        try {
            totalCost = 0.0;
        } finally {
            costLock.unlock();
        }

        statsLock.lock();
        try {
            byAgent.clear();
        } finally {
            statsLock.unlock();
        }

        try {
            Files.deleteIfExists(persistPath);
        } catch (IOException ignored) {
        }

        log.info("Token 用量统计已重置");
    }

    // ===== 持久化 =====

    void schedulePersist() {
        persistLock.lock();
        try {
            if (persistScheduled) return;
            persistScheduled = true;
        } finally {
            persistLock.unlock();
        }

        // 防抖：在新线程中延迟 2 秒后写入
        Thread persistThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                persistScheduled = false;
                return;
            }
            doPersist();
        });
        persistThread.setName("token-persist");
    }

    private void doPersist() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalPromptTokens", totalPromptTokens.get());
            data.put("totalCompletionTokens", totalCompletionTokens.get());
            data.put("totalCachedPromptTokens", totalCachedPromptTokens.get());
            data.put("totalCost", totalCost);
            data.put("lastUpdated", Instant.now().toString());

            Map<String, Object> agents = new LinkedHashMap<>();
            statsLock.lock();
            try {
                for (var entry : byAgent.entrySet()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("promptTokens", entry.getValue().promptTokens.get());
                    m.put("completionTokens", entry.getValue().completionTokens.get());
                    m.put("cachedPromptTokens", entry.getValue().cachedPromptTokens.get());
                    m.put("cost", entry.getValue().cost);
                    agents.put(entry.getKey(), m);
                }
            } finally {
                statsLock.unlock();
            }
            data.put("byAgent", agents);

            Files.createDirectories(persistPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(persistPath.toFile(), data);
            dirty = false;
        } catch (Exception e) {
            log.warn("Token 用量持久化失败", e);
        } finally {
            persistLock.lock();
            try {
                persistScheduled = false;
                // 如果期间有新数据，立即排入下一次写入
                if (dirty) {
                    persistScheduled = true;
                }
            } finally {
                persistLock.unlock();
            }
            if (dirty) {
                // 递归排下一次（但实际下次调用会重新触发 schedulePersist）
                doPersist();
            }
        }
    }

    private static long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
