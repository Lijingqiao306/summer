package org.example.ykd_summer.config;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.tools.BilibiliAgentTools;
import org.example.ykd_summer.utils.PromptUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * BILIBILI Agent 的 ChatClient 配置。
 * <p>
 * BILIBILI Agent 负责处理用户的 B站相关指令（发动态等），
 * 由 Router Agent 统一编排分发。
 * <p>
 * 模型：qwen-turbo（成本更低，B站操作任务无需 plus 级别推理）。
 * 具备 ChatMemory（用户扫码后说"继续"需要上下文记忆来恢复待发内容）。
 * ChatMemory 通过 conversationId = userId + "_bilibili_" + sessionId 隔离。
 */
@Configuration
@Slf4j
public class BilibiliAgentConfig {

    private static final String BILIBILI_SYSTEM_PROMPT =
            PromptUtils.load("prompts/bilibili-system.txt");

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("bilibiliChatClient")
    public ChatClient bilibiliChatClient(OpenAiChatModel openAiChatModel,
                                          BilibiliAgentTools bilibiliAgentTools,
                                          @Qualifier("bilibiliChatMemory") ChatMemory chatMemory) {
        log.info("正在构建 BILIBILI Agent ChatClient（qwen-turbo，发动态工具）...");
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(BILIBILI_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen-turbo")
                        .temperature(0.3))
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /** BILIBILI Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("bilibiliChatTools")
    public List<Object> bilibiliChatTools(BilibiliAgentTools bilibiliAgentTools) {
        return List.of(bilibiliAgentTools);
    }
}
