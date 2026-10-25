package org.example.ykd_summer.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 BSS 余额查询配置。
 */
@ConfigurationProperties(prefix = "alibaba-cloud")
public record BillingProperties(
        /** RAM 子账号 AccessKey ID */
        String accessKeyId,
        /** RAM 子账号 AccessKey Secret */
        String accessKeySecret,
        /** BSS API 端点 */
        Bss bss
) {
    public record Bss(String endpoint, String apiVersion) {}
}
