package org.example.ykd_summer.config;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.tools.BrowserTools;
import org.example.ykd_summer.utils.PromptUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Browser Chat 配置类
 * 创建独立的 Browser ChatClient Bean，仅绑定浏览器工具（openWebPage 等），
 * 与其他 Agent（TEXT/IMAGE/FILE）工具隔离，防止 LLM 越狱调用。
 */
@Configuration
@Slf4j
public class BrowserChatConfig {

    private static final String BROWSER_SYSTEM_PROMPT =
            PromptUtils.load("prompts/browser-system.txt");

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("browserChatClient")
    public ChatClient browserChatClient(OpenAiChatModel openAiChatModel, BrowserTools browserTools) {
        log.info("正在构建 Browser ChatClient（DashScope 通义千问）...");
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(BROWSER_SYSTEM_PROMPT)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /** BROWSER Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("browserChatTools")
    public List<Object> browserChatTools(BrowserTools browserTools) {
        return List.of(browserTools);
    }
}
