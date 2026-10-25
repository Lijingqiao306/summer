package org.example.ykd_summer.config;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.tools.SpringMonitorConfigTools;
import org.example.ykd_summer.tools.SpringMonitorTools;
import org.example.ykd_summer.tools.MailSendTools;
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
 * Spring 监测 Agent 的 ChatClient 配置。
 * <p>
 * 独立的 Agent，拥有完整的 Spring 项目监测能力（查询 + 配置 + 邮件），
 * 由 Router Agent 统一编排分发，不挂载到 TEXT Agent。
 * <p>
 * 模型：qwen-turbo（成本更低，监测任务无需 plus 级别推理）。
 * 具备 ChatMemory（监测配置修改可能需要多轮上下文）。
 */
@Configuration
@Slf4j
public class SpringMonitorAgentConfig {

    private static final String SPRING_MONITOR_SYSTEM_PROMPT =
            PromptUtils.load("prompts/spring-monitor-system.txt");

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("springMonitorAgent")
    public ChatClient springMonitorAgent(OpenAiChatModel openAiChatModel,
                                         SpringMonitorTools queryTools,
                                         SpringMonitorConfigTools configTools,
                                         MailSendTools mailSendTools,
                                         @Qualifier("springChatMemory") ChatMemory chatMemory) {
        log.info("正在构建 Spring 监测 Agent ChatClient（qwen-turbo，含查询+配置+邮件工具）...");
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(SPRING_MONITOR_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen-turbo")
                        .temperature(0.3))
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /** SPRING Agent 工具类列表，供 OrchestrationServiceImpl 运行时过滤启用/禁用 */
    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("springChatTools")
    public List<Object> springChatTools(SpringMonitorTools queryTools,
                                         SpringMonitorConfigTools configTools,
                                         MailSendTools mailSendTools) {
        return List.of(queryTools, configTools, mailSendTools);
    }
}
