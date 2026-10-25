package org.example.ykd_summer.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DashScope 语音转写（ASR）配置属性，绑定 yml 中的 dashscope.voice.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "dashscope.voice")
public class DashScopeVoiceProperties {

    /** DashScope API Key，优先用 DASHSCOPE_API_KEY，回退到 OPENAI_API_KEY */
    private String apiKey;

    /** 语音识别模型，默认 qwen3-asr-flash */
    private String model = "qwen3-asr-flash";

    /** TTS 合成模型，默认 cosyvoice-v3-plus */
    private String ttsModel = "cosyvoice-v3-plus";

    /** TTS 音色，默认 longanyang（龙安洋-阳光大男孩，cosyvoice-v3-plus 标杆音色） */
    private String ttsVoice = "longanyang";

    /** TTS 输出音频格式，默认 mp3（已被 ttsFallbackFormats 取代，保留用于向后兼容） */
    private String ttsFormat = "mp3";

    /** TTS 降级格式列表，按顺序尝试，全部失败后降级为纯文本。默认 ["mp3", "wav"] */
    private List<String> ttsFallbackFormats = List.of("mp3", "wav");
}
