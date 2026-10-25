package org.example.ykd_summer.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Token 用量与定价配置。
 */
@ConfigurationProperties(prefix = "dashscope.usage")
public record DashScopeUsageProperties(
        /** 月配额（token 数），0 表示不限配额 */
        long monthlyQuota,
        /** 持久化文件路径 */
        String persistPath,
        /** 各 Agent 的模型定价（元/千Token），key 为 agent 名 */
        Map<String, Pricing> pricing
) {
    public record Pricing(double inputPrice, double outputPrice) {}
}
