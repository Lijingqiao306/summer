package org.example.ykd_summer.config;

import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.config.advisor.BilibiliUploadRagAdvisor;
import org.example.ykd_summer.config.advisor.PersonalRagAdvisor;
import org.example.ykd_summer.tools.AmapTools;
import org.example.ykd_summer.tools.LocationTools;
import org.example.ykd_summer.tools.MailSendTools;
import org.example.ykd_summer.tools.WebSearchTools;
import org.example.ykd_summer.utils.PromptUtils;
import org.example.ykd_summer.tools.BotTools;
import org.example.ykd_summer.tools.PersonalTools;
import org.example.ykd_summer.tools.BilibiliStoreTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

/**
 * AI Chat 配置类
 * 创建 ChatClient Bean，连接 DashScope（阿里云通义千问）
 */
@Configuration
@Slf4j
public class AiChatConfig {

    private static final String SYSTEM_PROMPT =
            PromptUtils.load("prompts/ai-chat-system.txt");

    /**
     * DashScope Embedding 端点模板，WorkspaceId 通过环境变量注入。
     * 参考：https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api
     */
    @Value("${dashscope.workspace-id}")
    private String workspaceId;

    private static final String EMBEDDING_URL_TEMPLATE =
            "https://%s.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    /**
     * 会话记忆：窗口式存储，底层使用 JDBC（H2）持久化。
     * 为不同 Agent 设置独立的窗口大小以优化 token 消耗：
     * - TEXT Agent：8 条（多轮对话需上下文但无需过长）
     * - IMAGE Agent：3 条（图生图基本一次性任务）
     * - BILIBILI Agent：5 条（扫码→继续两步流程）
     * - SPRING Agent：8 条（监测偶尔多轮）
     */
    @Bean
    @Qualifier("textChatMemory")
    public ChatMemory textChatMemory(JdbcChatMemoryRepository repository) {
        log.info("TEXT Agent ChatMemory，窗口大小=8");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(8)
                .build();
    }

    @Bean
    @Qualifier("imageChatMemory")
    public ChatMemory imageChatMemory(JdbcChatMemoryRepository repository) {
        log.info("IMAGE Agent ChatMemory，窗口大小=3");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(3)
                .build();
    }

    @Bean
    @Qualifier("bilibiliChatMemory")
    public ChatMemory bilibiliChatMemory(JdbcChatMemoryRepository repository) {
        log.info("BILIBILI Agent ChatMemory，窗口大小=5");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(5)
                .build();
    }

    @Bean
    @Qualifier("springChatMemory")
    public ChatMemory springChatMemory(JdbcChatMemoryRepository repository) {
        log.info("SPRING Agent ChatMemory，窗口大小=8");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(8)
                .build();
    }

    /** RAG 检索参数：最多返回 5 条，相似度阈值 0.35（降低以覆盖位置型查询与偏好文档的语义距离） */
    private static final int PERSONAL_RAG_TOP_K = 5;
    private static final double PERSONAL_RAG_SIMILARITY_THRESHOLD = 0.35;

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("aiChatClient")
    public ChatClient aiChatClient(OpenAiChatModel openAiChatModel, BotTools botTools,
                                   MailSendTools mailSendTools, PersonalTools personalTools,
                                   LocationTools locationTools, AmapTools amapTools,
                                   WebSearchTools webSearchTools,
                                   @Qualifier("textChatMemory") ChatMemory chatMemory,
                                   VectorStore personalVectorStore,
                                   BilibiliStoreTools bilibiliStoreTools) {
        log.info("正在构建 AI ChatClient（DashScope 通义千问 + 个人信息向量库环绕增强"
                + " + 位置感知 + 高德地图 + webSearch 联网搜索工具 + B站创作档案增强）...");

        log.info("ChatClient 默认Options：enable_search=true,forced_search=true");
        log.info("个人信息 RAG: topK={}, similarityThreshold={}",
                PERSONAL_RAG_TOP_K, PERSONAL_RAG_SIMILARITY_THRESHOLD);

        return ChatClient.builder(openAiChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new PersonalRagAdvisor(personalVectorStore,
                                PERSONAL_RAG_TOP_K, PERSONAL_RAG_SIMILARITY_THRESHOLD),
                        new BilibiliUploadRagAdvisor(bilibiliStoreTools.personalBilibiliVectorStore()),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /** TEXT Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("aiChatTools")
    public List<Object> aiChatTools(BotTools botTools, MailSendTools mailSendTools,
                                     PersonalTools personalTools, LocationTools locationTools,
                                     AmapTools amapTools, WebSearchTools webSearchTools) {
        return List.of(botTools, mailSendTools, personalTools, locationTools, amapTools, webSearchTools);
    }

    /**
     * 独立的 Embedding 模型 Bean，使用 DashScope 向量专用端点。
     * <p>
     * 官方文档（https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api）
     * 明确 Chat 和 Embedding 的 base_url 不同：
     * <ul>
     *   <li>Chat：https://dashscope.aliyuncs.com/compatible-mode/v1</li>
     *   <li>Embedding：https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1</li>
     * </ul>
     *
     * @param openAiApiKey 通过 {@code spring.ai.openai.api-key} 注入
     */
    @Bean
    public EmbeddingModel embeddingModel(@Value("${spring.ai.openai.api-key}") String openAiApiKey,
                                         @Value("${spring.ai.openai.embedding.model}") String embeddingModelName) {
        // 工作空间 ID 缺省时回退到 DashScope 默认兼容端点，避免应用无法启动
        final String embeddingBaseUrl;
        if (workspaceId == null || workspaceId.isBlank()) {
            embeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            log.warn("DASHSCOPE_WORKSPACE_ID 未设置，Embedding 使用默认端点 {}。"
                    + "若向量化接口报 401/403，请设置该环境变量（需 ws- 前缀）。", embeddingBaseUrl);
        } else {
            embeddingBaseUrl = String.format(EMBEDDING_URL_TEMPLATE, workspaceId);
        }
        log.info("正在构建 EmbeddingModel（DashScope 向量专用端点，baseUrl={}, 模型={}）...",
                embeddingBaseUrl, embeddingModelName);

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(openAiApiKey)
                .model(embeddingModelName)
                .build();
        return OpenAiEmbeddingModel.builder().options(options).build();
    }

    /**
     * 应用启动时校验 QQ 邮箱 SMTP 配置是否可用。
     * 校验发件人地址格式，并在认证失败时打印明确错误。
     */
    @Bean
    public CommandLineRunner checkMailConfig(JavaMailSender mailSender,
                                             @Value("${spring.mail.username}") String username) {
        return args -> {
            // 1. 校验发件人地址格式
            if (username == null || username.isBlank()) {
                log.error("QQ邮件SMTP配置校验失败：发件人地址为空！请设置 QQ_MAIL_USERNAME 环境变量。");
                return;
            }
            String candidate = username.trim();
            if (!candidate.contains("@")) {
                if (candidate.matches("\\d{5,12}")) {
                    log.info("QQ邮件SMTP发件人为纯 QQ 号（{}），运行时将自动补全为 {}@qq.com", candidate, candidate);
                } else {
                    log.error("QQ邮件SMTP配置校验失败：发件人地址格式异常（{}），应为完整邮箱或纯数字 QQ 号。", candidate);
                    return;
                }
            } else {
                try {
                    new InternetAddress(candidate).validate();
                } catch (Exception e) {
                    log.error("QQ邮件SMTP配置校验失败：发件人邮箱格式不合法（{}）", candidate);
                    return;
                }
            }
            log.info("QQ邮件SMTP配置校验通过，发件人: {}", candidate);
        };
    }
}
