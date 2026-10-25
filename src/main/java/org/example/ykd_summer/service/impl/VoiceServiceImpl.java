package org.example.ykd_summer.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.example.ykd_summer.config.properties.DashScopeVoiceProperties;
import org.example.ykd_summer.service.VoiceService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 语音识别服务实现，调用 DashScope Qwen3-ASR-Flash 原生 HTTP API 进行语音转文字。
 * <p>
 * 优先使用微信内置转写（VoiceItem.getText()），不可用时再调本服务。
 * <p>
 * 使用 DashScope 同步调用端点，通过 Base64 Data URI 上传本地音频直接转写。
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference">Qwen-ASR API 参考</a>
 */
@Slf4j
@Service
public class VoiceServiceImpl implements VoiceService {

    /** DashScope 多模态生成端点，Qwen3-ASR-Flash 同步调用也走此端点 */
    private static final String DASHSCOPE_ASR_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DASHSCOPE_TTS_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** ASR 最大支持：10 MB / 5 分钟 */
    private static final int MAX_ASR_SIZE_BYTES = 10 * 1024 * 1024;

    private final DashScopeVoiceProperties props;
    private final OkHttpClient okHttpClient;

    public VoiceServiceImpl(DashScopeVoiceProperties props) {
        this.props = props;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String transcribe(Resource audioResource) {
        if (audioResource == null) {
            log.warn("音频 Resource 为 null，跳过转写");
            return null;
        }

        byte[] audioBytes;
        try {
            audioBytes = audioResource.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.error("读取音频 Resource 失败", e);
            return null;
        }

        if (audioBytes.length == 0) {
            log.warn("音频数据为空，跳过转写");
            return null;
        }

        if (audioBytes.length > MAX_ASR_SIZE_BYTES) {
            log.warn("音频文件过大 ({} bytes > {} MB)，跳过转写",
                    audioBytes.length, MAX_ASR_SIZE_BYTES / 1024 / 1024);
            return null;
        }

        String apiKey = props.getApiKey();
        String model = props.getModel();

        // 根据 magic bytes 检测音频 MIME 类型
        String mimeType = detectAudioMimeType(audioBytes);

        // Base64 编码并构建 Data URI
        String base64 = Base64.getEncoder().encodeToString(audioBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;

        log.info("语音转写请求: model={}, size={} bytes, mimeType={}, apiKey={}***",
                model, audioBytes.length, mimeType,
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) : "(empty)");

        // 构建请求体：{"model":"...","input":{"messages":[{"role":"user","content":[{"audio":"data:..."}]}]}}
        JsonObject audioContent = new JsonObject();
        audioContent.addProperty("audio", dataUrl);

        JsonArray contentArray = new JsonArray();
        contentArray.add(audioContent);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", contentArray);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject input = new JsonObject();
        input.add("messages", messages);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);

        try {
            Request request = new Request.Builder()
                    .url(DASHSCOPE_ASR_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.info("语音转写响应: status={}, body={}", response.code(),
                        responseBody.length() > 300 ? responseBody.substring(0, 300) + "..." : responseBody);

                if (!response.isSuccessful()) {
                    log.error("语音转写失败，HTTP {}: {}", response.code(), responseBody);
                    return null;
                }

                // 解析：output.choices[0].message.content[0].text
                JsonObject result = GSON.fromJson(responseBody, JsonObject.class);
                JsonObject output = result.getAsJsonObject("output");
                if (output == null) {
                    log.warn("语音转写响应中无 output 字段");
                    return null;
                }

                JsonArray choices = output.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    log.warn("语音转写响应中无 choices");
                    return null;
                }

                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject msgObj = choice.getAsJsonObject("message");
                if (msgObj == null) {
                    log.warn("语音转写响应中无 message");
                    return null;
                }

                JsonArray msgContent = msgObj.getAsJsonArray("content");
                if (msgContent == null || msgContent.isEmpty()) {
                    log.warn("语音转写响应 message.content 为空");
                    return null;
                }

                String text = msgContent.get(0).getAsJsonObject().get("text").getAsString();
                log.info("语音转写成功: text={}", text);
                return text != null && !text.isBlank() ? text : null;
            }

        } catch (Exception e) {
            log.error("语音转写调用异常", e);
            return null;
        }
    }

    /**
     * 通过文件头 magic bytes 检测音频 MIME 类型。
     * <p>
     * 支持的格式：AMR、MP3、WAV、OGG、M4A/AAC。
     * 无法识别时默认返回 audio/mpeg。
     */
    private static String detectAudioMimeType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "audio/mpeg";
        }
        // AMR: "#!AMR"
        if (bytes[0] == 0x23 && bytes[1] == 0x21 && bytes[2] == 0x41 && bytes[3] == 0x4D) {
            return "audio/amr";
        }
        // WAV: "RIFF"
        if (bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46) {
            return "audio/wav";
        }
        // OGG: "OggS"
        if (bytes[0] == 0x4F && bytes[1] == 0x67 && bytes[2] == 0x67 && bytes[3] == 0x53) {
            return "audio/ogg";
        }
        // MP3: ID3 tag ("ID3") or sync word (0xFF 0xFB / 0xFF 0xF3 / 0xFF 0xF2)
        if ((bytes[0] == 0x49 && bytes[1] == 0x44 && bytes[2] == 0x33)
                || (bytes[0] == (byte) 0xFF && (bytes[1] & 0xFE) == 0xFA)) {
            return "audio/mpeg";
        }
        // M4A/AAC: ftyp box at offset 4 ("ftyp" at bytes[4..7])
        if (bytes.length > 11
                && bytes[4] == 0x66 && bytes[5] == 0x74 && bytes[6] == 0x79 && bytes[7] == 0x70) {
            return "audio/mp4";
        }
        // SILK (微信语音常见格式): 魔数 "#!SILK"
        if (bytes[0] == 0x23 && bytes[1] == 0x21 && bytes[2] == 0x53
                && bytes[3] == 0x49 && bytes.length > 4
                && bytes[4] == 0x4C && bytes[5] == 0x4B) {
            return "audio/silk";
        }
        // 兜底
        return "audio/mpeg";
    }

    @Override
    public VoiceResult synthesize(String text) {
        if (text == null || text.isBlank()) {
            log.warn("合成文字为空，跳过 TTS");
            return new VoiceResult(null, null);
        }

        List<String> fallbackFormats = props.getTtsFallbackFormats();
        log.info("TTS 降级链: {} -> 纯文本，text={}", fallbackFormats,
                text.substring(0, Math.min(20, text.length())));

        for (String format : fallbackFormats) {
            byte[] audio = synthesizeWithFormat(text, format);
            if (audio != null && audio.length > 0) {
                log.info("TTS 合成成功: format={}, size={} bytes, text={}",
                        format, audio.length,
                        text.substring(0, Math.min(20, text.length())));
                return new VoiceResult(format, audio);
            }
            log.warn("TTS format={} 失败，尝试降级", format);
        }

        log.warn("TTS 全部格式均失败，降级为纯文本: text={}",
                text.substring(0, Math.min(30, text.length())));
        return new VoiceResult(null, null);
    }

    /**
     * 使用指定格式调用 DashScope CosyVoice TTS 并下载音频。
     *
     * @param text   合成文字
     * @param format 输出音频格式（mp3 / wav）
     * @return 音频二进制数据，失败时返回 null
     */
    private byte[] synthesizeWithFormat(String text, String format) {
        String apiKey = props.getApiKey();
        String ttsModel = props.getTtsModel();
        String ttsVoice = props.getTtsVoice();

        JsonObject inputObj = new JsonObject();
        inputObj.addProperty("text", text);
        inputObj.addProperty("voice", ttsVoice);
        inputObj.addProperty("format", format);
        inputObj.addProperty("sample_rate", 22050);
        inputObj.addProperty("volume", 50);
        inputObj.addProperty("rate", 1.0);
        inputObj.addProperty("pitch", 1.0);

        JsonObject body = new JsonObject();
        body.addProperty("model", ttsModel);
        body.add("input", inputObj);

        try {
            Request request = new Request.Builder()
                    .url(DASHSCOPE_TTS_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            String audioUrl;
            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("TTS 合成失败 [format={}], HTTP {}: {}",
                            format, response.code(), responseBody);
                    return null;
                }
                JsonObject result = GSON.fromJson(responseBody, JsonObject.class);
                audioUrl = result.getAsJsonObject("output")
                        .getAsJsonObject("audio")
                        .get("url").getAsString();
                log.info("TTS 合成任务已提交 [format={}], audioUrl={}", format, audioUrl);
            }

            Request downloadRequest = new Request.Builder()
                    .url(audioUrl)
                    .get()
                    .build();

            try (Response downloadResponse = okHttpClient.newCall(downloadRequest).execute()) {
                if (!downloadResponse.isSuccessful()) {
                    log.error("TTS 音频下载失败 [format={}], HTTP {}: {}",
                            format, downloadResponse.code(), audioUrl);
                    return null;
                }
                byte[] rawAudio = downloadResponse.body() != null
                        ? downloadResponse.body().bytes() : new byte[0];
                return rawAudio.length > 0 ? rawAudio : null;
            }

        } catch (Exception e) {
            log.error("TTS 合成异常 [format={}]: text={}",
                    format, text.substring(0, Math.min(30, text.length())), e);
            return null;
        }
    }
}
