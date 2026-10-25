package org.example.ykd_summer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.utils.PromptUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由 ChatClient 配置。
 * <p>
 * 路由 Agent 职责：任务编排。
 * 使用多模态模型（qwen-vl-plus），接收文本和图片，输出 JSON 格式的步骤计划：
 * <pre>{@code
 * {
 *   "steps": [
 *     {"index": 0, "agent": "TEXT", "task": "...", "input_hint": "...", "depends_on": []},
 *     {"index": 1, "agent": "IMAGE", "task": "...", "input_hint": "...", "depends_on": [0]}
 *   ]
 * }
 * }</pre>
 * <p>
 * 架构分层：
 * <pre>
 *   用户消息（文本 / 文本+图片）
 *      ↓
 *   RouterChatClient（qwen-vl-plus，输出 JSON 步骤计划）
 *      ↓
 *   OrchestrationService（解析 → 拓扑排序 → 逐步执行 → 聚合结果）
 *      ↓
 *   ├── TEXT 步骤 → AiChatClient（BotTools：天气 / 版本 / 状态 / 闲聊）
 *   └── IMAGE 步骤 → ImageChatClient（ImageTools：文生图 / 图片识别）
 * </pre>
 */
@Configuration
@Slf4j
public class RouterChatConfig {

    /**
     * 路由 Agent 使用的多模态模型，需要能同时理解文本和图片。
     */
    private static final String ROUTER_MODEL = "qwen-vl-plus";

    private static final String ROUTER_SYSTEM_PROMPT =
            PromptUtils.load("prompts/router-system.txt");

    /**
     * 路由 ChatClient —— 用于任务编排，使用多模态模型。
     * 不注册任何业务工具，不参与会话记忆，不使用 @Primary。
     * 模型覆盖在 Service 层通过 prompt().options() 完成。
     */
    @Bean
    public ChatClient routerChatClient(OpenAiChatModel openAiChatModel) {
        log.info("正在构建路由 ChatClient（多模态任务编排，模型={}，无业务工具）...", ROUTER_MODEL);
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(ROUTER_SYSTEM_PROMPT)
                .build();
    }

    /**
     * ObjectMapper Bean，用于解析路由 Agent 返回的 JSON 编排计划。
     * Spring Boot 4.x 的 spring-boot-starter-webmvc 不含 JSON 自动配置，
     * 需显式声明。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
