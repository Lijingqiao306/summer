package org.example.ykd_summer.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 在 Spring 启动完成后将 {@link ConsoleLogAppender} 挂载到 root logger。
 * <p>
 * 采用代码方式注册 Appender，无需修改 logback-spring.xml 等外部配置。
 */
@Slf4j
@Component
public class ConsoleLogAppenderRegistrar {

    private final ConsoleEventBus eventBus;

    public ConsoleLogAppenderRegistrar(ConsoleEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        ConsoleLogAppender appender = new ConsoleLogAppender();
        appender.setEventBus(eventBus);
        appender.setName("ConsoleLogAppender");
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);

        log.info("ConsoleLogAppender 已注册，日志桥接到 SSE 事件总线");
    }
}
