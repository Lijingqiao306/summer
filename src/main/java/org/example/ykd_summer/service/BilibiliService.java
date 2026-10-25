package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.BilibiliComment;
import org.example.ykd_summer.dto.BilibiliDynamicStat;
import org.example.ykd_summer.dto.BilibiliTrendingVideo;
import org.example.ykd_summer.dto.BilibiliUserInfo;
import org.example.ykd_summer.dto.BilibiliVideoStat;
import org.example.ykd_summer.dto.VideoMetadata;
import org.example.ykd_summer.dto.VideoUploadResult;

import java.util.List;

/**
 * B站数据采集服务。
 */
public interface BilibiliService {

    /**
     * 获取当前用户信息（含粉丝数）。
     *
     * @return 用户信息
     */
    BilibiliUserInfo getUserInfo();

    /**
     * 获取最新视频的统计数据（播放/点赞/评论/弹幕）。
     *
     * @return 视频统计，无视频时返回 null
     */
    BilibiliVideoStat getLatestVideoStat();

    /**
     * 获取最新动态的统计数据（点赞/评论/转发）。
     *
     * @return 动态统计，无动态时返回 null
     */
    BilibiliDynamicStat getLatestDynamic();

    /**
     * 检查当前 Cookie 是否有效。
     * <p>
     * 通过调用 /x/web-interface/nav 接口，根据 B站返回业务码判断：
     * code == 0 → 有效，code == -101 → 未登录/已过期，其他 → 异常。
     *
     * @return true 表示 Cookie 有效
     */
    boolean checkCookieValid();

    /**
     * 重新刷新 WBI 签名密钥。
     * <p>
     * 在 Cookie 更新后调用，确保 WBI 签名使用最新的密钥/会话。
     */
    void refreshWbiKeys();

    /**
     * 在已登录的B站账号上发布一条纯文本动态。
     * <p>
     * API: POST /dynamic_svr/v1/dynamic_svr/create
     *
     * @param content 动态文本内容
     * @return true 表示发布成功
     */
    boolean postDynamic(String content);

    /**
     * 在已登录的B站账号上发布一条带图片的图文动态。
     *
     * @param content     动态文本内容
     * @param imageBytes  图片字节数组
     * @param contentType 图片 MIME 类型（如 "image/jpeg"、"image/png"）
     * @param fileName    原始文件名（如 "photo.jpg"）
     * @return true 表示发布成功
     */
    boolean postDynamicWithImage(String content, byte[] imageBytes, String contentType, String fileName);

    /**
     * 上传视频到B站创作中心（预上传 + UPOS 分片上传 + 保存草稿）。
     * <p>
     * 上传成功后视频出现在B站创作中心草稿箱。
     *
     * @param videoBytes 视频字节数组
     * @param fileName   原始文件名（如 "test.mp4"）
     * @return 上传结果（含文件名、CID 和草稿 aid），失败返回 null
     */
    VideoUploadResult uploadVideo(byte[] videoBytes, String fileName);

    /**
     * 上传视频到B站创作中心，并携带元数据（标题、简介、标签、封面）保存草稿。
     *
     * @param videoBytes 视频字节数组
     * @param fileName   原始文件名
     * @param metadata   稿件元数据（标题、简介、标签、分区、tid）
     * @return 上传结果（含草稿 aid），失败返回 null
     */
    VideoUploadResult uploadVideo(byte[] videoBytes, String fileName, VideoMetadata metadata);

    /**
     * 上传视频到B站创作中心，并携带元数据和指定封面保存草稿。
     *
     * @param videoBytes 视频字节数组
     * @param fileName   原始文件名
     * @param metadata   稿件元数据
     * @param coverBytes 用户提供的封面图片字节；null 或空数组时使用默认封面
     * @return 上传结果（含草稿 aid），失败返回 null
     */
    VideoUploadResult uploadVideo(byte[] videoBytes, String fileName, VideoMetadata metadata, byte[] coverBytes);

    /**
     * 拉取 B 站全站排行榜热门视频。
     * <p>
     * 接口: ranking/v2?rid=0&type=all，需要 WBI 签名。
     * 通过分页拉取两页 (page=1 + page=2) 共约 200 条数据。
     *
     * @param count 期望拉取数量（实际可能因去重或数据不足而略少）
     * @return 热门视频列表，网络异常时返回空列表
     */
    List<BilibiliTrendingVideo> getTrendingVideos(int count);

    /**
     * 获取视频的最新评论列表。
     * <p>
     * 接口: /x/v2/reply/main?type=1&amp;oid={aid}&amp;sort=1，无需 Cookie。
     *
     * @param aid  视频 AV 号
     * @param count 拉取数量
     * @return 评论列表，按发布时间倒序，网络异常时返回空列表
     */
    List<BilibiliComment> getVideoComments(String aid, int count);

    /**
     * 获取动态的最新评论列表。
     * <p>
     * 先通过动态详情 API 获取 comment_id 和动态类型，
     * 再根据类型自动选择正确的评论 type (DRAW→11, FORWARD/WORD→17)。
     *
     * @param dynamicId 动态 ID
     * @param count     拉取数量
     * @return 评论列表，按发布时间倒序，网络异常时返回空列表
     */
    List<BilibiliComment> getDynamicComments(String dynamicId, int count);

    /**
     * 分页拉取视频全部评论（最多 maxCount 条，每页 20 条）。
     *
     * @param aid      视频 AV 号
     * @param maxCount 最多拉取条数（建议 ≤ 200）
     * @return 评论列表（按发布时间倒序），网络异常时返回空列表
     */
    List<BilibiliComment> getAllVideoComments(String aid, int maxCount);

    /**
     * 分页拉取动态全部评论（最多 maxCount 条，每页 20 条）。
     * <p>
     * 内部先调用动态详情 API 确定正确的 comment type 和 oid。
     *
     * @param dynamicId 动态 ID
     * @param maxCount  最多拉取条数（建议 ≤ 200）
     * @return 评论列表（按发布时间倒序），网络异常时返回空列表
     */
    List<BilibiliComment> getAllDynamicComments(String dynamicId, int maxCount);

    /**
     * 删除自己内容下的一条评论。
     * <p>
     * 接口: POST /x/v2/reply/del，需要 Cookie 和 CSRF token。
     *
     * @param type 评论类型（视频=1, 画图动态=11, 转发/文字动态=17）
     * @param oid  内容 ID（视频为 aid, 动态为 comment_id_str）
     * @param rpid 评论 ID
     * @return 是否删除成功
     */
    boolean deleteComment(int type, String oid, long rpid);
}
