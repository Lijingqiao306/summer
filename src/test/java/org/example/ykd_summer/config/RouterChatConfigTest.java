package org.example.ykd_summer.config;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由 Agent（RouterChatClient）集成测试
 * <p>
 * 验证路由 LLM 能根据用户意图正确选择工具：
 * <ul>
 *   <li>文本类意图 → 调用 BotTools（queryWeather / getAppVersion / getServiceStatus）</li>
 *   <li>图片类意图 → 调用 ImageTools（generateImage / describeImage）</li>
 *   <li>闲聊/帮助 → 直接友好回复</li>
 * </ul>
 * <p>
 * 依赖真实 DashScope API Key（OPENAI_API_KEY 环境变量）。
 * 文生图测试会产生实际费用。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RouterChatConfigTest {

    private static final Logger log = LoggerFactory.getLogger(RouterChatConfigTest.class);

    @Autowired
    private ChatClient routerChatClient;

    @Autowired
    private ChatMemory chatMemory;

    private static final String TEST_USER = "test-router-user";

    // ========================
    // 文本类路由测试
    // ========================

    @Test
    @Order(1)
    void shouldHandleGreeting() {
        chatMemory.clear(TEST_USER);

        log.info("===== 路由测试：问候 =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user("你好")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("问候应返回友好回复")
                .isNotBlank()
                .doesNotContain("失败")
                .doesNotContain("不可用");
    }

    @Test
    @Order(2)
    void shouldHandleHelpRequest() {
        log.info("===== 路由测试：帮助请求 =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user("你能做什么")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("帮助请求应列出功能")
                .isNotBlank()
                .doesNotContain("失败");
    }

    @Test
    @Order(3)
    void shouldCallGetAppVersionForVersionQuery() {
        log.info("===== 路由测试：版本查询 → getAppVersion =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user("版本号是多少")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("版本查询应调用 getAppVersion 工具并返回版本号")
                .contains("0.0.1");
    }

    @Test
    @Order(4)
    void shouldAnswerGeneralQuestion() {
        log.info("===== 路由测试：通用问答 =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user("1+1等于几")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("通用问答应包含正确答案")
                .isNotBlank()
                .contains("2");
    }

    // ========================
    // 图片类路由测试
    // ========================

    @Test
    @Order(5)
    void shouldCallGenerateImageForImageGeneration() {
        chatMemory.clear(TEST_USER + "-img");

        log.info("===== 路由测试：文生图 → generateImage =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER + "-img"))
                .user("画一只坐在窗边看雨的橘猫")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("文生图请求应调用 generateImage 工具并返回图片 URL")
                .contains("http");
        assertThat(reply)
                .doesNotContain("失败")
                .doesNotContain("不可用");
    }

    @Test
    @Order(6)
    void shouldCallGenerateImageWithStyle() {
        chatMemory.clear(TEST_USER + "-img-style");

        log.info("===== 路由测试：文生图（带风格）→ generateImage =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER + "-img-style"))
                .user("生成一张星空的图片，卡通风格")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("带风格的文生图应调用 generateImage 工具")
                .contains("http");
        assertThat(reply)
                .doesNotContain("失败");
    }

    @Test
    @Order(7)
    void shouldAnswerImageCapabilityQuestion() {
        log.info("===== 路由测试：图片功能咨询 =====");
        String reply = routerChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user("你能画图吗")
                .call()
                .content();

        log.info("路由回复: {}", reply);
        assertThat(reply)
                .as("图片功能咨询应有合理回复")
                .isNotBlank()
                .doesNotContain("失败")
                .doesNotContain("不可用");
    }
}
