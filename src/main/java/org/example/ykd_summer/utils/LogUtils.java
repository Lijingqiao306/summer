package org.example.ykd_summer.utils;

/**
 * 日志工具类，提供日志打印时的文本截断能力，避免长文本撑爆控制台。
 */
public final class LogUtils {

    private LogUtils() {
    }

    /**
     * 将字符串截断到指定长度，超出部分用 "…" 替代。
     *
     * @param s      原始字符串
     * @param maxLen 最大允许长度
     * @return 截断后的字符串
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    /**
     * 为日志输出截断 URL，支持 Data URL 特殊处理。
     * <ul>
     *   <li>Data URL → 只打印类型前缀 + 总长度</li>
     *   <li>普通 HTTP URL → 超过 120 字符则截断</li>
     * </ul>
     *
     * @param url 原始 URL
     * @return 适合日志打印的 URL 字符串
     */
    public static String truncateUrlForLog(String url) {
        if (url == null) return "null";
        if (url.startsWith("data:")) {
            int colon = url.indexOf(';');
            String prefix = colon > 0 ? url.substring(0, colon) : "data:";
            return prefix + ";base64,... (总长度 " + url.length() + " 字符)";
        }
        if (url.length() > 120) {
            return url.substring(0, 120) + "…";
        }
        return url;
    }
}
