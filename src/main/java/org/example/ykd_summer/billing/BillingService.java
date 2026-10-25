package org.example.ykd_summer.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.config.properties.BillingProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 阿里云 BSS 余额查询服务。
 * <p>
 * 通过 HMAC-SHA1 签名调用 QueryAccountBalance API,
 * 获取账户实时可用余额。同时维护 SSE 订阅者，任务完成时主动推送最新余额。
 */
@Slf4j
@Service
public class BillingService {

    private final BillingProperties billingProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenUsageTracker tokenUsageTracker;
    private static final String ISO8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    // ===== 余额缓存 =====
    private volatile BillingBalance cachedBalance;
    private final ReentrantLock balanceLock = new ReentrantLock();

    // ===== SSE 推送 =====
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    // 去重：1.5 秒内的重复推送直接跳过，避免并发任务导致 BSS API 被刷爆
    private static final long PUSH_DEBOUNCE_MS = 1500L;
    private volatile long lastPushTimeMs = 0L;

    public BillingService(BillingProperties billingProperties, ObjectMapper objectMapper,
                          TokenUsageTracker tokenUsageTracker) {
        this.billingProperties = billingProperties;
        this.objectMapper = objectMapper;
        this.tokenUsageTracker = tokenUsageTracker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 启动时预热一次余额，避免首次 SSE 连接拿到空数据。
     */
    @PostConstruct
    void warmUp() {
        try {
            BillingBalance initial = queryBalance();
            balanceLock.lock();
            try {
                cachedBalance = initial;
            } finally {
                balanceLock.unlock();
            }
            if (initial.success()) {
                log.info("BSS 余额预热成功: ¥{:.4f} {}", initial.availableAmount(), initial.currency());
            } else {
                log.warn("BSS 余额预热失败: {}", initial.error());
            }
        } catch (Exception e) {
            log.warn("BSS 余额预热异常", e);
        }
    }

    /**
     * 查询账户可用余额。
     * @return BillingBalance（余额不为 null 时查询成功）
     */
    public BillingBalance queryBalance() {
        String accessKeyId = billingProperties.accessKeyId();
        String accessKeySecret = billingProperties.accessKeySecret();

        if (accessKeyId == null || accessKeyId.isBlank()
                || accessKeySecret == null || accessKeySecret.isBlank()) {
            return BillingBalance.unconfigured();
        }

        try {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("Action", "QueryAccountBalance");
            params.put("Format", "JSON");
            params.put("Version", billingProperties.bss() != null && billingProperties.bss().apiVersion() != null
                    ? billingProperties.bss().apiVersion() : "2017-12-14");
            params.put("AccessKeyId", accessKeyId);
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("Timestamp", formatTimestamp(new Date()));
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", generateNonce());

            String signature = sign(params, accessKeySecret);
            params.put("Signature", signature);

            String queryString = buildQueryString(params);
            String endpoint = billingProperties.bss() != null && billingProperties.bss().endpoint() != null
                    ? billingProperties.bss().endpoint() : "business.aliyuncs.com";

            URI uri = URI.create("https://" + endpoint + "/?" + queryString);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.path("Data");
                String availableAmount = data.path("AvailableAmount").asText();
                String currency = data.path("Currency").asText("CNY");

                if (!availableAmount.isEmpty()) {
                    return new BillingBalance(Double.parseDouble(availableAmount), currency, true);
                }
            }

            log.warn("BSS 余额查询异常: status={}, body={}", response.statusCode(), response.body());
            return BillingBalance.failed("状态码: " + response.statusCode());

        } catch (Exception e) {
            log.error("BSS 余额查询失败", e);
            return BillingBalance.failed(e.getMessage());
        }
    }

    /**
     * 获取最近一次缓存的余额（启动预热或主动 push 后会更新）。
     * 如果从未查询过则返回 unconfigured 哨兵。
     */
    public BillingBalance getCachedBalance() {
        BillingBalance c = cachedBalance;
        return c != null ? c : BillingBalance.unconfigured();
    }

    // ============ SSE 推送 ============

