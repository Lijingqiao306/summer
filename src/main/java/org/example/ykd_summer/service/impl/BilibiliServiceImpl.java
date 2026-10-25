package org.example.ykd_summer.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.ykd_summer.dto.BilibiliComment;
import org.example.ykd_summer.dto.BilibiliDynamicStat;
import org.example.ykd_summer.dto.BilibiliTrendingVideo;
import org.example.ykd_summer.dto.BilibiliUserInfo;
import org.example.ykd_summer.dto.BilibiliVideoStat;
import org.example.ykd_summer.dto.VideoMetadata;
import org.example.ykd_summer.dto.VideoUploadResult;
import org.example.ykd_summer.service.BilibiliService;
import org.example.ykd_summer.store.BilibiliCookieStore;
import org.example.ykd_summer.utils.BilibiliSignUtil;
import org.example.ykd_summer.web.ConsoleEvent;
import org.example.ykd_summer.web.ConsoleEventBus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

/**
 * B站数据采集服务实现。
 * <p>
 * 通过 OkHttp + WBI 签名调用 B站 API，采集粉丝数、最新视频统计、最新动态统计。
 * 启动时自动从 nav 接口获取实时 WBI 签名密钥，所有 WBI 接口均携带 Cookie 以通过风控。
 */
@Slf4j
@Service
public class BilibiliServiceImpl implements BilibiliService {

    private static final String API_BASE = "https://api.bilibili.com";
    private static final String BILIBILI_REFERER = "https://www.bilibili.com/";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final BilibiliCookieStore cookieStore;
    private final ConsoleEventBus eventBus;

    @Value("${bilibili.uid}")
    private String configuredUid;

    /** 默认封面图路径（发布视频时使用，通过 application.yml 配置） */
    @Value("${bilibili.video.default-cover:}")
    private String defaultCoverPath;

    /** 解析后的实际 UID（配置优先，空则从 Cookie 中提取 DedeUserID） */
    private final String uid;

