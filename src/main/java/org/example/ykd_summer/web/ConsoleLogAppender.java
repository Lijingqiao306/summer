package org.example.ykd_summer.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback 自定义 Appender，将应用的日志输出桥接到 {@link ConsoleEventBus}。
 * <p>
 * 只捕获 {@code org.example.ykd_summer} 包的日志（排除 web 子包防止循环），
 * 通过 SSE 实时推送到浏览器监控页面。
 * <p>
 * 安装方式：由 {@link ConsoleLogAppenderRegistrar} 在 Spring 启动后
 * 以编程方式创建并挂载到 root logger，无需修改 logback XML 配置。
 */
public class ConsoleLogAppender extends AppenderBase<ILoggingEvent> {

    private volatile ConsoleEventBus eventBus;

    /** 排除自身所在的 web 包，防止日志循环 */
    private static final String EXCLUDED_PACKAGE = "org.example.ykd_summer.web";

    public void setEventBus(ConsoleEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        ConsoleEventBus bus = this.eventBus;
        if (bus == null) return;

        String loggerName = eventObject.getLoggerName();
        // 只推送 ykd_summer 包的日志（排除 web 包防止循环）
        if (loggerName == null
                || !loggerName.startsWith("org.example.ykd_summer")
                || loggerName.startsWith(EXCLUDED_PACKAGE)) {
            return;
        }

        Level level = eventObject.getLevel();
        String levelStr = level.levelStr;
        String source = shortenLogger(loggerName);
        String message = eventObject.getFormattedMessage();
        bus.publish(ConsoleEvent.log(levelStr, source, message));
    }

    /**
     * 缩短 logger 名称，只保留最后两段（如 {@code o.e.y.service.impl.AiChatServiceImpl}）。
     */
    private static String shortenLogger(String fullName) {
        if (fullName == null) return "?";
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot < 0) return fullName;
        String className = fullName.substring(lastDot + 1);
        // 取倒数第二个包名缩写
        int secondLastDot = fullName.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0) return className;
        String pkg = fullName.substring(secondLastDot + 1, lastDot);
        return pkg + "." + className;
    }
}