    /**
     * 注册一个余额 SSE 订阅者。立即推送一次当前缓存数据。
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            subscribers.remove(emitter);
        });
        emitter.onError(e -> subscribers.remove(emitter));

        // 立即推一次当前缓存
        try {
            BillingBalance cached = getCachedBalance();
            String json = objectMapper.writeValueAsString(buildBillingPayload(cached));
            emitter.send(SseEmitter.event().name("billing").data(json));
        } catch (IOException ignored) {
        }
        return emitter;
    }

    /**
     * 主动触发余额刷新并推送给所有 SSE 订阅者。
     * <p>
     * <b>两步策略</b>：
     * <ol>
     *   <li><b>立即推送</b>：用当前缓存余额 + TokenUsageTracker 的最新 totalCost
     *       构建 SSE 事件，即时推送给前端。这保证任务的实时消费数据第一时间可见。</li>
     *   <li><b>异步查 BSS</b>：带 1.5s 防抖（仅防 BSS API 调用，不防 SSE 推送），
     *       查到最新账户余额后更新缓存并再次推送。</li>
     * </ol>
     * <p>
     * 这样即使阿里云 BSS 余额是异步结算的（数值暂时不变），前端的消费折线也能实时增长。
     */
    public void pushToAll() {
        // ---- 第 1 步：立即推送当前缓存余额 + 最新 token 消费 ----
        pushBalanceToSubscribers(getCachedBalance());

        // ---- 第 2 步：异步查询 BSS 真实余额（带防抖） ----
        long now = System.currentTimeMillis();
        long last = lastPushTimeMs;
        if (now - last < PUSH_DEBOUNCE_MS) {
            // 仅在 BSS API 层面防抖，SSE 推送不受影响
            return;
        }
        lastPushTimeMs = now;

        Thread.ofVirtual().name("billing-bss-query").start(() -> {
            try {
                BillingBalance balance = queryBalance();
                balanceLock.lock();
                try {
                    cachedBalance = balance;
                } finally {
                    balanceLock.unlock();
                }
                // 用最新 BSS 余额再次推送
                pushBalanceToSubscribers(balance);
                if (balance.success()) {
                    log.debug("BSS 余额查询成功并已推送: ¥{:.4f}", balance.availableAmount());
                } else {
                    log.debug("BSS 余额查询失败: {}", balance.error());
                }
            } catch (Exception e) {
                log.warn("BSS 余额主动查询异常", e);
            }
        });
    }

    /**
     * 将指定余额数据与当前 token 消费快照打包并推送给所有 SSE 订阅者。
     */
    private void pushBalanceToSubscribers(BillingBalance balance) {
        if (subscribers.isEmpty()) {
            log.debug("无 SSE 订阅者，跳过余额推送");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(buildBillingPayload(balance));
            log.info("推送余额 SSE 事件给 {} 个订阅者: {}", subscribers.size(), json);
            for (SseEmitter emitter : subscribers) {
                try {
                    emitter.send(SseEmitter.event().name("billing").data(json));
                } catch (Exception e) {
                    subscribers.remove(emitter);
                }
            }
        } catch (IOException e) {
            log.warn("序列化余额 SSE 事件失败", e);
        }
    }

    /**
     * 构建包含余额 + Token消费的 SSE 推送 payload。
     * <p>
     * BSS 余额是异步结算的（分钟/小时级），直接推送原始余额可能不会变化。
     * 这里会附带 TokenUsageTracker 的累计消费 cost，前端可据此展示实时估算余额。
     */
    private Map<String, Object> buildBillingPayload(BillingBalance balance) {
        TokenUsageSnapshot snapshot = tokenUsageTracker.getSnapshot();
        Map<String, Object> balanceData = new LinkedHashMap<>();
        balanceData.put("availableAmount", balance.availableAmount());
        balanceData.put("currency", balance.currency());
        balanceData.put("success", balance.success());
        balanceData.put("error", balance.error());
        balanceData.put("totalCost", snapshot.totalCost());
        if (balance.availableAmount() != null) {
            balanceData.put("estimatedBalance", Math.max(0, balance.availableAmount() - snapshot.totalCost()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "billing");
        payload.put("balance", balanceData);
        return payload;
    }

    // ============ 签名相关 ============

    private String buildQueryString(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(percentEncode(entry.getKey()));
            sb.append('=');
            sb.append(percentEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private String sign(TreeMap<String, String> params, String accessKeySecret) throws Exception {
        String canonicalized = buildQueryString(params);
        String stringToSign = "GET&%2F&" + percentEncode(canonicalized);
        Mac mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec keySpec = new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1");
        mac.init(keySpec);
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signData);
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String formatTimestamp(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(ISO8601_FORMAT);
        sdf.setTimeZone(new SimpleTimeZone(0, "GMT"));
        return sdf.format(date);
    }

    private static String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 余额查询结果。
     */
    public record BillingBalance(
            /** 可用余额（元），null=未查询 */
            Double availableAmount,
            /** 货币单位 */
            String currency,
            /** 是否查询成功 */
            boolean success,
            /** 失败原因 */
            String error
    ) {
        public BillingBalance(Double availableAmount, String currency, boolean success) {
            this(availableAmount, currency, success, null);
        }

        public static BillingBalance unconfigured() {
            return new BillingBalance(null, "CNY", false, "未配置 AccessKey");
        }

        public static BillingBalance failed(String error) {
            return new BillingBalance(null, "CNY", false, error);
        }
    }
}
