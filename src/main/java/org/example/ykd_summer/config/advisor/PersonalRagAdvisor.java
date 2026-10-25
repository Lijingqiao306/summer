package org.example.ykd_summer.config.advisor;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.tools.PersonalTools;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 个人信息 RAG Advisor：每次 TEXT Agent 请求前自动从向量库检索当前用户的个人信息，
 * 并将结果注入为系统上下文，无需 LLM 通过 Tool Calling 手动触发。
 * <p>
 * 挂载在 TEXT Agent 的 Advisor 链上，实现「向量库环绕增强」架构：
 * 所有分发到 TEXT Agent 的请求都会先经过此 Advisor，
 * 自动检索与当前问题相关的用户偏好/习惯/健康等信息并注入上下文。
 * <p>
 * 与 Spring AI 原生 {@code QuestionAnswerAdvisor} 的核心区别：
 * <ul>
 *   <li>支持动态 userId 过滤：每次请求从 {@link PersonalTools#getCurrentUserId()}
 *       读取当前用户 ID，构建 per-user 过滤条件</li>
 *   <li>原生 {@code QuestionAnswerAdvisor} 的 filterExpression 是构建时固定的，
 *       无法实现 per-request 级别的 userId 隔离</li>
 * </ul>
 * <p>
 * 线程安全：无内部状态，userId 通过 ThreadLocal 读取，
 * 符合项目中 PersonalTools 的跨线程传递机制。
 * <p>
 * 实现基于 Spring AI 2.0.0 {@link BaseAdvisor} 接口，
 * 通过 {@code Prompt.augmentSystemMessage()} 向系统消息追加 RAG 上下文。
 */
@Slf4j
public class PersonalRagAdvisor implements BaseAdvisor {

    /**
     * 请求级开关：通过 {@code .advisors(a -> a.param(SKIP_RAG_KEY, true))} 设置后，
     * 本次请求跳过个人信息 RAG 检索与注入。
     * 由编排层根据工具类别决议设置——纯 SYSTEM（天气/版本/状态）、MAIL 类请求
     * 与个人信息无关，注入只会浪费 token 并可能污染回复。
     */
    public static final String SKIP_RAG_KEY = "personalRag.skip";

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.35;

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;
    private int order = 0;

    public PersonalRagAdvisor(VectorStore vectorStore) {
        this(vectorStore, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public PersonalRagAdvisor(VectorStore vectorStore, int topK, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // 0) 请求级跳过开关：编排层判定本次请求类别与个人信息无关时设置
        if (Boolean.TRUE.equals(chatClientRequest.context().get(SKIP_RAG_KEY))) {
            log.debug("PersonalRagAdvisor: 请求类别与个人信息无关，跳过 RAG 检索");
            return chatClientRequest;
        }

        // 1) 获取当前用户 ID
        String userId = PersonalTools.getCurrentUserId();
        if (userId == null) {
            log.debug("PersonalRagAdvisor: userId 为空，跳过 RAG 检索");
            return chatClientRequest;
        }

        // 2) 用用户的提问文本作为检索查询
        String query = chatClientRequest.prompt().getUserMessage().getText();
        if (query == null || query.isBlank()) {
            log.debug("PersonalRagAdvisor: userText 为空，跳过 RAG 检索");
            return chatClientRequest;
        }

        // 3) 检索向量库（带 userId 过滤）
        List<Document> docs;
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(PersonalTools.userIdFilter(userId))
                    .build();

            docs = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("PersonalRagAdvisor: 向量库检索异常，跳过 RAG 注入: {}", e.getMessage());
            return chatClientRequest;
        }

        if (docs.isEmpty()) {
            log.debug("PersonalRagAdvisor: 未检索到相关的个人信息，userId={}", userId);
            return chatClientRequest;
        }

        // 4) 过滤掉「位置信息」类别的文档。
        //    位置数据仅供 AmapTools 场景通过 getMyLocation 按需读取，
        //    不应注入到对话上下文中（避免污染非位置类对话）。
        List<Document> personalDocs = docs.stream()
                .filter(d -> {
                    Object cat = d.getMetadata() != null ? d.getMetadata().get("category") : null;
                    return !"位置信息".equals(cat);
                })
                .toList();

        if (personalDocs.isEmpty()) {
            log.debug("PersonalRagAdvisor: 无个人信息（已过滤 {} 条位置信息），userId={}",
                    docs.size() - personalDocs.size(), userId);
            return chatClientRequest;
        }

        // 5) 构建中文引导语的 RAG 上下文
        String ragContext = personalDocs.stream()
                .map(d -> {
                    String cat = d.getMetadata() != null && d.getMetadata().get("category") != null
                            ? (String) d.getMetadata().get("category") : "其他";
                    return "[" + cat + "] " + d.getText();
                })
                .collect(Collectors.joining("\n"));

        String augmentedSystemText = "\n\n【以下是你已存档的个人信息，仅用于内部排序/筛选参考，严禁据此自行添加搜索类别。"
                + "例如：用户问「推荐附近火锅」→ 只搜火锅，禁止额外搜索充电站/4S店/商场等无关类别。"
                + "★ 硬性禁令：你不得在回复中主动提及、列举或论证用户的兴趣/偏好（如「结合你作为赛博朋克爱好者…」「契合你的鸣潮喜好」等），"
                + "也严禁为贴合兴趣而编造、夸大或脑补事实：不得给作品（电影/游戏/书籍/活动等）添加真实内容中不存在的设定、彩蛋、联名、IP 元素、OST 参与或剧情细节。"
                + "兴趣档案只可静默地用于调整推荐条目的先后顺序，回复正文必须完全基于搜索/检索返回的真实内容，"
                + "且不得出现任何「因为你喜欢XX所以推荐」式的论证句式。除非用户明确说「按我的喜好推荐」，否则不要显式引用兴趣。】\n"
                + ragContext;

        // 6) 使用 Prompt.augmentSystemMessage() 追加到已存在的 system 消息末尾
        log.debug("PersonalRagAdvisor: 已注入 {} 条个人信息，userId={}, queryLen={}",
                personalDocs.size(), userId, query.length());

        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentSystemMessage(augmentedSystemText))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // RAG 注入是单向的，不需要后处理
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
