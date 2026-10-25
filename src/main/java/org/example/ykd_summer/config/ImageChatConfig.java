package org.example.ykd_summer.config;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.utils.PromptUtils;
import org.example.ykd_summer.tools.ImageTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 图片 Agent 的 ChatClient 配置
 * 独立于文本 Agent（AiChatConfig），拥有专属的 System Prompt 与 Tools
 * 共享同一个 ChatMemory（通过 conversationId 隔离用户）
 */
@Configuration
@Slf4j
public class ImageChatConfig {

    private static final String IMAGE_SYSTEM_PROMPT =
            PromptUtils.load("prompts/image-system.txt");

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("imageChatClient")
    public ChatClient imageChatClient(OpenAiChatModel openAiChatModel, ImageTools imageTools,
                                       @Qualifier("imageChatMemory") ChatMemory chatMemory) {
        log.info("正在构建图片 Agent ChatClient（DashScope 通义千问）...");
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(IMAGE_SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /** IMAGE Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("imageChatTools")
    public List<Object> imageChatTools(ImageTools imageTools) {
        return List.of(imageTools);
    }
}
