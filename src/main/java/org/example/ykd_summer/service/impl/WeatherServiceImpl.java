package org.example.ykd_summer.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.ykd_summer.dto.WeatherResult;
import org.example.ykd_summer.exception.WeatherException;
import org.example.ykd_summer.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class WeatherServiceImpl implements WeatherService {
    private static final String API_URL = "https://uapis.cn/api/v1/misc/weather";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public WeatherServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public WeatherResult getWeather(String city, String adcode,
                                    Boolean extended, Boolean forecast,
                                    Boolean hourly, Boolean minutely,
                                    Boolean indices, String lang) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(API_URL).newBuilder();

        if (city != null && !city.isBlank()) {
            urlBuilder.addQueryParameter("city", city);
        }
        if (adcode != null && !adcode.isBlank()) {
            urlBuilder.addQueryParameter("adcode", adcode);
        }
        addBoolParam(urlBuilder, "extended", extended);
        addBoolParam(urlBuilder, "forecast", forecast);
        addBoolParam(urlBuilder, "hourly", hourly);
        addBoolParam(urlBuilder, "minutely", minutely);
        addBoolParam(urlBuilder, "indices", indices);
        if (lang != null && !lang.isBlank()) {
            urlBuilder.addQueryParameter("lang", lang);
        }

        String url = urlBuilder.build().toString();
        log.info("Requesting weather API: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            log.debug("Weather API response: {}", body);

            // 先解析为 JsonNode 判断是成功还是错误响应
            JsonNode root = objectMapper.readTree(body);

            // 错误响应：有 code 和 message，但没有天气数据
            if (root.has("code") && root.has("message") && !root.has("province")) {
                String errorCode = root.get("code").asText();
                String errorMessage = root.get("message").asText();
                throw new WeatherException(errorCode, errorMessage);
            }

            // 反序列化为 WeatherResult
            WeatherResult result = objectMapper.treeToValue(root, WeatherResult.class);

            // 关键天气字段为 null 时视为未找到该城市
            if (result.city() == null || result.weather() == null) {
                throw new WeatherException("NOT_FOUND", "未找到该城市的天气数据");
            }

            return result;

        } catch (WeatherException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to fetch weather data", e);
            throw new WeatherException("SERVICE_UNAVAILABLE", "天气服务暂时不可用");
        }
    }

    private void addBoolParam(HttpUrl.Builder builder, String key, Boolean value) {
        if (value != null) {
            builder.addQueryParameter(key, String.valueOf(value));
        }
    }
}
