package org.example.ykd_summer.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具，挂载到 TEXT Agent 上。
 * <p>
 * 直连 DashScope 原生端点（/api/v1/services/aigc/text-generation/generation），
 * 绕过 Spring AI，直接控制请求 JSON，确保 enable_search / forced_search 生效。
 * <p>
 * 选择 DashScope 原生端点而非 OpenAI 兼容端点的原因：
 * <ul>
 *   <li>原生端点支持 enable_source=true，返回 search_info.search_results（来源标题+URL），
 *       是 prompt 中「每条推荐必须标注来源」反幻觉规则的数据基础</li>
 *   <li>usage.plugins.search 记录搜索调用次数与策略，搜索行为可观测、可审计</li>
 *   <li>OpenAI 兼容协议层面不支持返回搜索来源</li>
 * </ul>
 * 模型选择 qwen-plus（与主 Agent 同档）：qwen-turbo 实测会把「南京」误读为
 * 「南京路步行街」（上海），搜索合成质量优先于速度。
 */
@Slf4j
@Component
public class WebSearchTools {

    private static final String SEARCH_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    private static final String SEARCH_MODEL = "qwen-plus";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public WebSearchTools(@Value("${spring.ai.openai.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 联网搜索实时/时效性信息。
     * <p>
     * 返回格式：搜索合成答案 + 来源列表（标题/站点/URL）。
     * 模型必须基于返回内容忠实转述，严禁添加其中不存在的细节。
     */
    @Tool(description = "联网搜索实时信息。适用于活动/新闻/演出等时效性查询。")
    public String webSearch(
            @ToolParam(description = "搜索关键词，包含时间范围与地域，如「2026年8月 南京 动漫展」或「最近热门电影」。若用户用词模糊（最近/近期），工具会自动按当前日期补充具体年月，无需反问用户。")
            String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("WebSearchTools: DashScope API Key 未配置");
            return "【联网搜索服务未配置：缺少 API Key】";
        }
        log.info("webSearch: query={}", query);

        // 规范化相对时间词：模型常把「最近/近期」脑补成训练数据中的旧年份（如 2024），
        // 导致时效性搜索失效。这里用服务器当前日期兜底纠正。
        String normalizedQuery = normalizeTimeQuery(query);
        if (!normalizedQuery.equals(query)) {
            log.warn("webSearch: 时间词已规范化, before={}, after={}", query, normalizedQuery);
            query = normalizedQuery;
        }

        try {
            String requestJson = buildRequestJson(query);
            Request request = new Request.Builder()
                    .url(SEARCH_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestJson, JSON_MEDIA))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("webSearch: DashScope 请求失败, code={}, body={}", response.code(),
                            body.length() > 500 ? body.substring(0, 500) : body);
                    return "【联网搜索请求失败，状态码：" + response.code() + "】";
                }
                return parseResponse(body);
            }
        } catch (Exception e) {
            log.error("webSearch: 调用异常, query={}, error={}", query, e.getMessage());
            return "【联网搜索调用异常：" + e.getMessage() + "】";
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 相对时间词规范化：模型在调用本工具时常把「最近/近期/现在」错误推断为训练数据中的旧年月，
     * 使时效性搜索返回过时结果。这里用服务器当前日期兜底：
     * <ul>
     *   <li>若 query 含相对时间词（最近/近期/近来/现在/目前/当下）但年份与当前年份不一致，
     *       将其中错误的「YYYY年M月」整体替换为当前「YYYY年M月」；</li>
     *   <li>若 query 完全不含任何 4 位年份且含相对时间词，则在前面补上当前「YYYY年M月 」前缀。</li>
     * </ul>
     * 注意：当 query 已含与当前年份一致的明确年月（如用户明确说 2026年8月），则原样保留，不做改动。
     */
    private String normalizeTimeQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        LocalDate now = LocalDate.now();
        int curYear = now.getYear();
        int curMonth = now.getMonthValue();
        String curYearMonth = curYear + "年" + curMonth + "月";

        boolean hasRelativeWord = RELATIVE_TIME_PATTERN.matcher(query).find();
        if (!hasRelativeWord) {
            return query;
        }

        // 匹配 query 中出现的「YYYY年M月」或「YYYY年」（支持 1-2 位月/不带月）
        Matcher ym = YEAR_MONTH_PATTERN.matcher(query);
        if (ym.find()) {
            String matched = ym.group(0);
            int year = Integer.parseInt(ym.group(1));
            // 已是当前年份的明确年月，视为可信，保留
            if (year == curYear) {
                return query;
            }
            // 模型给的年份不是当前年份 -> 不可信，整体替换为当前年月
            return query.replace(matched, curYearMonth);
        }

        // 无明确年份，补前缀
        return curYearMonth + " " + query;
    }

    private static final Pattern RELATIVE_TIME_PATTERN =
            Pattern.compile("(最近|近期|近来|现在|目前|当下|这段时间|最近几年|近日)");
    private static final Pattern YEAR_MONTH_PATTERN =
            Pattern.compile("(20\\d{2})年(\\d{1,2})?月?");

    /**
     * 构建 DashScope 原生 Generation API 请求 JSON。
     * forced_search=true 确保必定搜索；enable_source=true 要求返回来源列表。
     */
    private String buildRequestJson(String query) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", SEARCH_MODEL);

        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", query);

        ObjectNode parameters = root.putObject("parameters");
        parameters.put("result_format", "message");
        parameters.put("enable_search", true);
        ObjectNode searchOptions = parameters.putObject("search_options");
        searchOptions.put("forced_search", true);
        searchOptions.put("search_strategy", "turbo");
        searchOptions.put("enable_source", true);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 DashScope 原生响应：提取合成答案 + 来源列表，拼接为模型易读的文本。
     */
    private String parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode output = root.path("output");

        String content = output.path("choices").path(0)
                .path("message").path("content").asText("");
        if (content.isBlank()) {
            log.warn("webSearch: 响应中无内容, body={}",
                    body.length() > 500 ? body.substring(0, 500) : body);
            return "【联网搜索未返回有效内容】";
        }

        // 搜索调用审计：usage.plugins.search
        JsonNode searchUsage = root.path("usage").path("plugins").path("search");
        int searchCount = searchUsage.path("count").asInt(0);

        JsonNode sources = output.path("search_info").path("search_results");
        StringBuilder sb = new StringBuilder();
        sb.append("【搜索结果】\n").append(content);

        if (sources.isArray() && !sources.isEmpty()) {
            sb.append("\n\n【来源】");
            for (JsonNode src : sources) {
                sb.append("\n").append(src.path("index").asInt())
                        .append(". ").append(src.path("title").asText("无标题"))
                        .append(" — ").append(src.path("site_name").asText("未知站点"))
                        .append("（").append(src.path("url").asText("")).append("）");
            }
        }
        log.info("webSearch: 完成, searchCount={}, 来源数={}, contentLen={}",
                searchCount, sources.isArray() ? sources.size() : 0, content.length());
        return sb.toString();
    }
}
