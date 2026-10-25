package org.example.ykd_summer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 天气查询结果（Jackson 反序列化载体）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherResult(
        String province,
        String city,
        String district,
        String adcode,
        String weather,
        @JsonProperty("weather_icon") String weatherIcon,
        Double temperature,
        @JsonProperty("wind_direction") String windDirection,
        @JsonProperty("wind_power") String windPower,
        Integer humidity,
        @JsonProperty("report_time") String reportTime,
        @JsonProperty("feels_like") Double feelsLike,
        Double visibility,
        Double pressure,
        Double uv,
        Double precipitation,
        Integer cloud,
        Integer aqi,
        @JsonProperty("aqi_level") Integer aqiLevel,
        @JsonProperty("aqi_category") String aqiCategory,
        @JsonProperty("aqi_primary") String aqiPrimary,
        @JsonProperty("air_pollutants") AirPollutants airPollutants,
        @JsonProperty("temp_max") Double tempMax,
        @JsonProperty("temp_min") Double tempMin,
        List<Forecast> forecast,
        List<Alert> alerts,
        @JsonProperty("hourly_forecast") List<HourlyForecast> hourlyForecast,
        @JsonProperty("minutely_precip") MinutelyPrecip minutelyPrecip,
        @JsonProperty("life_indices") LifeIndices lifeIndices
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AirPollutants(Double pm25, Double pm10, Double o3, Double no2, Double so2, Double co) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            String title,
            String type,
            String level,
            String text,
            @JsonProperty("publish_time") String publishTime,
            String publisher,
            List<String> guidance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(
            String date,
            String week,
            @JsonProperty("temp_max") Double tempMax,
            @JsonProperty("temp_min") Double tempMin,
            @JsonProperty("weather_day") String weatherDay,
            @JsonProperty("weather_night") String weatherNight,
            @JsonProperty("wind_dir_day") String windDirDay,
            @JsonProperty("wind_dir_night") String windDirNight,
            @JsonProperty("wind_scale_day") String windScaleDay,
            @JsonProperty("wind_scale_night") String windScaleNight,
            @JsonProperty("wind_speed_day") Double windSpeedDay,
            Double humidity,
            Double precip,
            Double visibility,
            @JsonProperty("uv_index") Double uvIndex,
            String sunrise,
            String sunset
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HourlyForecast(
            String time,
            Double temperature,
            String weather,
            @JsonProperty("wind_direction") String windDirection,
            @JsonProperty("wind_speed") Double windSpeed,
            @JsonProperty("wind_scale") String windScale,
            Double humidity,
            Double precip,
            @JsonProperty("feels_like") Double feelsLike,
            Double visibility,
            Double pop,
            @JsonProperty("uv_index") Double uvIndex
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MinutelyPrecip(
            String summary,
            @JsonProperty("update_time") String updateTime,
            List<MinutelyData> data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MinutelyData(String time, Double precip, String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifeIndices(
            LifeIndexItem clothing,
            LifeIndexItem uv,
            @JsonProperty("car_wash") LifeIndexItem carWash,
            LifeIndexItem drying,
            @JsonProperty("air_conditioner") LifeIndexItem airConditioner,
            @JsonProperty("cold_risk") LifeIndexItem coldRisk,
            LifeIndexItem exercise,
            LifeIndexItem comfort,
            LifeIndexItem travel,
            LifeIndexItem fishing,
            LifeIndexItem allergy,
            LifeIndexItem sunscreen,
            LifeIndexItem mood,
            LifeIndexItem beer,
            LifeIndexItem umbrella,
            LifeIndexItem traffic,
            @JsonProperty("air_purifier") LifeIndexItem airPurifier,
            LifeIndexItem pollen
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifeIndexItem(String level, String brief, String advice) {}
}
