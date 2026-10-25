package org.example.ykd_summer.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 个人信息记忆工具集合，作为 TEXT Agent 的环绕增强挂载。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>持久化用户的所有个性化信息（饮食偏好、兴趣爱好、生活习惯、健康信息等）到向量库</li>
 *   <li>按 userId 隔离向量库数据，确保不同用户档案互不可见</li>
 *   <li>提供删除/查看等管理操作</li>
 * </ul>
 * <p>
 * 个人信息的检索不通过 Tool Calling，而是由 {@code PersonalRagAdvisor}
 * 在每次 TEXT Agent 请求前自动从向量库检索并注入上下文，
 * 确保个人信息强制可见，不依赖 LLM 自主决策。
 * <p>
 * 跨线程数据传递：userId 通过 ThreadLocal（同线程快速路径）+
 * ConcurrentHashMap（跨线程回退）传递。
 */
@Slf4j
@Component
public class PersonalTools {

    // ==================== userId 跨线程传递 ====================

    /** ThreadLocal 路径（同线程快速路径） */
    static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    /** ConcurrentHashMap 路径（跨线程回退） */
    private static final ConcurrentHashMap<String, String> USER_ID_STORE = new ConcurrentHashMap<>();

    /** 设置当前线程的用户 ID（OrchestrationService 在执行步骤前调用） */
    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
        USER_ID_STORE.put(Thread.currentThread().getName(), userId);
    }

    /** 清理当前线程的用户 ID（OrchestrationService 在执行步骤后调用） */
    public static void clearCurrentUserId() {
        CURRENT_USER_ID.remove();
    }

    /** 读取当前用户 ID（优先 ThreadLocal，回退 ConcurrentHashMap） */
    public static String getCurrentUserId() {
        String userId = CURRENT_USER_ID.get();
        if (userId != null) return userId;
        // 跨线程回退：遍历 USER_ID_STORE 找任意可用值
        if (!USER_ID_STORE.isEmpty()) {
            return USER_ID_STORE.values().iterator().next();
        }
        return null;
    }

    // ==================== 向量库管理 ====================

    private final EmbeddingModel embeddingModel;

    /**
     * 个人信息向量库持久化文件路径（可配置，支持相对路径，启动时自动转为绝对路径）。
     * <p>
     * 默认值 ./data/personal_vector_store.json 相对于 JVM 工作目录（user.dir）。
     * 可在 application.yml 中通过 personal.vector-store.file-path 覆盖。
     */
    @Value("${personal.vector-store.file-path:./data/personal_vector_store.json}")
    private String configuredPath;

    /** 解析后的绝对路径（@PostConstruct 中初始化） */
    private Path storeFile;

    /** 内存中的向量库实例（懒加载，双重检查锁），volatile 保证安全的发布 */
    private volatile SimpleVectorStore store;

    public PersonalTools(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 启动时即刻：解析路径为绝对路径 + 预加载向量库 + 日志验证。
     * <p>
     * 若文件存在则尝试加载并输出文档数量，提前暴露路径错误或 JSON 损坏问题。
     */
    @PostConstruct
    void init() {
        // 解析为绝对路径，避免依赖 JVM 工作目录的不确定性
        Path rawPath = Path.of(configuredPath);
        if (rawPath.isAbsolute()) {
            storeFile = rawPath;
        } else {
            storeFile = Path.of(System.getProperty("user.dir", ".")).resolve(rawPath).normalize().toAbsolutePath();
        }
        log.info("个人信息向量库路径: {}", storeFile);

        // 预加载向量库（若存在）
        SimpleVectorStore s = getStore();
        if (s != null && Files.exists(storeFile)) {
            try {
                // 用宽松条件查询所有文档做计数（需绕过 userId 过滤，故查整个 store）
                long docCount = s.similaritySearch(
                        SearchRequest.builder().query("所有").topK(1000).similarityThreshold(0.0).build()
                ).size();
                log.info("个人信息向量库已就绪: path={}, docCount={}", storeFile, docCount);
            } catch (Exception e) {
                log.warn("个人信息向量库计数失败（不影响功能）: {}", e.getMessage());
            }
        }
    }

    /**
     * 将个人信息向量库暴露为 Spring Bean，供 {@code PersonalRagAdvisor} 注入使用。
     * <p>
     * PersonalRagAdvisor 在每次 TEXT Agent 请求前自动检索该向量库，
     * 按 userId 过滤后将匹配的个人信息文档注入为系统上下文。
     */
    @Bean
    public VectorStore personalVectorStore() {
        return getStore();
    }

    /** 加载或创建向量库（包级可见，供同一包下的 LocationTools 复用） */
    SimpleVectorStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    // 先用局部变量完成全部初始化，最后再赋值给 volatile 字段，
                    // 确保其他线程通过 volatile 读拿到的一定是完整初始化的 store
                    SimpleVectorStore newStore = SimpleVectorStore.builder(embeddingModel).build();
                    Path file = storeFile != null ? storeFile : Path.of(configuredPath);
                    if (Files.exists(file)) {
                        try {
                            newStore.load(file.toFile());
                            log.info("个人信息向量库已加载: file={}", file);
                        } catch (Exception e) {
                            log.warn("加载个人信息向量库失败，将使用空库: {}", e.getMessage());
                            newStore = SimpleVectorStore.builder(embeddingModel).build();
                        }
                    } else {
                        log.info("个人信息向量库文件不存在，将创建新库: file={}", file);
                    }
                    store = newStore; // volatile 写——在所有初始化完成后
                }
            }
        }
        return store;
    }

    /** 持久化向量库到文件（包级可见，供同一包下的 LocationTools 复用） */
    synchronized void persistStore() {
        try {
            Path file = storeFile != null ? storeFile : Path.of(configuredPath);
            Files.createDirectories(file.getParent());
            getStore().save(file.toFile());
            log.debug("个人信息向量库已持久化: file={}", file);
        } catch (Exception e) {
            log.error("持久化个人信息向量库失败: {}", e.getMessage());
        }
    }

    /** 构建 userId 过滤表达式：userId == 'xxx' */
    public static Filter.Expression userIdFilter(String userId) {
        return new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("userId"),
                new Filter.Value(userId)
        );
    }

    // ==================== Tool 方法 ====================

    @Tool(description = "保存用户的个性化信息（偏好/习惯/健康/兴趣等）。每次一条。")
    public String savePersonalInfo(
            @ToolParam(description = "信息内容，如「喜欢吃川菜」「对花生过敏」")
            String content,
            @ToolParam(description = "类别：饮食偏好、兴趣爱好、生活习惯、健康、个人偏好、工作学习、社会关系、其他") String category) {

        String userId = getCurrentUserId();
        if (userId == null) {
            log.error("savePersonalInfo: userId 为空，无法保存");
            return "系统错误：无法识别用户身份，请稍后再试。";
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("userId", userId);
        meta.put("category", category);
        meta.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Document doc = new Document(content, meta);
        getStore().add(List.of(doc));
        persistStore();

        log.info("savePersonalInfo: userId={}, category={}, contentLen={}", userId, category, content.length());
        return String.format("已保存「%s → %s」到你的个人档案。后续相关问题时，我会参考这条信息。", category, content);
    }

    @Tool(description = "删除用户个人档案中的指定信息。当用户要求删除或修改某项个人信息时使用。")
    public String deletePersonalInfo(
            @ToolParam(description = "要删除的信息关键词，用于匹配档案中的记录") String keyword) {

        String userId = getCurrentUserId();
        if (userId == null) {
            return "系统错误：无法识别用户身份。";
        }

        try {
            SimpleVectorStore currentStore = getStore();
            SearchRequest request = SearchRequest.builder()
                    .query(keyword)
                    .topK(10)
                    .similarityThreshold(0.3)
                    .filterExpression(userIdFilter(userId))
                    .build();

            List<Document> docs = currentStore.similaritySearch(request);

            if (docs.isEmpty()) {
                return "未找到与「" + keyword + "」相关的个人档案记录。";
            }

            List<String> idsToDelete = docs.stream()
                    .map(Document::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (idsToDelete.isEmpty()) {
                return "未找到可删除的记录。";
            }

            currentStore.delete(idsToDelete);
            persistStore();

            log.info("deletePersonalInfo: userId={}, keyword={}, deleted={}", userId, keyword, idsToDelete.size());
            return String.format("已删除 %d 条与「%s」相关的个人档案记录。", idsToDelete.size(), keyword);
        } catch (Exception e) {
            log.error("deletePersonalInfo 异常: {}", e.getMessage());
            return "删除失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出当前用户所有已保存的个人档案信息概览。")
    public String listMyPersonalInfo() {

        String userId = getCurrentUserId();
        if (userId == null) {
            return "系统错误：无法识别用户身份。";
        }

        try {
            SimpleVectorStore currentStore = getStore();
            // 用一个宽松的搜索拉取所有属于当前用户的文档
            SearchRequest request = SearchRequest.builder()
                    .query("饮食偏好 兴趣爱好 生活习惯 健康 过敏史 疾病史 用药史 个人偏好 工作学习 社会关系")
                    .topK(50)
                    .similarityThreshold(0.0)
                    .filterExpression(userIdFilter(userId))
                    .build();

            List<Document> docs = currentStore.similaritySearch(request);

            if (docs.isEmpty()) {
                return "你的个人档案目前为空。你可以告诉我你的饮食偏好、兴趣爱好、健康信息等，我会帮你记录。";
            }

            // 按 category 分组
            Map<String, List<Document>> grouped = docs.stream()
                    .collect(Collectors.groupingBy(
                            d -> (String) d.getMetadata().getOrDefault("category", "其他"),
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));

            StringBuilder sb = new StringBuilder("你的个人档案概览：\n");
            for (Map.Entry<String, List<Document>> entry : grouped.entrySet()) {
                sb.append("\n【").append(entry.getKey()).append("】\n");
                for (Document doc : entry.getValue()) {
                    sb.append("  · ").append(doc.getText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("listMyPersonalInfo 异常: {}", e.getMessage());
            return "获取个人档案失败：" + e.getMessage();
        }
    }
}
