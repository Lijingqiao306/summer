package org.example.ykd_summer.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户地理位置管理工具。
 * <p>
 * 用同一套 {@link SimpleVectorStore} + JSON 持久化存储位置信息，
 * 与 {@link PersonalTools} 共享向量库。位置数据以「位置信息」category 归档，
 * 每个用户只保留最新的一条位置记录（设置即覆盖）。
 * <p>
 * 位置信息不通过 Advisor 强制注入（避免干扰非位置类查询），
 * 由 LLM 在需要时主动调用 {@link #getMyLocation()} 获取。
 */
@Slf4j
@Component
public class LocationTools {

    private final PersonalTools personalTools;

    public LocationTools(PersonalTools personalTools) {
        this.personalTools = personalTools;
    }

    // ==================== 位置文档检索辅助 ====================

    /** 搜索当前用户的「位置信息」类别文档 */
    private List<Document> findLocationDocs(String userId) {
        SearchRequest request = SearchRequest.builder()
                .query("位置信息 城市 地址 区域 所在")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression(PersonalTools.userIdFilter(userId))
                .build();
        return personalTools.getStore().similaritySearch(request).stream()
                .filter(d -> "位置信息".equals(d.getMetadata().get("category")))
                .collect(Collectors.toList());
    }

    // ==================== Tool 方法 ====================

    @Tool(description = "保存用户的地理位置。如用户说「我在深圳南山」时调用。")
    public String setMyLocation(
            @ToolParam(description = "用户的位置描述，如「深圳南山」「北京朝阳」「广州天河体育西路」") String address) {

        String userId = PersonalTools.getCurrentUserId();
        if (userId == null) {
            log.error("setMyLocation: userId 为空");
            return "系统错误：无法识别用户身份，请稍后再试。";
        }

        // 删除旧的「位置信息」文档
        List<Document> oldDocs = findLocationDocs(userId);
        if (!oldDocs.isEmpty()) {
            List<String> oldIds = oldDocs.stream()
                    .map(Document::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!oldIds.isEmpty()) {
                personalTools.getStore().delete(oldIds);
                log.debug("setMyLocation: 已删除旧的 {} 条位置记录", oldIds.size());
            }
        }

        // 写入新位置
        Map<String, Object> meta = new HashMap<>();
        meta.put("userId", userId);
        meta.put("category", "位置信息");
        meta.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Document doc = new Document(address, meta);
        personalTools.getStore().add(List.of(doc));
        personalTools.persistStore();

        log.info("setMyLocation: userId={}, address={}", userId, address);
        return String.format("已记住你的位置：%s。后续推荐店铺、餐厅等服务时，我会优先按这个位置帮你搜索。", address);
    }

    @Tool(description = "获取已保存的用户地理位置，用于位置相关推荐。")
    public String getMyLocation() {
        String userId = PersonalTools.getCurrentUserId();
        if (userId == null) {
            log.warn("getMyLocation: userId 为空");
            return "【未获取到用户身份】";
        }

        List<Document> docs = findLocationDocs(userId);
        if (docs.isEmpty()) {
            log.debug("getMyLocation: userId={} 未设置位置", userId);
            return "【用户尚未设置位置】";
        }

        // 返回最新的一条位置
        String location = docs.get(0).getText();
        log.debug("getMyLocation: userId={}, location={}", userId, location);
        return location;
    }
}
