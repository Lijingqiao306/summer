package org.example.ykd_summer.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import org.example.ykd_summer.utils.LogUtils;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.example.ykd_summer.config.properties.DashScopeImageProperties;
import org.example.ykd_summer.service.ImageService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 图片服务实现
 * 文生图使用 DashScope REST API（绕过 SDK 端点映射问题），图片识别使用 MultiModalConversation SDK
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/z-image-api-reference">Z-Image API 参考文档</a>
 */
@Slf4j
@Service
public class ImageServiceImpl implements ImageService {

    private static final String DASHSCOPE_IMAGE_API =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private final DashScopeImageProperties props;
    private final OkHttpClient okHttpClient;

    public ImageServiceImpl(DashScopeImageProperties props) {
        this.props = props;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String generateImage(String prompt, String style, String size) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("图片描述不能为空");
        }

        String effectiveSize = (size != null && !size.isBlank()) ? size : props.getGeneration().getSize();
        String effectivePrompt = (style != null && !style.isBlank()) ? prompt + "，风格：" + style : prompt;
        String model = props.getGeneration().getModel();

        // 构建 DashScope Z-Image REST API 请求体
        // 格式参考: https://help.aliyun.com/zh/model-studio/z-image-api-reference
        JsonObject textContent = new JsonObject();
        textContent.addProperty("text", effectivePrompt);

        JsonArray contentArray = new JsonArray();
        contentArray.add(textContent);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", contentArray);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject input = new JsonObject();
        input.add("messages", messages);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("size", effectiveSize);
        parameters.addProperty("prompt_extend", false);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input", input);
        body.add("parameters", parameters);

        String apiKey = props.getApiKey();
        log.info("文生图请求: model={}, prompt={}, size={}, apiKey={}***",
                model, effectivePrompt, effectiveSize,
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) : "(empty)");

        try {
            Request request = new Request.Builder()
                    .url(DASHSCOPE_IMAGE_API)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.info("文生图响应: status={}, body={}", response.code(),
                        responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("图片生成失败，HTTP " + response.code() + ": " + responseBody);
                }

                JsonObject result = GSON.fromJson(responseBody, JsonObject.class);
                JsonObject output = result.getAsJsonObject("output");

                if (output == null || output.getAsJsonArray("choices") == null
                        || output.getAsJsonArray("choices").isEmpty()) {
                    String requestId = result.has("request_id") ? result.get("request_id").getAsString() : "unknown";
                    log.warn("文生图返回为空, requestId={}", requestId);
                    throw new RuntimeException("图片生成失败：服务返回为空");
                }

                // 解析 Z-Image 响应: output.choices[0].message.content[0].image
                JsonArray choices = output.getAsJsonArray("choices");
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject messageObj = choice.getAsJsonObject("message");
                JsonArray content = messageObj.getAsJsonArray("content");

                // 遍历 content 数组找到 "image" 字段
                String imageUrl = null;
                for (int i = 0; i < content.size(); i++) {
                    JsonObject item = content.get(i).getAsJsonObject();
                    if (item.has("image")) {
                        imageUrl = item.get("image").getAsString();
                        break;
                    }
                }

                if (imageUrl == null) {
                    log.warn("文生图响应中未找到图片 URL, body={}", responseBody);
                    throw new RuntimeException("图片生成失败：未返回图片 URL");
                }

                log.info("文生图成功: url={}", LogUtils.truncateUrlForLog(imageUrl));
                return imageUrl;
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("文生图调用异常", e);
            throw new RuntimeException("图片生成失败：" + e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("图片 URL 不能为空");
        }

        log.info("下载图片: url={}", LogUtils.truncateUrlForLog(imageUrl));
        try {
            Request request = new Request.Builder()
                    .url(imageUrl)
                    .get()
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("图片下载失败，HTTP " + response.code());
                }
                byte[] bytes = response.body() != null ? response.body().bytes() : new byte[0];
                log.info("图片下载成功: url={}, size={} bytes", LogUtils.truncateUrlForLog(imageUrl), bytes.length);
                return bytes;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片下载异常: url={}", imageUrl, e);
            throw new RuntimeException("图片下载失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String describeImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("图片 URL 不能为空");
        }

        //构建请求消息
        MultiModalMessage message = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(
                        Map.of("image", imageUrl),
                        Map.of("text", "请详细描述这张图片的内容，包括人物、物体、场景、颜色、氛围等信息。")
                ))
                .build();

        //构建请求参数
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getRecognition().getModel())
                .messages(Collections.singletonList(message))
                .build();

        log.info("图片识别请求: model={}, imageUrl={}", props.getRecognition().getModel(), LogUtils.truncateUrlForLog(imageUrl));

        try {
            MultiModalConversation conv = new MultiModalConversation();
            MultiModalConversationResult result = conv.call(param);

            if (result == null || result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                log.warn("图片识别返回为空, requestId={}", result != null ? result.getRequestId() : "null");
                return "未能识别该图片的内容。";
            }

            //解析大模型返回结果
            String description = (String) result.getOutput().getChoices().get(0)
                    .getMessage().getContent().get(0).get("text");
            log.info("图片识别成功: requestId={}, description={}", result.getRequestId(), description);
            return description != null ? description : "未能识别该图片的内容。";

        } catch (Exception e) {
            log.error("图片识别调用异常", e);
            return "图片识别暂时不可用：" + e.getMessage();
        }
    }
}
