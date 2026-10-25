package org.example.ykd_summer.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.WeatherResult;
import org.example.ykd_summer.exception.WeatherException;
import org.example.ykd_summer.service.WeatherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 可调用的工具集合
 * 通过 @Tool 暴露给大模型，由模型自主决定何时调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotTools {

    private final WeatherService weatherService;

    @Value("${app.version}")
    private String appVersion;

    @Tool(description = "查询指定城市的实时天气。")
    public String queryWeather(@ToolParam(description = "城市名称，例如 北京、上海、广州") String city) {
        try {
            WeatherResult result = weatherService.getWeather(city, null, null, null, null, null, null, null);
            return String.format("%s 当前天气：%s，气温 %.0f℃，风向 %s，湿度 %d%%。数据更新时间：%s",
                    result.city(),
                    result.weather(),
                    result.temperature(),
                    result.windDirection(),
                    result.humidity(),
                    result.reportTime());
        } catch (WeatherException e) {
            if ("NOT_FOUND".equals(e.getErrorCode())) {
                log.warn("天气查询未找到城市：{}，错误信息：{}", city, e.getMessage());
                return "未找到「" + city + "」的天气数据，请确认城市名称是否正确。";
            }
            log.error("天气服务异常，错误码：{}", e.getErrorCode(), e);
            return "天气服务暂时不可用，请稍后再试。";
        } catch (Exception e) {
            log.error("天气工具调用失败", e);
            return "抱歉，暂时无法查询「" + city + "」的天气，请稍后再试。";
        }
    }

    @Tool(description = "获取当前应用的版本号。当用户询问版本、version 时使用。")
    public String getAppVersion() {
        return "当前版本：" + appVersion;
    }

    @Tool(description = "获取服务的运行状态。当用户询问状态、status、是否在线时使用。")
    public String getServiceStatus() {
        return "服务运行正常！";
    }
}
