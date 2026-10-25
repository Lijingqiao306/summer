package org.example.ykd_summer.config;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.utils.PromptUtils;
import org.example.ykd_summer.tools.FileTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 文件 Agent 的 ChatClient 配置。
 * <p>
 * 独立于文本 Agent（AiChatConfig）和图片 Agent（ImageChatConfig），
 * 拥有专属的 System Prompt 与 FileTools。不使用 ChatMemory（无状态文档问答）。
 */
@Configuration
@Slf4j
public class FileChatConfig {

    private static final String FILE_SYSTEM_PROMPT =
            PromptUtils.load("prompts/file-system.txt");

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("fileChatClient")
    public ChatClient fileChatClient(OpenAiChatModel openAiChatModel, FileTools fileTools) {
        log.info("正在构建文件 Agent ChatClient（DashScope 通义千问）...");
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(FILE_SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /** FILE Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("fileChatTools")
    public List<Object> fileChatTools(FileTools fileTools) {
        return List.of(fileTools);
    }
}