    public BilibiliServiceImpl(BilibiliCookieStore cookieStore, ConsoleEventBus eventBus) {
        this.cookieStore = cookieStore;
        this.eventBus = eventBus;
        this.uid = resolveUid();
        log.info("B站 UID 已解析: {}", uid);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    /**
     * 应用启动后立即获取实时 WBI 签名密钥，避免使用过期密钥导致风控拦截。
     */
    @PostConstruct
    public void initWbiKeys() {
        // 校验默认封面配置
        if (defaultCoverPath != null && !defaultCoverPath.isBlank()) {
            Path coverPath = Path.of(defaultCoverPath);
            if (!Files.exists(coverPath)) {
                log.warn("B站视频默认封面文件不存在: {}，上传视频时将无法使用默认封面，请检查 application.yml 中 bilibili.video.default-cover 配置", coverPath.toAbsolutePath());
            } else {
                log.info("B站视频默认封面已就绪: {} (size={} bytes)", coverPath.toAbsolutePath(), coverPath.toFile().length());
            }
        } else {
            log.info("未配置 B站 视频默认封面路径（bilibili.video.default-cover 为空），用户选择「使用默认封面」时将跳过封面上传");
        }
        doRefreshWbiKeys();
    }

    @Override
    public void refreshWbiKeys() {
        doRefreshWbiKeys();
    }

    /**
     * 从 /x/web-interface/nav 提取 WBI 密钥并刷新。
     */
    private void doRefreshWbiKeys() {
        try {
            log.info("正在获取 B站 WBI 实时签名密钥...");
            String navUrl = API_BASE + "/x/web-interface/nav";
            JsonNode navData = executeGetInternal(navUrl, true, BILIBILI_REFERER);
            if (navData != null) {
                JsonNode wbiImg = navData.path("wbi_img");
                String imgUrl = wbiImg.path("img_url").asText("");
                String subUrl = wbiImg.path("sub_url").asText("");

                String imgKey = extractKeyFromUrl(imgUrl);
                String subKey = extractKeyFromUrl(subUrl);

                if (!imgKey.isBlank() && !subKey.isBlank()) {
                    BilibiliSignUtil.refreshMixKey(imgKey, subKey);
                    log.info("WBI 签名密钥已更新: imgKey={}, subKey={}", imgKey, subKey);
                } else {
                    log.warn("无法从 nav 接口提取 WBI 密钥，使用内置固定密钥作为回退");
                }
            } else {
                log.warn("nav 接口返回空数据，使用内置固定密钥作为回退");
            }
        } catch (Exception e) {
            log.warn("获取 WBI 实时密钥失败，使用内置固定密钥作为回退: {}", e.getMessage());
        }
    }

    /**
     * 从 wbi_img URL 中提取密钥（取路径最后一段，去掉 .png 后缀）。
     * <p>如 {@code .../bfs/wbi/7cd084941338484aae1ad9425b84077c.png} → {@code 7cd084941338484aae1ad9425b84077c}
     */
    private String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) return "";
        String filename = url.substring(lastSlash + 1);
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }

    // ==================== 用户信息 ====================

    @Override
    public BilibiliUserInfo getUserInfo() {
        log.info("采集 B站用户信息: mid={}", uid);

        // 使用 /x/relation/stat 获取粉丝数（该接口专门提供关注/粉丝数据，更可靠）
        int follower = 0;
        Map<String, String> relParams = new HashMap<>();
        relParams.put("vmid", uid);
        String relUrl = buildWbiUrl("/x/relation/stat", relParams);
        JsonNode relData = executeGetWithCookie(relUrl);
        if (relData != null) {
            follower = relData.path("follower").asInt(0);
        }

        // 使用 /x/space/v2/myinfo 获取用户名（该接口无需 WBI 签名，仅需 Cookie 和 web_location）
        String name = "";
        Map<String, String> myParams = new HashMap<>();
        myParams.put("web_location", "333.1387");
        String myUrl = buildUrl("/x/space/v2/myinfo", myParams);
        JsonNode myData = executeGetWithCookie(myUrl);
        if (myData != null) {
            JsonNode profile = myData.path("profile");
            name = profile.path("name").asText("");
        }

        log.info("用户信息采集完成: name={}, follower={}", name, follower);
        return BilibiliUserInfo.builder()
                .mid(uid)
                .name(name)
                .followerCount(follower)
                .build();
    }

    // ==================== 最新视频统计 ====================

    @Override
    public BilibiliVideoStat getLatestVideoStat() {
        log.info("采集 B站最新视频: mid={}", uid);

        // Step 1: 从 polymer 动态 feed 中获取最新视频动态的 bvid/aid
        //         （该接口无需 WBI 签名和 dm_* 参数，绕过 arc/search 的 403 风控）
        Map<String, String> feedParams = new HashMap<>();
        feedParams.put("host_mid", uid);
        String feedUrl = buildUrl("/x/polymer/web-dynamic/v1/feed/space", feedParams);
        JsonNode feedData = executeGetWithCookie(feedUrl);
        if (feedData == null) {
            return null;
        }

        JsonNode items = feedData.path("items");
        if (!items.isArray()) {
            log.info("该用户暂无动态");
            return null;
        }

        // 找到第一条视频动态 (DYNAMIC_TYPE_AV)
        String aid = null, bvid = "", title = "";
        for (JsonNode item : items) {
            String type = item.path("type").asText("");
            if ("DYNAMIC_TYPE_AV".equals(type)) {
                JsonNode archive = item.path("modules")
                        .path("module_dynamic")
                        .path("major")
                        .path("archive");
                aid = String.valueOf(archive.path("aid").asLong());
                bvid = archive.path("bvid").asText("");
                title = archive.path("title").asText("");
                break;
            }
        }

        if (aid == null || bvid.isBlank()) {
            log.info("该用户暂无视频动态");
            return null;
        }

        // Step 2: 通过 /x/web-interface/wbi/view 获取详细统计（该接口无需 dm_* 参数）
        Map<String, String> viewParams = new HashMap<>();
        viewParams.put("aid", aid);
        String viewUrl = buildWbiUrl("/x/web-interface/wbi/view", viewParams);

        JsonNode viewData = executeGetWithCookie(viewUrl);
        JsonNode stat = (viewData != null) ? viewData.path("stat") : null;

        int play = (stat != null) ? stat.path("view").asInt(0) : 0;
        int danmaku = (stat != null) ? stat.path("danmaku").asInt(0) : 0;
        int reply = (stat != null) ? stat.path("reply").asInt(0) : 0;
        int like = (stat != null) ? stat.path("like").asInt(0) : 0;

        String videoUrl = "https://www.bilibili.com/video/" + bvid;

        log.info("最新视频采集完成: title={}, play={}, like={}, reply={}, danmaku={}",
                title, play, like, reply, danmaku);

        return BilibiliVideoStat.builder()
                .aid(aid)
                .bvid(bvid)
                .title(title)
                .url(videoUrl)
                .playCount(play)
                .likeCount(like)
                .replyCount(reply)
                .danmakuCount(danmaku)
                .build();
    }

    // ==================== 最新动态统计 ====================

    @Override
    public BilibiliDynamicStat getLatestDynamic() {
        log.info("采集 B站最新动态: mid={}", uid);

        Map<String, String> params = new HashMap<>();
        params.put("host_mid", uid);
        // polymer 接口不需要 WBI 签名，但需要 Cookie
        String url = buildUrl("/x/polymer/web-dynamic/v1/feed/space", params);

        JsonNode data = executeGetWithCookie(url);
        if (data == null) {
            return null;
        }

        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) {
            log.info("该用户暂无动态");
            return null;
        }

        JsonNode firstItem = items.get(0);
        String dynamicId = firstItem.path("id_str").asText("");
        String dynamicType = firstItem.path("type").asText("UNKNOWN");

        // 提取动态内容
        String content = extractDynamicContent(firstItem);

        // 提取统计数据
        JsonNode moduleStat = firstItem.path("modules").path("module_stat");
        int likeCount = moduleStat.path("like").path("count").asInt(0);
        int commentCount = moduleStat.path("comment").path("count").asInt(0);
        int forwardCount = moduleStat.path("forward").path("count").asInt(0);

        String dynamicUrl = "https://t.bilibili.com/" + dynamicId;
        String typeDesc = mapDynamicType(dynamicType);

        log.info("最新动态采集完成: type={}, like={}, comment={}, forward={}",
                typeDesc, likeCount, commentCount, forwardCount);

        return BilibiliDynamicStat.builder()
                .dynamicId(dynamicId)
                .type(typeDesc)
                .content(content)
                .url(dynamicUrl)
                .likeCount(likeCount)
                .commentCount(commentCount)
                .forwardCount(forwardCount)
                .build();
    }

    // ==================== 发动态 ====================

    @Override
    public boolean postDynamic(String content) {
        log.info("发布 B站纯文本动态: content={}", content);

        try {
            String url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/create";
            String biliJct = extractCookieField("bili_jct");

            if (biliJct.isBlank()) {
                log.warn("bili_jct 为空，可能 Cookie 不完整");
                return false;
            }

            FormBody body = new FormBody.Builder()
                    .add("dynamic_id", "0")
                    .add("type", "4")
                    .add("rid", "0")
                    .add("content", content)
                    .add("csrf", biliJct)
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookieStore.load())
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", BILIBILI_REFERER)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("B站发动态 HTTP异常: code={}", response.code());
                    return false;
                }
                String bodyStr = response.body() != null ? response.body().string() : "";
                JsonNode root = objectMapper.readTree(bodyStr);

                int code = root.path("code").asInt(-1);
                if (code == 0) {
                    String dynamicId = root.path("data").path("dynamic_id").asText(
                            root.path("data").path("dynamic_id_str").asText(""));
                    log.info("B站动态发布成功: dynamic_id={}, content={}", dynamicId, content);
                    return true;
                }

                String message = root.path("message").asText("");
                log.warn("B站动态发布失败: code={}, message={}", code, message);
                return false;
            }
        } catch (Exception e) {
            log.error("B站动态发布异常", e);
            return false;
        }
    }

    // ==================== 发带图动态（REST API） ====================

    @Override
    public boolean postDynamicWithImage(String content, byte[] imageBytes, String contentType, String fileName) {
        log.info("发布B站带图动态: content={}, imageSize={} bytes", content, imageBytes != null ? imageBytes.length : 0);
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("图片数据为空");
            return false;
        }
        try {
            String imageUrl = uploadImageToBilibili(imageBytes);
            if (imageUrl == null || imageUrl.isBlank()) {
                log.warn("图片上传到 B站失败");
                return false;
            }
            log.info("图片上传成功, url={}", imageUrl);
            return createImageDynamicWithApi(content, imageUrl, imageBytes);
        } catch (Exception e) {
            log.error("B站带图动态发布异常", e);
            return false;
        }
    }

    private String uploadImageToBilibili(byte[] imageBytes) {
        try {
            String cookie = cookieStore.load();
            String biliJct = extractCookieField("bili_jct");
            if (biliJct.isBlank()) { log.warn("bili_jct 为空"); return null; }

            RequestBody fileBody = RequestBody.create(imageBytes, MediaType.parse("image/jpeg"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file_up", "upload.jpg", fileBody)
                    .addFormDataPart("category", "daily")
                    .addFormDataPart("biz", "draw")
                    .addFormDataPart("csrf", biliJct)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", BILIBILI_REFERER)
                    .post(body)
                    .build();

            try (Response resp = httpClient.newCall(request).execute()) {
                String bodyStr = resp.body() != null ? resp.body().string() : "";
                JsonNode root = objectMapper.readTree(bodyStr);
                int code = root.path("code").asInt(-1);
                if (code == 0) return root.path("data").path("image_url").asText("");
                log.warn("图片上传失败: code={}, msg={}", code, root.path("message").asText(""));
                return null;
            }
        } catch (Exception e) { log.error("图片上传异常", e); return null; }
    }

    /** 通过 REST API 发布图文动态（POST create_draw，category=daily） */
    private boolean createImageDynamicWithApi(String content, String imageUrl, byte[] imageBytes) {
        try {
            String cookie = cookieStore.load();
            String biliJct = extractCookieField("bili_jct");
            if (biliJct.isBlank()) { log.warn("bili_jct 为空"); return false; }

            int w = 800, h = 600;
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(imageBytes));
                if (img != null) { w = img.getWidth(); h = img.getHeight(); }
            } catch (Exception ignored) {}

            String pics = "[{\"img_src\":\"" + imageUrl + "\",\"img_width\":" + w + ",\"img_height\":" + h + "}]";

            FormBody body = new FormBody.Builder()
                    .add("content", content)
                    .add("pictures", pics)
                    .add("category", "daily")
                    .add("csrf", biliJct)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/create_draw")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", BILIBILI_REFERER)
                    .post(body)
                    .build();

            try (Response resp = httpClient.newCall(request).execute()) {
                String bodyStr = resp.body() != null ? resp.body().string() : "";
                JsonNode root = objectMapper.readTree(bodyStr);
                int code = root.path("code").asInt(-1);
                if (code == 0) {
                    String dynId = root.path("data").path("dynamic_id_str").asText("");
                    log.info("图文动态发布成功: dynamic_id={}", dynId);
                    return true;
                }
                log.warn("图文动态创建失败: code={}, msg={}", code, root.path("message").asText(""));
                return false;
            }
        } catch (Exception e) {
            log.error("图文动态创建异常", e);
            return false;
        }
    }

    // ==================== Cookie 有效性检查 ====================

    @Override
    public boolean checkCookieValid() {
        try {
            String navUrl = API_BASE + "/x/web-interface/nav";
            JsonNode root = executeGetRawWithCookie(navUrl);
            if (root == null) {
                log.warn("Cookie 有效性检查：nav 接口返回空");
                return false;
            }
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                log.info("Cookie 有效性检查：有效");
                return true;
            }
            if (code == -101) {
                log.warn("Cookie 有效性检查：未登录或已过期 (code=-101)");
            } else {
                log.warn("Cookie 有效性检查：异常 code={}, message={}",
                        code, root.path("message").asText(""));
            }
            return false;
        } catch (Exception e) {
            log.error("Cookie 有效性检查异常", e);
            return false;
        }
    }

    // ==================== 视频上传 ====================

    /**
     * B站预上传接口地址。
     */
    private static final String PREUPLOAD_URL = "https://member.bilibili.com/preupload";

    /**
     * UPOS 上传配置（普通视频通道）。创建会话、合片时必须与预上传保持一致。
     */
    private static final String UPOS_PROFILE = "ugcupos/bup";

    /**
     * 分片上传专用 HTTP 客户端：写超时更长，避免大分片被默认 15s 超时中断。
     */
    private final OkHttpClient uploadHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build();

    @Override
    public VideoUploadResult uploadVideo(byte[] videoBytes, String fileName) {
        return uploadVideo(videoBytes, fileName, null);
    }

    @Override
    public VideoUploadResult uploadVideo(byte[] videoBytes, String fileName, VideoMetadata metadata) {
        return uploadVideo(videoBytes, fileName, metadata, null);
    }

    @Override
    public VideoUploadResult uploadVideo(byte[] videoBytes, String fileName, VideoMetadata metadata,
                                         byte[] coverBytes) {
        if (videoBytes == null || videoBytes.length == 0) {
            log.warn("视频数据为空，无法上传");
            return null;
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "video.mp4";
        }
        if (metadata == null) {
            metadata = VideoMetadata.defaults(fileName);
        }
        log.info("开始上传B站视频: fileName={}, size={} bytes, title={}, tid={}",
                fileName, videoBytes.length, metadata.title(), metadata.tid());

        try {
            String cookie = cookieStore.load();
            if (cookie == null || cookie.isBlank()) {
                log.warn("B站 Cookie 为空，无法上传视频");
                return null;
            }

            // Step 1: 预上传，获取 endpoint / upos_uri / auth / biz_id
            JsonNode preData = doPreupload(videoBytes.length, fileName, cookie);
            if (preData == null) {
                return null;
            }

            String auth = preData.path("auth").asText("");
            String endpoint = preData.path("endpoint").asText("");
            String uposUri = preData.path("upos_uri").asText("");
            long bizId = preData.path("biz_id").asLong(0);
            int chunkSize = preData.path("chunk_size").asInt(10_485_760);
            int chunkRetry = Math.max(1, preData.path("chunk_retry").asInt(3));

            log.info("预上传成功: endpoint={}, upos_uri={}, biz_id={}, chunk_size={}, auth={}",
                    endpoint, uposUri, bizId, chunkSize, auth != null && !auth.isBlank());

            if (endpoint.isBlank() || uposUri.isBlank() || bizId <= 0) {
                log.warn("预上传返回数据不完整: endpoint={}, upos_uri={}, biz_id={}",
                        endpoint, uposUri, bizId);
                return null;
            }

            // 构建上传 URL
            String base = endpoint.startsWith("//") ? "https:" + endpoint : endpoint;
            String uriPath = uposUri.replace("upos://", "");
            String uploadUrl = base + "/" + uriPath;

            // Step 2: 创建 UPOS 上传会话，获取 uploadId
            String uploadId = createUposSession(uploadUrl, auth, videoBytes.length, chunkSize, bizId);
            if (uploadId == null) {
                return null;
            }

            // Step 3: 分片上传（支持多分片 + 失败重试）
            int chunks = uploadUposChunks(uploadUrl, auth, uploadId, videoBytes, chunkSize, chunkRetry);
            if (chunks <= 0) {
                return null;
            }

            // Step 4: 合片确认
            if (!completeUposUpload(uploadUrl, auth, uploadId, bizId, fileName, chunks, cookie)) {
                return null;
            }

            // 从 upos_uri 提取实际文件名
            String actualFileName = extractFileNameFromUposUri(uposUri);
            log.info("视频文件上传成功: fileName={}, bizId={}", actualFileName, bizId);

            // Step 4.5: 上传封面图（API 方式，优先用户提供的封面，否则用默认封面）
            String coverUrl = uploadCoverImage(cookie, coverBytes);
            if (coverUrl == null) {
                log.warn("封面上传失败，草稿将不带封面（发布环节再补）");
            }

            // Step 5: 携带元数据保存到创作中心草稿箱
            Long draftAid = saveVideoDraft(actualFileName, bizId, fileName, cookie, metadata, coverUrl);
            if (draftAid == null) {
                log.warn("草稿保存失败: fileName={}, bizId={}", actualFileName, bizId);
                return null;
            }
            return new VideoUploadResult(actualFileName, String.valueOf(bizId), bizId, draftAid);
        } catch (Exception e) {
            log.error("视频上传异常", e);
            return null;
        }
    }

    /**
     * 通过 API 上传封面图到 B站 BFS，返回封面 URL。
     * <p>
     * 接口: POST /x/vu/web/cover/up（biliup-rs 同款），multipart 上传图片字节。
     *
     * @return BFS 封面 URL（如 "http://i0.hdslb.com/bfs/archive/xxx.jpg"），失败返回 null
     */
    private String uploadCoverImage(String cookie, byte[] customCoverBytes) {
        try {
            byte[] coverBytes;
            String mime;
            if (customCoverBytes != null && customCoverBytes.length > 0) {
                coverBytes = customCoverBytes;
                mime = detectImageMime(coverBytes);
                log.info("使用用户提供的封面图片: size={} bytes, mime={}", coverBytes.length, mime);
            } else {
                java.nio.file.Path coverPath = java.nio.file.Path.of(defaultCoverPath);
                if (!java.nio.file.Files.exists(coverPath)) {
                    log.warn("默认封面文件不存在: {}", coverPath);
                    return null;
                }
                coverBytes = java.nio.file.Files.readAllBytes(coverPath);
                mime = "image/jpeg";
                log.info("使用默认封面图片: {}", coverPath);
            }
            String biliJct = extractCookieField("bili_jct");

            // biliup 同款：form 表单提交 base64 data URI，而非 multipart 文件上传
            String dataUri = "data:" + mime + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(coverBytes);
            FormBody body = new FormBody.Builder()
                    .add("cover", dataUri)
                    .add("csrf", biliJct)
                    .build();
            Request request = new Request.Builder()
                    .url("https://member.bilibili.com/x/vu/web/cover/up")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://member.bilibili.com/platform/upload/video/frame")
                    .post(body)
                    .build();
            try (Response resp = httpClient.newCall(request).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                log.info("封面上传响应: HTTP {}, body={}", resp.code(), respBody);
                if (!resp.isSuccessful()) {
                    return null;
                }
                JsonNode root = objectMapper.readTree(respBody);
                if (root.path("code").asInt(-1) == 0) {
                    String url = root.path("data").path("url").asText("");
                    if (!url.isBlank()) {
                        log.info("封面上传成功: {}", url);
                        return url;
                    }
                }
                log.warn("封面上传返回异常: {}", respBody);
                return null;
            }
        } catch (Exception e) {
            log.warn("封面上传异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Step 1: 预上传，获取上传元数据。
     */
    private JsonNode doPreupload(long fileSize, String fileName, String cookie) throws Exception {
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        String preuploadUrl = PREUPLOAD_URL
                + "?r=upos&profile=" + URLEncoder.encode(UPOS_PROFILE, StandardCharsets.UTF_8)
                + "&name=" + encodedName
                + "&size=" + fileSize;

        Request preReq = new Request.Builder()
                .url(preuploadUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://member.bilibili.com/")
                .header("Cookie", cookie)
                .get()
                .build();

        try (Response preResp = httpClient.newCall(preReq).execute()) {
            if (!preResp.isSuccessful()) {
                log.warn("预上传请求失败: HTTP {}", preResp.code());
                return null;
            }
            String bodyStr = preResp.body() != null ? preResp.body().string() : "";
            JsonNode root = objectMapper.readTree(bodyStr);
            int ok = root.path("OK").asInt(0);
            if (ok != 1) {
                log.warn("预上传返回失败: {}", bodyStr);
                return null;
            }
            log.info("预上传完整响应: {}", root.toPrettyString());
            return root;
        }
    }

    /**
     * Step 2: 创建 UPOS 上传会话，获取 uploadId。
     * 注意：uploads= 必须带等号；profile/filesize/partsize/biz_id 为必要参数。
     */
    private String createUposSession(String uploadUrl, String auth, long fileSize,
                                     int chunkSize, long bizId) {
        String initUrl = uploadUrl
                + "?uploads=&output=json"
                + "&profile=" + URLEncoder.encode(UPOS_PROFILE, StandardCharsets.UTF_8)
                + "&filesize=" + fileSize
                + "&partsize=" + chunkSize
                + "&biz_id=" + bizId;
        log.info("UPOS 创建上传会话: {}", initUrl);
        Request.Builder initReqBuilder = new Request.Builder()
                .url(initUrl)
                .header("User-Agent", USER_AGENT)
                .post(RequestBody.create(new byte[0],
                        MediaType.parse("application/octet-stream")));
        if (auth != null && !auth.isBlank()) {
            initReqBuilder.header("X-Upos-Auth", auth);
        }
        try (Response initResp = uploadHttpClient.newCall(initReqBuilder.build()).execute()) {
            String initBody = initResp.body() != null ? initResp.body().string() : "";
            log.info("UPOS 创建会话响应: HTTP {}, body={}", initResp.code(), initBody);
            if (!initResp.isSuccessful()) {
                log.warn("UPOS 创建上传会话失败: HTTP {}", initResp.code());
                return null;
            }
            JsonNode initNode = objectMapper.readTree(initBody);
            String uploadId = initNode.path("upload_id").asText("");
            if (uploadId.isBlank()) {
                uploadId = initNode.path("uploadId").asText("");
            }
            if (uploadId.isBlank()) {
                log.warn("UPOS 创建上传会话未返回 uploadId");
                return null;
            }
            log.info("UPOS uploadId={}", uploadId);
            return uploadId;
        } catch (Exception e) {
            log.error("UPOS 创建上传会话异常", e);
            return null;
        }
    }

    /**
     * Step 3: 分片上传全部数据，失败按 chunk_retry 重试。
     *
     * @return 成功返回分片总数，失败返回 -1
     */
    private int uploadUposChunks(String uploadUrl, String auth, String uploadId,
                                 byte[] videoBytes, int chunkSize, int maxRetry) {
        int total = videoBytes.length;
        int chunks = (int) Math.ceil((double) total / chunkSize);
        for (int chunk = 0; chunk < chunks; chunk++) {
            int start = chunk * chunkSize;
            int end = Math.min(start + chunkSize, total);
            int size = end - start;
            String partUrl = uploadUrl
                    + "?partNumber=" + (chunk + 1)
                    + "&uploadId=" + uploadId
                    + "&chunk=" + chunk
                    + "&chunks=" + chunks
                    + "&size=" + size
                    + "&start=" + start
                    + "&end=" + end
                    + "&total=" + total;
            byte[] slice = Arrays.copyOfRange(videoBytes, start, end);
            boolean success = false;
            for (int attempt = 1; attempt <= maxRetry && !success; attempt++) {
                success = putUposChunk(partUrl, auth, slice, chunk + 1, chunks);
                if (!success) {
                    log.warn("UPOS 分片 {}/{} 第 {} 次上传失败", chunk + 1, chunks, attempt);
                }
            }
            if (!success) {
                log.warn("UPOS 分片 {}/{} 重试 {} 次后仍失败，上传终止", chunk + 1, chunks, maxRetry);
                return -1;
            }
        }
        return chunks;
    }

    /**
     * PUT 上传单个分片。
     */
    private boolean putUposChunk(String partUrl, String auth, byte[] slice,
                                 int partNumber, int chunks) {
        RequestBody partBody = RequestBody.create(slice,
                MediaType.parse("application/octet-stream"));
        Request.Builder partReqBuilder = new Request.Builder()
                .url(partUrl)
                .header("Content-Type", "application/octet-stream")
                .put(partBody);
        if (auth != null && !auth.isBlank()) {
            partReqBuilder.header("X-Upos-Auth", auth);
        }
        try (Response partResp = uploadHttpClient.newCall(partReqBuilder.build()).execute()) {
            String partRespBody = partResp.body() != null ? partResp.body().string() : "";
            log.info("UPOS 分片 {}/{} 上传响应: HTTP {}, body={}",
                    partNumber, chunks, partResp.code(), partRespBody);
            return partResp.code() == 200;
        } catch (Exception e) {
            log.warn("UPOS 分片 {}/{} 上传异常: {}", partNumber, chunks, e.getMessage());
            return false;
        }
    }

    /**
     * Step 4: 合片确认。请求体必须携带各分片 parts 列表，否则 UPOS 无法合并。
     */
    private boolean completeUposUpload(String uploadUrl, String auth, String uploadId,
                                       long bizId, String fileName, int chunks, String cookie) {
        try {
            String completeUrl = uploadUrl
                    + "?output=json"
                    + "&name=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    + "&profile=" + URLEncoder.encode(UPOS_PROFILE, StandardCharsets.UTF_8)
                    + "&uploadId=" + uploadId
                    + "&biz_id=" + bizId;
            List<Map<String, Object>> parts = new ArrayList<>();
            for (int i = 1; i <= chunks; i++) {
                Map<String, Object> part = new HashMap<>();
                part.put("partNumber", i);
                part.put("eTag", "etag");
                parts.add(part);
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("parts", parts);
            String jsonBody = objectMapper.writeValueAsString(payload);
            log.info("UPOS 合片确认: url={}, body={}", completeUrl, jsonBody);

            RequestBody completeBody = RequestBody.create(jsonBody,
                    MediaType.parse("application/json; charset=utf-8"));
            Request.Builder completeReqBuilder = new Request.Builder()
                    .url(completeUrl)
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://member.bilibili.com/")
                    .post(completeBody);
            if (auth != null && !auth.isBlank()) {
                completeReqBuilder.header("X-Upos-Auth", auth);
            }
            try (Response completeResp = uploadHttpClient.newCall(completeReqBuilder.build()).execute()) {
                String respBody = completeResp.body() != null ? completeResp.body().string() : "";
                log.info("UPOS 合片响应: HTTP {}, body={}", completeResp.code(), respBody);
                if (!completeResp.isSuccessful()) {
                    log.warn("UPOS 合片失败: HTTP {}", completeResp.code());
                    return false;
                }
                JsonNode node = objectMapper.readTree(respBody);
                if (node.path("OK").asInt(0) != 1) {
                    log.warn("UPOS 合片返回异常: {}", respBody);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            log.error("UPOS 合片异常", e);
            return false;
        }
    }

    /**
     * 从 upos_uri 中提取文件名。
     * 格式: "upos://ugcfxever/n260730sa2zg7ez5rq4jzi3hswekf8tq.mp4"
     */
    private String extractFileNameFromUposUri(String uposUri) {
        if (uposUri == null || uposUri.isBlank()) return "unknown";
        int lastSlash = uposUri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < uposUri.length() - 1) {
            return uposUri.substring(lastSlash + 1);
        }
        return uposUri;
    }

    /**
     * Step 5: 将已上传的视频保存到创作中心草稿箱。
     * <p>
     * 接口: POST /x/vupre/web/draft/add（创作中心草稿接口，保存后不进入审核发布流程）。
     * 注意: videos[].filename 为 UPOS 文件名（不含扩展名），cid 即预上传返回的 biz_id。
     *
     * @param actualFileName CDN 上传后的文件名（含扩展名）
     * @param bizId          预上传返回的 biz_id
     * @param originalName   用户原始文件名（用作默认标题）
     * @param cookie         Cookie 串
     * @return 保存成功返回 true
     */
    private Long saveVideoDraft(String actualFileName, long bizId,
                                String originalName, String cookie,
                                VideoMetadata metadata, String coverUrl) {
        try {
            String biliJct = extractCookieField("bili_jct");
            if (biliJct.isBlank()) {
                log.warn("bili_jct 为空，无法保存草稿");
                return null;
            }

            // 标题：优先元数据，否则去掉原始文件名的扩展名
            String title = metadata != null && !metadata.title().isBlank()
                    ? metadata.title()
                    : stripExtension(originalName != null ? originalName : actualFileName);
            String desc = metadata != null ? metadata.description() : "";
            String tagStr = metadata != null && !metadata.tags().isEmpty()
                    ? String.join(",", metadata.tags()) : "日常";
            int tid = metadata != null ? metadata.tid() : 171;
            String cover = coverUrl != null ? coverUrl : "";

            // 草稿接口要求 filename 不含扩展名
            String fileNameNoExt = stripExtension(actualFileName);

            Map<String, Object> videoItem = new HashMap<>();
            videoItem.put("filename", fileNameNoExt);
            videoItem.put("title", title);
            videoItem.put("desc", desc);
            videoItem.put("cid", bizId);
            videoItem.put("is_4k", false);
            videoItem.put("is_8k", false);
            videoItem.put("is_hdr", false);

            Map<String, Object> subtitleConf = new HashMap<>();
            subtitleConf.put("open", 0);
            subtitleConf.put("lan", "");

            Map<String, Object> watermark = new HashMap<>();
            watermark.put("state", 1);

            Map<String, Object> body = new HashMap<>();
            body.put("videos", List.of(videoItem));
            body.put("cover", cover);
            body.put("cover43", "");
            body.put("ai_cover", 0);
            body.put("is_ab_cover", 0);
            body.put("ab_cover_info", null);
            body.put("title", title);
            body.put("copyright", 1);
            body.put("tid", tid);
            body.put("tag", tagStr);
            body.put("desc", desc);
            body.put("recreate", -1);
            body.put("dynamic", "");
            body.put("is_only_self", 0);
            body.put("space_hidden", 2);
            body.put("watermark", watermark);
            body.put("no_reprint", 1);
            body.put("subtitle", subtitleConf);
            body.put("dolby", 0);
            body.put("lossless_music", 0);
            body.put("up_selection_reply", false);
            body.put("up_close_reply", false);
            body.put("up_close_danmu", false);
            body.put("csrf", biliJct);

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("草稿保存请求体: {}", jsonBody);

            String draftUrl = "https://member.bilibili.com/x/vupre/web/draft/add?t="
                    + System.currentTimeMillis() + "&csrf=" + biliJct;
            RequestBody reqBody = RequestBody.create(
                    jsonBody, MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(draftUrl)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://member.bilibili.com/platform/upload/video/frame")
                    .post(reqBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                log.info("草稿保存响应: HTTP {}, body={}", response.code(), respBody);
                if (!response.isSuccessful()) {
                    log.warn("草稿保存请求失败: HTTP {}", response.code());
                    return null;
                }
                JsonNode root = objectMapper.readTree(respBody);
                int code = root.path("code").asInt(-1);
                if (code == 0) {
                    JsonNode data = root.path("data");
                    long aid = data.path("aid").asLong(0);
                    if (aid <= 0) {
                        aid = data.path("draft_id").asLong(0);
                    }
                    if (aid <= 0) {
                        // draft/add 响应不携带 data（{"code":0,"message":"0","ttl":1}），
                        // 需查询草稿列表获取最新草稿的 draft_id
                        log.info("草稿保存响应未携带 aid，查询草稿列表获取 draft_id...");
                        Long fetched = fetchLatestDraftId(cookie);
                        aid = fetched != null ? fetched : 0;
                    }
                    log.info("草稿保存成功: fileName={}, aid={}", actualFileName, aid);
                    return aid > 0 ? aid : null;
                }
                log.warn("草稿保存失败: code={}, message={}",
                        code, root.path("message").asText(""));
                return null;
            }
        } catch (Exception e) {
            log.error("草稿保存异常", e);
            return null;
        }
    }

    /**
     * 通过魔数（magic bytes）检测图片 MIME 类型。
     */
    private String detectImageMime(byte[] bytes) {
        if (bytes != null && bytes.length >= 4) {
            // JPEG: FF D8
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            // PNG: 89 50 4E 47
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "image/png";
            }
            // GIF: 47 49 46
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return "image/gif";
            }
            // WebP: RIFF....WEBP
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
                return "image/webp";
            }
        }
        return "image/jpeg";
    }

    /**
     * 查询创作中心草稿列表，获取最新草稿的 draft_id。
     * <p>
     * 接口: GET /x/vupre/web/draft/list（草稿箱列表，按更新时间倒序，第一条即刚保存的草稿）。
     */
    private Long fetchLatestDraftId(String cookie) {
        try {
            Request request = new Request.Builder()
                    .url("https://member.bilibili.com/x/vupre/web/draft/list?pn=1&ps=1")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://member.bilibili.com/platform/home")
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(request).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                log.info("草稿列表响应: HTTP {}, body={}", resp.code(),
                        body.length() > 600 ? body.substring(0, 600) + "..." : body);
                if (!resp.isSuccessful()) {
                    return null;
                }
                JsonNode root = objectMapper.readTree(body);
                if (root.path("code").asInt(-1) != 0) {
                    log.warn("草稿列表返回异常: {}", body);
                    return null;
                }
                // data 直接是数组: [{"id":3705561,"mid":...,"title":...}, ...]
                // 草稿 ID 字段名为 id（即编辑页 URL 中的 draftId）
                JsonNode data = root.path("data");
                JsonNode first = null;
                if (data.isArray() && !data.isEmpty()) {
                    first = data.get(0);
                } else {
                    // 兼容 data 是对象包列表的情况
                    for (String listKey : new String[]{"list", "drafts", "draft_list", "items"}) {
                        JsonNode list = data.path(listKey);
                        if (list.isArray() && !list.isEmpty()) {
                            first = list.get(0);
                            break;
                        }
                    }
                }
                if (first != null) {
                    long id = first.path("id").asLong(0);
                    if (id <= 0) {
                        id = first.path("draft_id").asLong(0);
                    }
                    if (id > 0) {
                        return id;
                    }
                }
                // 正则兜底：匹配 "id": 或 "draft_id":
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"(?:draft_)?id\"\\s*:\\s*(\\d+)")
                        .matcher(body);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
                log.warn("草稿列表中未找到草稿 ID");
                return null;
            }
        } catch (Exception e) {
            log.warn("查询草稿列表异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 去掉文件名的扩展名（如 "n260730xxx.mp4" → "n260730xxx"）。
     */
    private String stripExtension(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int dotIdx = name.lastIndexOf('.');
        return dotIdx > 0 ? name.substring(0, dotIdx) : name;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建带 WBI 签名的 URL。
     */
    private String buildWbiUrl(String path, Map<String, String> params) {
        Map<String, String> signed = BilibiliSignUtil.sign(params);
        HttpUrl.Builder builder = HttpUrl.parse(API_BASE + path).newBuilder();
        for (Map.Entry<String, String> entry : signed.entrySet()) {
            builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
        return builder.build().toString();
    }

    /**
     * 构建普通 URL（不带 WBI 签名）。
     */
    private String buildUrl(String path, Map<String, String> params) {
        HttpUrl.Builder builder = HttpUrl.parse(API_BASE + path).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
        return builder.build().toString();
    }

    /**
     * 执行 GET 请求（携带 Cookie）。
     */
    private JsonNode executeGetWithCookie(String url) {
        return executeGetInternal(url, true, BILIBILI_REFERER);
    }

    private JsonNode executeGetInternal(String url, boolean withCookie, String referer) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer != null ? referer : BILIBILI_REFERER)
                .get();

        if (withCookie) {
            String cookie = cookieStore.load();
            if (cookie != null && !cookie.isBlank()) {
                requestBuilder.header("Cookie", cookie);
            } else {
                log.warn("Cookie 未设置，请求可能被风控拦截或返回不完整数据");
            }
        }

        Request request = requestBuilder.build();
        log.debug("HTTP GET: {}", url);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("B站 API 返回异常状态码: url={}, code={}", url, response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonNode root = objectMapper.readTree(body);

            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String message = root.path("message").asText("");
                // -404「啥都木有」：评论/动态等内容为空时的正常返回，非系统错误，降级为 DEBUG
                if (code == -404) {
                    log.debug("B站 API 返回空内容: url={}, code={}, message={}", url, code, message);
                } else {
                    log.error("B站 API 返回错误: url={}, code={}, message={}", url, code, message);
                }
                return null;
            }

            return root.path("data");
        } catch (Exception e) {
            log.error("B站 API 请求失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 执行 GET 请求并返回原始 JsonNode（保留 code/message 字段，不剥离 data）。
     * <p>
     * 与 {@link #executeGetInternal} 不同：code != 0 时不会直接丢弃结果，
     * 而是返回完整 JSON，供调用方自行判断业务码（如 Cookie 有效性检查需要区分 -101）。
     *
     * @param url 请求地址（需已拼接完整参数）
     * @return 完整的 JSON 响应体，网络异常或无 Cookie 时返回 null
     */
    private JsonNode executeGetRawWithCookie(String url) {
        String cookie = cookieStore.load();
        if (cookie == null || cookie.isBlank()) {
            log.warn("executeGetRawWithCookie: Cookie 为空");
            return null;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", BILIBILI_REFERER)
                .header("Cookie", cookie)
                .get()
                .build();
        log.debug("HTTP GET (raw): {}", url);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("原始请求 HTTP 状态异常: url={}, code={}", url, response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.error("原始请求失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 提取动态文本内容（截取前 100 字）。
     */
    private String extractDynamicContent(JsonNode item) {
        JsonNode moduleDynamic = item.path("modules").path("module_dynamic");
        if (moduleDynamic.isMissingNode()) {
            return "";
        }

        // 优先取 desc.text（纯文字/图文动态的正文）
        JsonNode desc = moduleDynamic.path("desc");
        if (!desc.isMissingNode()) {
            String text = desc.path("text").asText("");
            if (!text.isBlank()) {
                return truncate(text, 100);
            }
        }

        // 尝试 major.opus.summary.text（带看板的动态）
        JsonNode major = moduleDynamic.path("major");
        if (!major.isMissingNode()) {
            String majorType = major.path("type").asText("");
            switch (majorType) {
                case "MAJOR_TYPE_OPUS" -> {
                    String opusText = major.path("opus").path("summary").path("text").asText("");
                    if (!opusText.isBlank()) {
                        return truncate(opusText, 100);
                    }
                }
                case "MAJOR_TYPE_DRAW" -> {
                    JsonNode items = major.path("draw").path("items");
                    if (items.isArray() && !items.isEmpty()) {
                        String drawDesc = items.get(0).path("description").asText("");
                        if (!drawDesc.isBlank()) {
                            return truncate("[图文动态] " + drawDesc, 100);
                        }
                    }
                    return "[图文动态，共" + items.size() + "张图片]";
                }
                case "MAJOR_TYPE_ARCHIVE" -> {
                    String archiveTitle = major.path("archive").path("title").asText("");
                    return truncate("[投稿视频] " + archiveTitle, 100);
                }
                case "MAJOR_TYPE_ARTICLE" -> {
                    String articleTitle = major.path("article").path("title").asText("");
                    return truncate("[专栏] " + articleTitle, 100);
                }
                default -> {
                    // 其他类型，尝试取通用字段
                }
            }
        }

        return "";
    }

    /**
     * 将 B站动态类型映射为中文描述。
     */
    private String mapDynamicType(String type) {
        return switch (type) {
            case "DYNAMIC_TYPE_WORD" -> "纯文字动态";
            case "DYNAMIC_TYPE_DRAW" -> "图文动态";
            case "DYNAMIC_TYPE_AV" -> "视频动态";
            case "DYNAMIC_TYPE_FORWARD" -> "转发动态";
            case "DYNAMIC_TYPE_ARTICLE" -> "专栏动态";
            case "DYNAMIC_TYPE_LIVE_RCMD" -> "直播动态";
            default -> "动态";
        };
    }

    /**
     * 截取文本，超过 maxLen 时追加 "..."。
     */
    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen) + "...";
    }

    /**
     * 从 Cookie 字符串中提取指定字段的值。
     *
     * @param fieldName Cookie 字段名，如 "bili_jct"、"DedeUserID"
     * @return 字段值，不存在时返回空字符串
     */
    private String extractCookieField(String fieldName) {
        String cookie = cookieStore.load();
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(fieldName + "=")) {
                return trimmed.substring(fieldName.length() + 1);
            }
        }
        return "";
    }

    /**
     * 解析 UID：优先使用配置项 {@code bilibili.uid}，
     * 为空则从 Cookie 中提取 {@code DedeUserID}。
     */
    private String resolveUid() {
        // 1. 配置优先
        if (configuredUid != null && !configuredUid.isBlank()) {
            return configuredUid.trim();
        }
        // 2. 从 Cookie 中提取 DedeUserID
        String cookie = cookieStore.load();
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("DedeUserID=")) {
                    String extracted = trimmed.substring("DedeUserID=".length()).trim();
                    if (!extracted.isBlank()) {
                        log.info("从 Cookie 中提取到 DedeUserID: {}", extracted);
                        return extracted;
                    }
                }
            }
        }
        log.warn("无法解析 B站 UID：bilibili.uid 未配置且 Cookie 中未找到 DedeUserID");
        return "";
    }

    // ==================== 热门视频拉取 ====================

    /**
     * 拉取 B 站全站排行榜热门视频。
     * <p>
     * 调用 ranking/v2 接口（需要 WBI 签名），通过分页拉取两页共约 200 条，
     * 按 bvid 去重后取前 {@code count} 条返回。
     */
    @Override
    public List<BilibiliTrendingVideo> getTrendingVideos(int count) {
        LinkedHashSet<BilibiliTrendingVideo> allVideos = new LinkedHashSet<>();
        try {
            for (int page = 1; page <= 2; page++) {
                Map<String, String> params = new HashMap<>();
                params.put("rid", "0");
                params.put("type", "all");
                params.put("page", String.valueOf(page));
                String url = buildWbiUrl("/x/web-interface/ranking/v2", params);
                JsonNode dataNode = executeGetWithCookie(url);
                if (dataNode == null) {
                    log.warn("获取热门视频第{}页失败", page);
                    continue;
                }
                JsonNode listNode = dataNode.path("list");
                if (!listNode.isArray()) {
                    log.warn("热门视频第{}页 list 字段为空或非数组", page);
                    continue;
                }
                for (JsonNode video : listNode) {
                    JsonNode stat = video.path("stat");
                    allVideos.add(new BilibiliTrendingVideo(
                            video.path("bvid").asText(""),
                            video.path("title").asText(""),
                            video.path("tname").asText(""),
                            video.path("tid").asInt(0),
                            stat.path("view").asInt(0),
                            stat.path("like").asInt(0),
                            stat.path("coin").asInt(0),
                            stat.path("favorite").asInt(0),
                            stat.path("share").asInt(0),
                            stat.path("reply").asInt(0),
                            video.path("owner").path("name").asText(""),
                            video.path("duration").asInt(0),
                            video.path("pubdate").asLong(0)
                    ));
                }
            }
        } catch (Exception e) {
            log.error("拉取热门视频异常", e);
        }
        List<BilibiliTrendingVideo> result = new ArrayList<>();
        for (BilibiliTrendingVideo v : allVideos) {
            result.add(v);
            if (result.size() >= count) break;
        }
        log.info("成功拉取 {} 条热门视频", result.size());
        return result;
    }

    // ==================== 评论拉取 ====================

    @Override
    public List<BilibiliComment> getVideoComments(String aid, int count) {
        List<BilibiliComment> result = new ArrayList<>();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("type", "1");
            params.put("oid", aid);
            params.put("pn", "1");
            params.put("ps", String.valueOf(count));
            params.put("sort", "1");
            String url = buildWbiUrl("/x/v2/reply/main", params);
            JsonNode dataNode = executeGetInternal(url, true, "https://www.bilibili.com");
            if (dataNode == null) {
                return result;
            }
            JsonNode replies = dataNode.path("replies");
            if (replies.isArray()) {
                for (JsonNode r : replies) {
                    result.add(parseComment(r, 1, aid));
                }
            }
        } catch (Exception e) {
            log.error("拉取视频评论异常: aid={}", aid, e);
        }
        log.debug("拉取视频评论: aid={}, 共{}条", aid, result.size());
        return result;
    }

    @Override
    public List<BilibiliComment> getDynamicComments(String dynamicId, int count) {
        List<BilibiliComment> result = new ArrayList<>();
        try {
            // Step 1: 调用动态详情 API 获取 comment_id_str 和原始类型
            String detailUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=" + dynamicId;
            JsonNode detailData = executeGetWithCookie(detailUrl); // ← 内部已带 Referer
            if (detailData == null) {
                log.warn("获取动态详情失败: dynamicId={}", dynamicId);
                return result;
            }
            JsonNode item = detailData.path("item");
            if (item.isMissingNode()) {
                log.warn("动态详情中缺少 item 字段: dynamicId={}", dynamicId);
                return result;
            }

            String rawType = item.path("type").asText("");
            String commentIdStr = item.at("/basic/comment_id_str").asText("");
            if (commentIdStr.isBlank()) {
                // 兜底：使用 dynamicId 作为 oid（FORWARD/WORD 等类型）
                commentIdStr = dynamicId;
            }

            // Step 2: 根据动态类型确定评论 type
            int commentType = getDynamicCommentType(rawType);
            String oid = commentIdStr;

            // Step 3: 拉取评论
            Map<String, String> commentParams = new HashMap<>();
            commentParams.put("type", String.valueOf(commentType));
            commentParams.put("oid", oid);
            commentParams.put("pn", "1");
            commentParams.put("ps", String.valueOf(count));
            commentParams.put("sort", "1");
            String commentUrl = buildWbiUrl("/x/v2/reply/main", commentParams);
            JsonNode commentData = executeGetInternal(commentUrl, true, "https://t.bilibili.com");
            if (commentData == null) {
                return result;
            }
            JsonNode replies = commentData.path("replies");
            if (replies.isArray()) {
                for (JsonNode r : replies) {
                    result.add(parseComment(r, commentType, oid));
                }
            }
        } catch (Exception e) {
            log.error("拉取动态评论异常: dynamicId={}", dynamicId, e);
        }
        log.debug("拉取动态评论: dynamicId={}, type=11/17, 共{}条", dynamicId, result.size());
        return result;
    }

    /**
     * 将 reply JSON 节点解析为 {@link BilibiliComment}。
     */
    private BilibiliComment parseComment(JsonNode r, int sourceType, String oid) {
        JsonNode member = r.path("member");
        JsonNode content = r.path("content");
        return new BilibiliComment(
                r.path("rpid").asLong(0),
                r.path("mid").asLong(0),
                member.path("uname").asText(""),
                member.path("avatar").asText(""),
                content.path("message").asText(""),
                r.path("like").asInt(0),
                r.path("rcount").asInt(0),
                r.path("ctime").asLong(0),
                null,   // sourceTitle → 外部设置
                null,   // sourceUrl   → 外部设置
                sourceType,
                oid
        );
    }

    /**
     * 根据 B站动态原始类型返回评论 API 的 type 参数。
     */
    private static int getDynamicCommentType(String dynamicType) {
        if ("DYNAMIC_TYPE_DRAW".equals(dynamicType)) {
            return 11;
        }
        // DYNAMIC_TYPE_FORWARD, DYNAMIC_TYPE_WORD, DYNAMIC_TYPE_AV 等
        return 17;
    }

    // ==================== 分页全量评论拉取 ====================

    @Override
    public List<BilibiliComment> getAllVideoComments(String aid, int maxCount) {
        return paginatedFetch(1, aid, maxCount);
    }

    @Override
    public List<BilibiliComment> getAllDynamicComments(String dynamicId, int maxCount) {
        // Step 1: 获取动态详情 → 确定 comment type 和 oid
        String detailUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=" + dynamicId;
        JsonNode detailData = executeGetWithCookie(detailUrl);
        if (detailData == null) {
            log.warn("获取动态详情失败，无法拉取全量评论: dynamicId={}", dynamicId);
            return Collections.emptyList();
        }
        JsonNode item = detailData.path("item");
        if (item.isMissingNode()) {
            log.warn("动态详情中缺少 item: dynamicId={}", dynamicId);
            return Collections.emptyList();
        }

        String rawType = item.path("type").asText("");
        String commentIdStr = item.at("/basic/comment_id_str").asText("");
        if (commentIdStr.isBlank()) {
            commentIdStr = dynamicId;
        }

        int commentType = getDynamicCommentType(rawType);
        return paginatedFetch(commentType, commentIdStr, maxCount);
    }

    /**
     * 通用分页评论拉取（每页 20 条，最多 maxCount 条）。
     */
    private List<BilibiliComment> paginatedFetch(int commentType, String oid, int maxCount) {
        List<BilibiliComment> result = new ArrayList<>();
        final int pageSize = 20;
        int totalFetched = 0;
        int page = 1;

        while (totalFetched < maxCount) {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("type", String.valueOf(commentType));
                params.put("oid", oid);
                params.put("pn", String.valueOf(page));
                params.put("ps", String.valueOf(pageSize));
                params.put("sort", "1");
                String url = buildWbiUrl("/x/v2/reply/main", params);
                JsonNode dataNode = executeGetInternal(url, true, "https://www.bilibili.com");
                if (dataNode == null) {
                    break;
                }
                JsonNode replies = dataNode.path("replies");
                if (!replies.isArray() || replies.size() == 0) {
                    break; // 没有更多评论
                }
                int remaining = maxCount - totalFetched;
                int toTake = Math.min(replies.size(), remaining);
                for (int i = 0; i < toTake; i++) {
                    result.add(parseComment(replies.get(i), commentType, oid));
                }
                totalFetched += toTake;
                if (replies.size() < pageSize) {
                    break; // 最后一页
                }
                page++;
            } catch (Exception e) {
                log.error("分页拉取评论异常: type={}, oid={}, page={}", commentType, oid, page, e);
                break;
            }
        }
        log.debug("分页拉取评论完成: type={}, oid={}, 共{}条", commentType, oid, result.size());
        return result;
    }

    // ==================== 删除评论 ====================

    @Override
    public boolean deleteComment(int type, String oid, long rpid) {
        try {
            String cookie = cookieStore.load();
            String biliJct = extractCookieField("bili_jct");
            if (biliJct.isBlank()) {
                log.warn("bili_jct 为空，无法删除评论");
                return false;
            }

            FormBody body = new FormBody.Builder()
                    .add("type", String.valueOf(type))
                    .add("oid", oid)
                    .add("rpid", String.valueOf(rpid))
                    .add("csrf", biliJct)
                    .add("csrf_token", biliJct)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.bilibili.com/x/v2/reply/del")
                    .header("Cookie", cookie)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bilibili.com")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("删除评论 HTTP {}: rpid={}, type={}, oid={}",
                            response.code(), rpid, type, oid);
                    return false;
                }
                JsonNode root = objectMapper.readTree(respBody);
                int code = root.path("code").asInt(-1);
                if (code == 0) {
                    log.info("删除评论成功: rpid={}, type={}, oid={}", rpid, type, oid);
                    return true;
                } else {
                    String msg = root.path("message").asText("");
                    log.warn("删除评论失败: rpid={}, code={}, msg={}", rpid, code, msg);
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("删除评论异常: rpid={}", rpid, e);
            return false;
        }
    }
}
