package org.example.ykd_summer.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 高德地图工具集合，挂载到 TEXT Agent 上。
 * <p>
 * 提供地址解析、POI 搜索、POI 详情、路径规划四大能力，
 * 通过高德 Web API 实现。LLM 按需调用，不需要时完全不影响上下文。
 * <p>
 * searchPoi 内部自动路由：
 * <ul>
 *   <li>有坐标 + 半径 → 周边搜索（around search），精准</li>
 *   <li>仅有城市名 → 城市关键词搜索（text search），全市覆盖</li>
 *   <li>都没有 → 全国搜索</li>
 * </ul>
 */
@Slf4j
@Component
public class AmapTools {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REVERSE_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String PLACE_AROUND_URL = "https://restapi.amap.com/v5/place/around";
    private static final String PLACE_TEXT_URL = "https://restapi.amap.com/v5/place/text";
    private static final String PLACE_DETAIL_URL = "https://restapi.amap.com/v5/place/detail";
    private static final String DIRECTION_URL = "https://restapi.amap.com/v3/direction";

    public AmapTools(@Value("${amap.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== 公用 HTTP 工具 ====================

    private String get(String url) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("高德 API Key 未配置，请在 application.yml 中设置 amap.api-key");
            return "【高德地图服务未配置：缺少 API Key】";
        }
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("高德 API 请求失败: url={}, code={}", url, response.code());
                    return "【高德地图服务请求失败，状态码：" + response.code() + "】";
                }
                String body = response.body() != null ? response.body().string() : "";
                if (body.length() < 300) {
                    // 短响应未必是异常：空结果也返回短 JSON，按 status 判断
                    try {
                        var node = objectMapper.readTree(body);
                        if ("1".equals(node.path("status").asText())) {
                            log.debug("高德 API 空结果: url={}, info={}", url, node.path("info").asText());
                        } else {
                            log.warn("高德 API 响应异常: url={}, body={}", url, body);
                        }
                    } catch (Exception parseEx) {
                        log.warn("高德 API 响应异常短且无法解析: url={}, body={}", url, body);
                    }
                } else {
                    log.debug("高德 API 响应: url={}, bodyLen={}", url, body.length());
                }
                return body;
            }
        } catch (Exception e) {
            log.error("高德 API 调用异常: url={}, error={}", url, e.getMessage());
            return "【高德地图服务调用异常：" + e.getMessage() + "】";
        }
    }

    private String buildUrl(String baseUrl, String... params) {
        StringBuilder sb = new StringBuilder(baseUrl).append("?key=").append(apiKey);
        for (int i = 0; i < params.length; i += 2) {
            if (i + 1 < params.length && params[i + 1] != null && !params[i + 1].isBlank()) {
                sb.append("&").append(params[i]).append("=")
                        .append(URLEncoder.encode(params[i + 1], StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    // ==================== Tool 方法 ====================

    @Tool(description = "地址转坐标（地理编码）。输入地址文字，返回「经度,纬度」。")
    public String geocode(
            @ToolParam(description = "地址文字，如「深圳南山」「北京朝阳」「广州天河体育西路」") String address) {

        if (address == null || address.isBlank()) {
            return "【地址为空，无法解析】";
        }

        // 如果地址不含「市」等关键字，可能太简略，但高德 geocode 本身有容错，直接传
        String url = buildUrl(GEOCODE_URL,
                "address", address.trim(),
                "output", "JSON");

        String body = get(url);
        if (body.startsWith("【")) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText("未知错误");
                return "【地理编码失败：" + info + "】";
            }
            JsonNode geocodes = root.path("geocodes");
            if (geocodes.isEmpty()) {
                return "【未找到「" + address + "」对应的坐标，请确认地址是否正确】";
            }
            String location = geocodes.get(0).path("location").asText();
            String formattedAddress = geocodes.get(0).path("formatted_address").asText(location);
            log.info("geocode: address={} → location={}", address, location);
            return String.format("「%s」的坐标为 %s", formattedAddress, location);
        } catch (Exception e) {
            log.error("geocode 解析异常: {}", e.getMessage());
            return "【地理编码结果解析失败：" + e.getMessage() + "】";
        }
    }

    @Tool(description = "搜索周边商家/场所（POI）。支持按地址、坐标、城市、关键词搜索。")
    public String searchPoi(
            @ToolParam(description = "搜索关键词，如「火锅」「健身房」。不支持临时活动/演出/漫展") String keyword,
            @ToolParam(description = "文字地址，如「南京百家湖商圈」。系统自动 geocode，推荐优先使用", required = false) String address,
            @ToolParam(description = "城市名称，如「深圳」「北京」", required = false) String city,
            @ToolParam(description = "经纬度坐标，格式为「经度,纬度」", required = false) String location,
            @ToolParam(description = "周边搜索半径（米），默认 3000", required = false) Integer radius) {

        if (keyword == null || keyword.isBlank()) {
            return "【搜索关键词为空】";
        }

        String url;
        int actualRadius = (radius != null && radius > 0) ? radius : 3000;

        // 策略（1）：address 参数 → 内部自动 geocode，从架构上杜绝坐标幻觉
        if (address != null && !address.isBlank()) {
            String autoCoord = internalGeocodeFetchCoordinates(address.trim());
            if (autoCoord != null) {
                location = autoCoord;
                log.info("searchPoi(address-geocode): address={} → coord={}, keyword={}, radius={}",
                        address, location, keyword, actualRadius);
            } else {
                log.warn("searchPoi: address={} geocode 失败，回退到城市关键词搜索", address);
                // geocode 失败，将 address 作为 city 回退
                if (city == null || city.isBlank()) {
                    city = address.trim();
                }
            }
        }

        if (location != null && !location.isBlank()) {
            // 周边搜索（精确实时坐标）
            url = buildUrl(PLACE_AROUND_URL,
                    "location", location.trim(),
                    "radius", String.valueOf(actualRadius),
                    "keywords", keyword.trim(),
                    "show_fields", "business,navi,photos");
            log.info("searchPoi(around): keyword={}, location={}, radius={}", keyword, location, actualRadius);
        } else if (city != null && !city.isBlank()) {
            // 城市关键词搜索
            url = buildUrl(PLACE_TEXT_URL,
                    "region", city.trim(),
                    "keywords", keyword.trim(),
                    "show_fields", "business,navi,photos");
            log.info("searchPoi(text, city): keyword={}, city={}", keyword, city);
        } else {
            // 全国搜索
            url = buildUrl(PLACE_TEXT_URL,
                    "keywords", keyword.trim(),
                    "show_fields", "business,navi,photos");
            log.info("searchPoi(text, nationwide): keyword={}", keyword);
        }

        String body = get(url);
        if (body.startsWith("【")) {
            return body;
        }

        return formatPoiResults(body, keyword);
    }

    /**
     * 内部 geocode：直接调用高德 API 获取坐标，不做格式化，返回「经度,纬度」或 null
     */
    private String internalGeocodeFetchCoordinates(String address) {
        String url = buildUrl(GEOCODE_URL, "address", address, "output", "JSON");
        String body = get(url);
        if (body.startsWith("【")) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode geocodes = root.path("geocodes");
            if (geocodes.isEmpty()) {
                return null;
            }
            return geocodes.get(0).path("location").asText();
        } catch (Exception e) {
            log.error("internalGeocodeFetchCoordinates 解析异常: {}", e.getMessage());
            return null;
        }
    }

    @Tool(description = "查询店铺详情（地址、电话、评分、消费等）。poiId 来自 searchPoi 结果。")
    public String getPoiDetail(
            @ToolParam(description = "POI ID，从 searchPoi 返回结果中获取") String poiId) {

        if (poiId == null || poiId.isBlank()) {
            return "【POI ID 为空】";
        }

        String url = buildUrl(PLACE_DETAIL_URL,
                "id", poiId.trim(),
                "show_fields", "business,navi,photos");

        log.info("getPoiDetail: poiId={}", poiId);
        String body = get(url);
        if (body.startsWith("【")) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                return "【POI 详情查询失败：" + root.path("info").asText("未知错误") + "】";
            }
            JsonNode pois = root.path("pois");
            if (pois.isEmpty()) {
                return "【未找到该 POI 的详情信息】";
            }
            return formatSinglePoi(pois.get(0), true);
        } catch (Exception e) {
            log.error("getPoiDetail 解析异常: {}", e.getMessage());
            return "【POI 详情解析失败：" + e.getMessage() + "】";
        }
    }

    @Tool(description = "规划出行路径。origin/destination 为坐标，type 可选 driving/walking/bicycling/transit。")
    public String planRoute(
            @ToolParam(description = "起点坐标，格式「经度,纬度」，如「113.95,22.54」") String origin,
            @ToolParam(description = "终点坐标，格式「经度,纬度」，如「114.05,22.55」") String destination,
            @ToolParam(description = "出行方式：driving（驾车，默认）/walking（步行）/bicycling（骑行）/transit（公交地铁）", required = false) String type) {

        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return "【起点或终点坐标为空】";
        }

        String mode = (type != null && !type.isBlank()) ? type.trim().toLowerCase() : "driving";
        String path;
        switch (mode) {
            case "walking" -> path = "/walking";
            case "bicycling" -> path = "/bicycling";
            case "transit" -> path = "/transit/integrated";
            default -> path = "/driving";
        }

        String url = buildUrl(DIRECTION_URL + path,
                "origin", origin.trim(),
                "destination", destination.trim());

        String modeLabel = switch (mode) {
            case "walking" -> "步行";
            case "bicycling" -> "骑行";
            case "transit" -> "公交地铁";
            default -> "驾车";
        };
        log.info("planRoute: origin={}, dest={}, mode={}", origin, destination, modeLabel);

        String body = get(url);
        if (body.startsWith("【")) {
            return body;
        }

        return formatRouteResult(body, modeLabel, mode);
    }

    // ==================== 结果格式化 ====================

    private String formatPoiResults(String body, String keyword) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                return "【POI 搜索失败：" + root.path("info").asText("未知错误") + "】";
            }
            JsonNode pois = root.path("pois");
            int count = root.path("count").asInt(0);
            if (count == 0 || pois.isEmpty()) {
                return "【未找到与「" + keyword + "」相关的店铺/地点】";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(count).append(" 个与「").append(keyword).append("」相关的结果：\n");
            int i = 1;
            for (JsonNode poi : pois) {
                if (i > 10) break;  // 最多 10 条
                sb.append("\n--- ").append(i).append(" ---\n");
                formatSinglePoiBrief(sb, poi);
                i++;
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("formatPoiResults 异常: {}", e.getMessage());
            return "【POI 搜索结果解析失败：" + e.getMessage() + "】";
        }
    }

    private void formatSinglePoiBrief(StringBuilder sb, JsonNode poi) {
        String name = poi.path("name").asText("");
        String address = poi.path("address").asText("");
        String distance = poi.path("distance").asText("");
        String rating = poi.path("biz_ext").path("rating").asText("");
        String cost = poi.path("biz_ext").path("cost").asText("");
        String tel = poi.path("tel").asText("");
        String businessArea = poi.path("business_area").asText("");
        String opentime = poi.path("biz_ext").path("opentime_today").asText("");
        String tag = poi.path("tag").asText("");
        String poiId = poi.path("id").asText("");

        sb.append("名称：").append(name).append("\n");
        if (!address.isBlank()) sb.append("地址：").append(address).append("\n");
        if (!businessArea.isBlank()) sb.append("商圈：").append(businessArea).append("\n");
        if (!distance.isBlank()) {
            try {
                int meters = Integer.parseInt(distance);
                if (meters >= 1000) {
                    sb.append("距离：").append(String.format("%.1f", meters / 1000.0)).append("公里\n");
                } else {
                    sb.append("距离：").append(meters).append("米\n");
                }
            } catch (NumberFormatException e) {
                sb.append("距离：").append(distance).append("米\n");
            }
        }
        if (!rating.isBlank()) sb.append("评分：").append(rating).append("\n");
        if (!cost.isBlank()) sb.append("人均：").append(cost).append("元\n");
        if (!tag.isBlank()) sb.append("标签：").append(tag).append("\n");
        if (!tel.isBlank()) sb.append("电话：").append(tel).append("\n");
        if (!opentime.isBlank()) sb.append("营业时间：").append(opentime).append("\n");
        sb.append("ID：").append(poiId).append("\n");
    }

    private String formatSinglePoi(JsonNode poi, boolean detail) {
        StringBuilder sb = new StringBuilder();
        formatSinglePoiBrief(sb, poi);
        if (detail) {
            String type = poi.path("type").asText("");
            if (!type.isBlank()) {
                // type 格式如 "餐饮服务;中餐厅;火锅店"，取三级分类中最具体的一项
                String[] parts = type.split(";");
                sb.append("类型：").append(parts[parts.length - 1]).append("\n");
            }
            String email = poi.path("email").asText("");
            if (!email.isBlank()) sb.append("邮箱：").append(email).append("\n");
            String website = poi.path("website").asText("");
            if (!website.isBlank()) sb.append("官网：").append(website).append("\n");
        }
        return sb.toString();
    }

    private String formatRouteResult(String body, String modeLabel, String mode) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                return "【路径规划失败：" + root.path("info").asText("未知错误") + "】";
            }

            JsonNode route;
            if ("transit".equals(mode)) {
                route = root.path("route").path("transits");
            } else {
                route = root.path("route").path("paths");
            }

            if (route.isEmpty()) {
                return "【未找到可行的" + modeLabel + "路线】";
            }

            // 取第一条路径
            JsonNode first = route.get(0);
            String distance = first.path("distance").asText("");
            String duration = first.path("duration").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append("【").append(modeLabel).append("路线】\n");
            if (!distance.isBlank()) {
                int meters = Integer.parseInt(distance);
                sb.append("总距离：").append(meters >= 1000
                        ? String.format("%.1f 公里", meters / 1000.0)
                        : distance + " 米").append("\n");
            }
            if (!duration.isBlank()) {
                int seconds = Integer.parseInt(duration);
                sb.append("预计耗时：");
                if (seconds >= 3600) {
                    sb.append(seconds / 3600).append(" 小时 ");
                    seconds %= 3600;
                }
                if (seconds >= 60) {
                    sb.append(seconds / 60).append(" 分钟");
                }
                sb.append("\n");
            }

            // 简要步骤（最多 5 步）
            JsonNode steps = first.path("steps");
            int maxSteps = Math.min(steps.size(), 5);
            for (int i = 0; i < maxSteps; i++) {
                JsonNode step = steps.get(i);
                String instruction = step.path("instruction").asText("");
                if (!instruction.isBlank()) {
                    sb.append("步骤 ").append(i + 1).append("：").append(instruction).append("\n");
                }
            }
            if (steps.size() > 5) {
                sb.append("... 共 ").append(steps.size()).append(" 步，以上为主要步骤\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("formatRouteResult 异常: {}", e.getMessage());
            return "【路径规划结果解析失败：" + e.getMessage() + "】";
        }
    }
}
