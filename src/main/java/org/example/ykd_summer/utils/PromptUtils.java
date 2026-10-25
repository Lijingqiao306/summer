package org.example.ykd_summer.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Prompt 文件加载工具。
 * <p>
 * 将 System Prompt 从 Java Config 类中抽离到 classpath 下的文本文件，
 * 解决 Prompt 文本过长导致代码可读性下降的问题。
 * <p>
 * 用法：
 * <pre>{@code
 * private static final String SYSTEM_PROMPT =
 *         PromptUtils.load("prompts/ai-chat-system.txt");
 * }</pre>
 */
public final class PromptUtils {

    private PromptUtils() {
    }

    /**
     * 从 classpath 加载文本文件内容。
     *
     * @param classpath classpath 路径，如 "prompts/ai-chat-system.txt"
     * @return 文件完整文本内容
     * @throws IllegalStateException 文件不存在时抛出
     * @throws UncheckedIOException 读取 IO 异常时抛出
     */
    public static String load(String classpath) {
        var is = PromptUtils.class.getClassLoader().getResourceAsStream(classpath);
        if (is == null) {
            throw new IllegalStateException("Prompt file not found in classpath: " + classpath);
        }
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt: " + classpath, e);
        }
    }
}
