package org.example.ykd_summer.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 评论记录持久化存储（已通知 + 已审核）。
 * <p>
 * 存储路径: data/notified_comments.json
 * <p>
 * 数据结构:
 * <pre>
 * {
 *   "notified":  [rpid, ...],   // 已推送过高赞提醒
 *   "moderated": [rpid, ...]    // 已审核过（避免重复审核浪费 token）
 * }
 * </pre>
 */
@Slf4j
@Component
public class NotifiedCommentStore {

    private static final Path STORE_FILE = Path.of("data", "notified_comments.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已推送过高赞提醒的 rpid */
    private Set<Long> notifiedRpids = Collections.synchronizedSet(new LinkedHashSet<>());

    /** 已审核过的 rpid（含已通知和已审核未通知的） */
    private Set<Long> moderatedRpids = Collections.synchronizedSet(new LinkedHashSet<>());

    @PostConstruct
    void load() {
        if (!Files.exists(STORE_FILE)) {
            log.info("首次运行，未找到评论记录文件");
            return;
        }
        try {
            String raw = Files.readString(STORE_FILE);
            // 兼容旧格式：纯数组 → 视为 notified
            if (raw.trim().startsWith("[")) {
                Set<Long> loaded = objectMapper.readValue(
                        raw, new TypeReference<>() {});
                notifiedRpids = Collections.synchronizedSet(new LinkedHashSet<>(loaded));
                // 旧通知记录视为已审核，避免重复审核浪费 token
                moderatedRpids = Collections.synchronizedSet(new LinkedHashSet<>(loaded));
                log.info("加载已通知评论（旧格式 → 自动升级）: {} 条", loaded.size());
                // 升级为新格式
                persist();
            } else {
                ObjectNode root = (ObjectNode) objectMapper.readTree(raw);
                if (root.has("notified")) {
                    notifiedRpids = Collections.synchronizedSet(
                            new LinkedHashSet<>(objectMapper.convertValue(
                                    root.get("notified"), new TypeReference<Set<Long>>() {}))
                    );
                }
                if (root.has("moderated")) {
                    moderatedRpids = Collections.synchronizedSet(
                            new LinkedHashSet<>(objectMapper.convertValue(
                                    root.get("moderated"), new TypeReference<Set<Long>>() {}))
                    );
                }
                log.info("加载评论记录: 已通知{}条, 已审核{}条",
                        notifiedRpids.size(), moderatedRpids.size());
            }
        } catch (IOException e) {
            log.warn("加载评论记录失败，使用空集合: {}", e.getMessage());
        }
    }

    // ---- 高赞通知 ----

    public boolean isNotified(long rpid) {
        return notifiedRpids.contains(rpid);
    }

    public void markNotified(long rpid) {
        notifiedRpids.add(rpid);
        moderatedRpids.add(rpid); // 通知过的也视为审核过
    }

    // ---- 审核追踪 ----

    public boolean isModerated(long rpid) {
        return moderatedRpids.contains(rpid);
    }

    public void markModerated(Set<Long> rpids) {
        moderatedRpids.addAll(rpids);
    }

    // ---- 持久化 ----

    public synchronized void persist() {
        try {
            Files.createDirectories(STORE_FILE.getParent());
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode notifiedArr = objectMapper.createArrayNode();
            notifiedRpids.forEach(notifiedArr::add);
            root.set("notified", notifiedArr);
            ArrayNode moderatedArr = objectMapper.createArrayNode();
            moderatedRpids.forEach(moderatedArr::add);
            root.set("moderated", moderatedArr);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(STORE_FILE.toFile(), root);
        } catch (IOException e) {
            log.error("持久化评论记录失败: {}", e.getMessage());
        }
    }

    /**
     * 清理过期的记录（保留最近 N 条），防止文件无限增长。
     */
    public synchronized void trimToSize(int maxSize) {
        if (notifiedRpids.size() > maxSize) {
            Set<Long> trimmed = new LinkedHashSet<>(notifiedRpids);
            while (trimmed.size() > maxSize) {
                trimmed.remove(trimmed.iterator().next());
            }
            notifiedRpids = Collections.synchronizedSet(trimmed);
        }
        if (moderatedRpids.size() > maxSize) {
            Set<Long> trimmed = new LinkedHashSet<>(moderatedRpids);
            while (trimmed.size() > maxSize) {
                trimmed.remove(trimmed.iterator().next());
            }
            moderatedRpids = Collections.synchronizedSet(trimmed);
        }
        persist();
    }
}
