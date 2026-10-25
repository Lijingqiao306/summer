package org.example.ykd_summer.web;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 工具类调用追踪器。
 * <p>
 * 统计每个工具类当前的活跃调用数（并发可重入）和累计调用次数，
 * 供监控页面展示工具方块的点亮状态和调用计数。
 */
@Component
public class ToolCallTracker {

    /** 工具类名 -> 当前活跃调用数（同一工具类可能被并发/嵌套调用） */
    private final ConcurrentHashMap<String, AtomicInteger> activeCounts = new ConcurrentHashMap<>();

    /** 工具类名 -> 累计调用次数 */
    private final ConcurrentHashMap<String, LongAdder> totalCounts = new ConcurrentHashMap<>();

    public void start(String toolClass) {
        activeCounts.computeIfAbsent(toolClass, k -> new AtomicInteger()).incrementAndGet();
        totalCounts.computeIfAbsent(toolClass, k -> new LongAdder()).increment();
    }

    public void end(String toolClass) {
        AtomicInteger c = activeCounts.get(toolClass);
        if (c != null) {
            c.decrementAndGet();
        }
    }

    /** 当前活跃调用数（0 表示未在调用） */
    public int activeCount(String toolClass) {
        AtomicInteger c = activeCounts.get(toolClass);
        return c == null ? 0 : Math.max(0, c.get());
    }

    /** 累计调用次数 */
    public long totalCount(String toolClass) {
        LongAdder c = totalCounts.get(toolClass);
        return c == null ? 0 : c.sum();
    }
}
