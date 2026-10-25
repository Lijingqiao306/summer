package org.example.ykd_summer.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.VideoMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * B站创作档案向量库管理工具。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>视频发布成功后，自动将标题、分区、标签等元数据存入独立向量库</li>
 *   <li>检索用户的历史创作记录，用于创作方向推荐</li>
 *   <li>向量库文件独立于个人信息向量库：{@code personal_bilibili_store.json}</li>
 * </ul>
 * <p>
 * 注意：B站账号是全局共享的（一套 cookies），因此向量库中不强制按 userId 隔离。
 * 创作者档案对不同用户对话都是可见的（创作方向推荐面向账号本身）。
 */
@Slf4j
@Component
public class BilibiliStoreTools {

    /** 向量库持久化文件路径 */
    @Value("${bilibili.store.file-path:./data/personal_bilibili_store.json}")
    private String configuredPath;

    private Path storeFile;
    private volatile SimpleVectorStore store;
    private final EmbeddingModel embeddingModel;

    public BilibiliStoreTools(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    void init() {
        Path rawPath = Path.of(configuredPath);
        if (rawPath.isAbsolute()) {
            storeFile = rawPath;
        } else {
            storeFile = Path.of(System.getProperty("user.dir", "."))
                    .resolve(rawPath).normalize().toAbsolutePath();
        }
        log.info("B站创作档案向量库路径: {}", storeFile);

        SimpleVectorStore s = getStore();
        if (s != null && Files.exists(storeFile)) {
            try {
                long docCount = s.similaritySearch(
                        SearchRequest.builder().query("所有").topK(1000).similarityThreshold(0.0).build()
                ).size();
                log.info("B站创作档案向量库已就绪: path={}, docCount={}", storeFile, docCount);
            } catch (Exception e) {
                log.warn("B站创作档案向量库计数失败（不影响功能）: {}", e.getMessage());
            }
        }
    }

    /** 将创作档案向量库暴露为 Spring Bean，供 Advisor 和 Scheduler 注入 */
    @Bean
    public VectorStore personalBilibiliVectorStore() {
        return getStore();
    }

    SimpleVectorStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    SimpleVectorStore newStore = SimpleVectorStore.builder(embeddingModel).build();
                    Path file = storeFile != null ? storeFile : Path.of(configuredPath);
                    if (Files.exists(file)) {
                        try {
                            newStore.load(file.toFile());
                            log.info("B站创作档案向量库已加载: file={}", file);
                        } catch (Exception e) {
                            log.warn("加载B站创作档案向量库失败，将使用空库: {}", e.getMessage());
                            newStore = SimpleVectorStore.builder(embeddingModel).build();
                        }
                    } else {
                        log.info("B站创作档案向量库文件不存在，将创建新库: file={}", file);
                    }
                    store = newStore;
                }
            }
        }
        return store;
    }

    synchronized void persistStore() {
        try {
            Path file = storeFile != null ? storeFile : Path.of(configuredPath);
            Files.createDirectories(file.getParent());
            getStore().save(file.toFile());
            log.debug("B站创作档案向量库已持久化: file={}", file);
        } catch (Exception e) {
            log.error("持久化B站创作档案向量库失败: {}", e.getMessage());
        }
    }

    /**
     * 视频发布成功后调用，将元数据存入创作档案。
     * <p>
     * 向量化文本（content）格式：
     *   "{标题} [分区:{主分区}/{子分区}] [标签:{tag1, tag2, ...}]"
     * <p>
     * metadata 包含：userId, title, tags, partition, tid, category, timestamp
     */
    public void saveUploadRecord(String userId, VideoMetadata metadata) {
        // 构建向量化文本
        String tagStr = metadata.tags() != null && !metadata.tags().isEmpty()
                ? String.join(", ", metadata.tags()) : "无标签";
        String partitionStr = metadata.partitionMain() + "/" + metadata.partitionSub();
        String content = String.format("%s [分区:%s] [标签:%s]",
                metadata.title(), partitionStr, tagStr);

        // 构建元数据
        Map<String, Object> meta = new HashMap<>();
        meta.put("userId", userId != null ? userId : "unknown");
        meta.put("title", metadata.title());
        meta.put("tags", tagStr);
        meta.put("partition", partitionStr);
        meta.put("tid", metadata.tid());
        meta.put("category", "bilibili_upload");
        meta.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Document doc = new Document(content, meta);
        getStore().add(List.of(doc));
        persistStore();

        log.info("B站创作档案已保存: userId={}, title={}, partition={}, tags={}",
                userId, metadata.title(), partitionStr, tagStr);
    }

    /**
     * 检索用户的历史创作记录（用于 AI 生成个性化创作建议）。
     * <p>
     * 不按 userId 过滤——B站账号是全局共享的，所有历史记录都属于同一个创作账户。
     *
     * @param limit 最多返回条数
     * @return 创作记录文档列表
     */
    public List<Document> getUserUploadHistory(int limit) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query("视频 标题 分区 标签 上传 创作")
                    .topK(limit)
                    .similarityThreshold(0.0)
                    .build();

            List<Document> docs = getStore().similaritySearch(request);
            log.debug("B站创作档案检索: 返回 {} 条记录", docs.size());
            return docs;
        } catch (Exception e) {
            log.warn("B站创作档案检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 构建 userId 过滤表达式（供 Advisor 使用，目前不强制按用户隔离） */
    public static Filter.Expression userIdFilter(String userId) {
        return new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("userId"),
                new Filter.Value(userId)
        );
    }
}
