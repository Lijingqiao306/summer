package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.WeatherResult;

/**
 * 天气服务接口
 */
public interface WeatherService {

    /**
     * 查询天气
     *
     * @param city      城市名称
     * @param adcode    行政区划代码
     * @param extended  是否扩展信息
     * @param forecast  是否预报
     * @param hourly    是否逐小时
     * @param minutely  是否逐分钟
     * @param indices   是否生活指数
     * @param lang      语言
     * @return 天气结果
     */
    WeatherResult getWeather(String city, String adcode,
                             Boolean extended, Boolean forecast,
                             Boolean hourly, Boolean minutely,
                             Boolean indices, String lang);
}
