package org.example.ykd_summer.config.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B站创作档案 RAG Advisor：每次 TEXT Agent 请求前自动从创作档案向量库检索
 * 历史上传记录，并将结果注入为系统上下文。
 * <p>
 * 与 {@link PersonalRagAdvisor} 互补——PersonalRagAdvisor 注入用户个人信息（偏好、习惯），
 * 本 Advisor 注入 B站创作档案（历史标题、分区、标签），
 * 帮助 AI 在回答创作方向相关问题时理解用户的创作风格。
 * <p>
 * <b>意图判断机制</b>：仅当用户提问中包含 B站创作相关的关键词时，
 * 才进行向量检索和上下文注入，避免在纯消费类推荐（如"推荐几家饭店"）中产生歧义污染。
 * <p>
 * 挂载在 TEXT Agent 的 Advisor 链上，优先级低于 PersonalRagAdvisor（order=1）。
 * <p>
 * 线程安全：无内部状态，从向量库检索数据。
 */
@Slf4j
public class BilibiliUploadRagAdvisor implements BaseAdvisor {

    private static final int DEFAULT_TOP_K = 10;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    /** 判断用户意图是否为 B站创作相关的关键词集合 */
    private static final Set<String> CREATION_KEYWORDS = Set.of(
            "创作", "视频", "投稿", "选题", "b站", "bilibili", "上传", "分区", "标签",
            "标题", "内容方向", "拍什么", "做什么内容", "发什么", "更新", "频道", "制作",
            "剪辑", "录制", "直播", "稿件", "封面", "播放量", "粉丝", "涨粉", "推荐方向"
    );

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;
    private int order = 1;

    public BilibiliUploadRagAdvisor(VectorStore vectorStore) {
        this(vectorStore, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public BilibiliUploadRagAdvisor(VectorStore vectorStore, int topK, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // 1) 获取用户提问文本
        String query = chatClientRequest.prompt().getUserMessage().getText();
        if (query == null || query.isBlank()) {
            log.debug("BilibiliUploadRagAdvisor: userText 为空，跳过 RAG 检索");
            return chatClientRequest;
        }

        // 2) 意图判断：仅当用户提问涉及 B站创作相关内容时才检索
        String lowerQuery = query.toLowerCase();
        boolean hasCreationIntent = CREATION_KEYWORDS.stream().anyMatch(lowerQuery::contains);
        if (!hasCreationIntent) {
            log.debug("BilibiliUploadRagAdvisor: 用户提问不涉及创作相关意图，跳过 RAG 检索");
            return chatClientRequest;
        }

        // 3) 检索创作档案向量库（不过滤 userId，B站账号是全局共享的）
        List<Document> docs;
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .build();

            docs = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("BilibiliUploadRagAdvisor: 向量库检索异常，跳过 RAG 注入: {}", e.getMessage());
            return chatClientRequest;
        }

        if (docs.isEmpty()) {
            log.debug("BilibiliUploadRagAdvisor: 未检索到相关的创作档案");
            return chatClientRequest;
        }

        // 4) 构建中文引导语的 RAG 上下文
        String ragContext = docs.stream()
                .map(d -> {
                    String title = d.getMetadata() != null && d.getMetadata().get("title") != null
                            ? (String) d.getMetadata().get("title") : "";
                    String partition = d.getMetadata() != null && d.getMetadata().get("partition") != null
                            ? (String) d.getMetadata().get("partition") : "";
                    String tags = d.getMetadata() != null && d.getMetadata().get("tags") != null
                            ? (String) d.getMetadata().get("tags") : "";
                    return String.format("- 标题「%s」分区:%s 标签:[%s]", title, partition, tags);
                })
                .collect(Collectors.joining("\n"));

        String augmentedSystemText = "\n\n【以下是你（助手）帮助用户在B站上发布过的视频创作档案，"
                + "仅供参考用户的创作风格和选题偏好。"
                + "如果用户询问创作方向、内容策划等问题时，可以结合这些历史给出个性化建议。"
                + "严禁在无关对话中主动提及这些记录。】\n"
                + ragContext;

        log.debug("BilibiliUploadRagAdvisor: 已注入 {} 条创作档案，queryLen={}",
                docs.size(), query.length());

        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentSystemMessage(augmentedSystemText))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @SuppressWarnings("unused")
    public void setOrder(int order) {
        this.order = order;
    }
}
