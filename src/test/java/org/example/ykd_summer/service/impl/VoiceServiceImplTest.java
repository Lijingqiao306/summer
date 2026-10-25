package org.example.ykd_summer.service.impl;

import org.example.ykd_summer.config.properties.DashScopeVoiceProperties;
import org.example.ykd_summer.service.VoiceService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTS 降级链集成测试（mp3 → wav → 纯文本）。
 * <p>
 * 手动构造 VoiceServiceImpl（不依赖 Spring 上下文），直接调用 DashScope 真实 API。
 * <p>
 * API Key 从环境变量获取：优先 DASHSCOPE_API_KEY，回退 OPENAI_API_KEY。
 * TTS 合成会产生实际 API 调用费用。
 */
class VoiceServiceImplTest {

    private static final Logger log = LoggerFactory.getLogger(VoiceServiceImplTest.class);

    /**
     * 手动创建 VoiceService 实例，绕过 Spring 上下文。
     * 避免加载 ILink SDK / Spring AI 等重量级依赖。
     */
    private VoiceService createService(List<String> fallbackFormats) {
        DashScopeVoiceProperties props = new DashScopeVoiceProperties();

        // 读取 API Key（优先百炼专用 Key，回退通用 Key）
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未设置 DASHSCOPE_API_KEY 或 OPENAI_API_KEY 环境变量，无法执行 TTS 测试");
        }

        props.setApiKey(apiKey);
        props.setTtsModel("cosyvoice-v3-plus");
        props.setTtsVoice("longanyang");
        props.setTtsFallbackFormats(fallbackFormats);
        return new VoiceServiceImpl(props);
    }

    // ========================
    // 降级链测试
    // ========================

    @Test
    void shouldSynthesizeWithDefaultFallbackChain() {
        VoiceService service = createService(List.of("mp3", "wav"));
        String testText = "你好，这是一条降级链测试";

        VoiceService.VoiceResult result = service.synthesize(testText);

        assertThat(result.success())
                .as("默认降级链 mp3→wav 至少应命中一个")
                .isTrue();
        assertThat(result.format())
                .as("默认应优先返回 mp3")
                .isIn("mp3", "wav");

        log.info("===== 降级链测试 =====");
        log.info("结果: format={}, size={} bytes, text=\"{}\"",
                result.format(), result.bytes().length, testText);
    }

    // ========================
    // WAV 格式专项测试
    // ========================

    @Test
    void shouldSynthesizeWavFormat() throws IOException {
        VoiceService service = createService(List.of("wav"));
        String testText = "你好，这是一条 WAV 格式的语音合成测试";

        VoiceService.VoiceResult result = service.synthesize(testText);

        // ---- 基本断言 ----
        assertThat(result.success())
                .as("WAV 格式 TTS 合成应成功")
                .isTrue();
        assertThat(result.format())
                .as("返回格式应为 wav")
                .isEqualTo("wav");

        byte[] bytes = result.bytes();
        assertThat(bytes.length)
                .as("WAV 文件至少应有 44 字节头")
                .isGreaterThanOrEqualTo(44);

        // ---- WAV 文件头验证 ----
        // RIFF 容器标识
        String riffHeader = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        assertThat(riffHeader)
                .as("WAV 文件应以 'RIFF' 开头")
                .isEqualTo("RIFF");

        // WAVE 格式标识（偏移 8，长度 4 字节）
        String waveHeader = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
        assertThat(waveHeader)
                .as("WAV 文件应包含 'WAVE' 标识")
                .isEqualTo("WAVE");

        log.info("===== WAV 格式测试 =====");
        log.info("结果: format={}, size={} bytes", result.format(), bytes.length);
        log.info("文件头: RIFF='{}' WAVE='{}'", riffHeader, waveHeader);

        // ---- 保存到临时文件供人工播放验证 ----
        Path tmpFile = Files.createTempFile("tts_wav_test_", ".wav");
        Files.write(tmpFile, bytes);
        log.info("WAV 音频已保存: {}", tmpFile.toAbsolutePath());
    }

    // ========================
    // WAV 长文本测试
    // ========================

    @Test
    void shouldSynthesizeLongTextAsWav() throws IOException {
        VoiceService service = createService(List.of("wav"));
        String testText = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。"
                + "床前明月光，疑是地上霜。举头望明月，低头思故乡。";

        VoiceService.VoiceResult result = service.synthesize(testText);

        assertThat(result.success())
                .as("长文本 WAV 合成应成功")
                .isTrue();
        assertThat(result.format())
                .as("格式应为 wav")
                .isEqualTo("wav");

        byte[] bytes = result.bytes();
        String riffHeader = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        assertThat(riffHeader)
                .as("WAV 文件应以 RIFF 开头")
                .isEqualTo("RIFF");

        log.info("===== 长文本 WAV 测试 =====");
        log.info("结果: format={}, size={} bytes, textLen={} chars",
                result.format(), bytes.length, testText.length());

        Path tmpFile = Files.createTempFile("tts_wav_long_", ".wav");
        Files.write(tmpFile, bytes);
        log.info("长文本音频已保存: {}", tmpFile.toAbsolutePath());
    }

    // ========================
    // 容错测试
    // ========================

    @Test
    void shouldReturnFailureOnEmptyText() {
        VoiceService service = createService(List.of("wav"));

        VoiceService.VoiceResult result = service.synthesize("");

        assertThat(result.success())
                .as("空文本应返回失败")
                .isFalse();
        assertThat(result.format()).isNull();
        assertThat(result.bytes()).isNull();

        log.info("===== 空文本测试 =====");
        log.info("结果: success=false（符合预期）");
    }

    @Test
    void shouldSynthesizeEnglishText() throws IOException {
        VoiceService service = createService(List.of("wav"));
        String testText = "Hello, this is a text to speech test using CosyVoice.";

        VoiceService.VoiceResult result = service.synthesize(testText);

        assertThat(result.success())
                .as("英文 WAV 合成应成功")
                .isTrue();

        byte[] bytes = result.bytes();
        String riffHeader = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        assertThat(riffHeader).isEqualTo("RIFF");

        log.info("===== 英文 WAV 测试 =====");
        log.info("结果: format={}, size={} bytes", result.format(), bytes.length);

        Path tmpFile = Files.createTempFile("tts_wav_en_", ".wav");
        Files.write(tmpFile, bytes);
        log.info("英文音频已保存: {}", tmpFile.toAbsolutePath());
    }
}
