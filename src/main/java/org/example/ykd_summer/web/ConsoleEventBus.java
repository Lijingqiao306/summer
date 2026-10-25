package org.example.ykd_summer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 控制台事件总线。
 * <p>
 * 维护一个环形缓冲（最近 500 条），管理 SSE 订阅者列表。
 * 多线程安全：事件发布和 SSE 发送均可并发执行。
 */
@Slf4j
@Component
public class ConsoleEventBus {

    private static final int MAX_HISTORY = 500;

    private final Deque<ConsoleEvent> ringBuffer = new ArrayDeque<>(MAX_HISTORY + 1);
    private final ReentrantLock bufferLock = new ReentrantLock();

    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 与 ConsoleEvent 保持一致的北京时间格式化器 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 将事件序列化为 JSON 字符串，供 SSE 推送和历史回放使用。
     */
    public String toJson(ConsoleEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("ConsoleEvent JSON 序列化失败", e);
            return "{\"timestamp\":\"" + TIME_FMT.format(java.time.Instant.now())
                    + "\",\"level\":\"ERROR\",\"source\":\"ConsoleEventBus\","
                    + "\"type\":\"LOG\",\"message\":\"JSON序列化失败\"}";
        }
    }

    /**
     * 发布一条事件到总线和所有订阅者。
     */
    public void publish(ConsoleEvent event) {
        // 写入环形缓冲
        bufferLock.lock();
        try {
            if (ringBuffer.size() >= MAX_HISTORY) {
                ringBuffer.pollFirst();
            }
            ringBuffer.offerLast(event);
        } finally {
            bufferLock.unlock();
        }

        // 广播给所有 SSE 订阅者
        String json = toJson(event);
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .name("console-event")
                        .data(json));
            } catch (Exception e) {
                // 客户端断开或 emitter 已关闭，直接移除
                subscribers.remove(emitter);
            }
        }
    }

    /**
     * 注册 SSE 订阅者。
     * 超时时间设 30 分钟，完成后自动从订阅列表移除。
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            subscribers.remove(emitter);
        });
        emitter.onError(e -> subscribers.remove(emitter));
        return emitter;
    }

    /**
     * 获取最近 N 条历史事件。
     */
    public List<ConsoleEvent> getHistory(int limit) {
        bufferLock.lock();
        try {
            int size = ringBuffer.size();
            int start = Math.max(0, size - Math.min(limit, size));
            List<ConsoleEvent> list = new ArrayList<>(ringBuffer);
            return list.subList(start, size);
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * 获取订阅者数量。
     */
    public int subscriberCount() {
        return subscribers.size();
    }

}
