package org.example.ykd_summer.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * B站 WBI 签名工具。
 * <p>
 * 支持动态 mixKey：优先使用通过 {@link #refreshMixKey(String, String)}
 * 传入的实时密钥，未更新时回退到内置固定密钥。
 * <p>
 * WBI 签名算法：
 * <ol>
 *   <li>将参数按 key 字典序排序</li>
 *   <li>拼接为 key1=value1&amp;key2=value2... 格式</li>
 *   <li>末尾追加 signKey（mixKey 前 32 位）</li>
 *   <li>对整个字符串做 MD5，得到 w_rid</li>
 *   <li>请求时附带 w_rid 和 wts（当前秒级时间戳）</li>
 * </ol>
 */
public class BilibiliSignUtil {

    /**
     * 内置固定 mixKey（64 位），作为动态密钥不可用时的回退。
     */
    private static final String DEFAULT_MIX_KEY =
            "ea1db124af3c7062474693fa704f4ff8ab4a500c8e7ca0784bf980958cd4b431";

    /**
     * 动态获取的 mixKey，由 {@link #refreshMixKey(String, String)} 设置。
     */
    private static volatile String dynamicMixKey = null;

    private BilibiliSignUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 用实时获取的 img_key 和 sub_key 更新 WBI 签名密钥。
     *
     * @param imgKey 从 nav 接口 wbi_img.img_url 文件名中提取的 key
     * @param subKey 从 nav 接口 wbi_img.sub_url 文件名中提取的 key
     */
    public static void refreshMixKey(String imgKey, String subKey) {
        if (imgKey != null && subKey != null && !imgKey.isBlank() && !subKey.isBlank()) {
            dynamicMixKey = imgKey.trim() + subKey.trim();
        }
    }

    /**
     * 获取当前有效的 mixKey（动态 > 默认）。
     */
    private static String currentMixKey() {
        String key = dynamicMixKey;
        return (key != null && !key.isBlank()) ? key : DEFAULT_MIX_KEY;
    }

    /**
     * 对参数进行 WBI 签名，返回包含 wts 和 w_rid 的新参数 map。
     *
     * @param params 原始参数（不含 wts / w_rid）
     * @return 包含 wts 和 w_rid 的签名后参数
     */
    public static Map<String, String> sign(Map<String, String> params) {
        String wts = String.valueOf(System.currentTimeMillis() / 1000);
        String signKey = currentMixKey().substring(0, 32);

        // 将 wts 加入参数参与签名计算
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", wts);

        // 构建待签名字符串：key1=value1&key2=value2...
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }

        // 追加签名 key 并计算 MD5
        sb.append(signKey);
        String wRid = md5(sb.toString());

        // 返回带签名的参数
        Map<String, String> result = new LinkedHashMap<>(params);
        result.put("wts", wts);
        result.put("w_rid", wRid);
        return result;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            BigInteger bigInt = new BigInteger(1, digest);
            return String.format("%032x", bigInt);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
