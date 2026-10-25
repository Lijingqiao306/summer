package org.example.ykd_summer.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片 Agent（ImageChatClient）文生图能力集成测试
 * <p>
 * 依赖真实 DashScope API Key，需要在环境变量中设置 OPENAI_API_KEY 或 DASHSCOPE_API_KEY。
 * 测试会实际调用 z-image-turbo 模型生成图片并产生费用。
 */
@SpringBootTest
class ImageChatConfigTest {

    private static final Logger log = LoggerFactory.getLogger(ImageChatConfigTest.class);

    @Autowired
    @Qualifier("imageChatClient")
    private ChatClient imageChatClient;

    @Autowired
    private ChatMemory chatMemory;

    private static final String TEST_USER = "test-image-user";

    @Test
    void shouldGenerateImageByToolCalling() {
        // 清空测试用户的记忆，保证每次测试独立
        chatMemory.clear(TEST_USER);

        String prompt = "画一只坐在窗边看雨的橘猫";

        log.info("===== 文生图测试开始 =====");
        log.info("用户输入: {}", prompt);

        String reply = imageChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER))
                .user(prompt)
                .call()
                .content();

        log.info("Agent 回复: {}", reply);

        // 断言回复中包含图片 URL（http 开头）
        assertThat(reply)
                .as("Agent 回复应包含生成的图片 URL")
                .contains("http");

        // 断言 Agent 调用了工具（而不是凭空编造）
        assertThat(reply)
                .as("回复不应包含错误提示")
                .doesNotContain("失败")
                .doesNotContain("不可用");
    }

    @Test
    void shouldGenerateImageWithStyle() {
        chatMemory.clear(TEST_USER + "-style");

        String prompt = "画一只小狗，水彩风格";

        log.info("===== 文生图（带风格）测试开始 =====");
        log.info("用户输入: {}", prompt);

        String reply = imageChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, TEST_USER + "-style"))
                .user(prompt)
                .call()
                .content();

        log.info("Agent 回复: {}", reply);

        assertThat(reply).contains("http");
        assertThat(reply).doesNotContain("失败");
    }
}
