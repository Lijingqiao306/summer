package org.example.ykd_summer;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.config.properties.BillingProperties;
import org.example.ykd_summer.config.properties.DashScopeUsageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableAspectJAutoProxy
@EnableConfigurationProperties({DashScopeUsageProperties.class, BillingProperties.class})
public class YkdSummerApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(YkdSummerApplication.class, args);
            log.info("Application started successfully");
            log.info("Application started successfully.Hello ZZT");
        } catch (Exception e) {
            log.error("Application failed to start.", e);
        }
    }
}
