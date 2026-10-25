package org.example.ykd_summer.dto;

import java.util.List;
import java.util.Map;

/**
 * B站视频稿件元数据。
 *
 * @param title         标题
 * @param description   简介
 * @param tags          标签列表
 * @param partitionMain 一级分区显示名（如 "生活"）
 * @param partitionSub  二级分区显示名（如 "日常"）
 * @param tid           分区 ID（草稿接口需要）
 */
public record VideoMetadata(String title, String description, List<String> tags,
                            String partitionMain, String partitionSub, int tid) {

    /** 默认分区：生活·日常（tid=171，biliup-rs 默认，已验证可投稿） */
    private static final int DEFAULT_TID = 171;
    private static final String DEFAULT_MAIN = "生活";
    private static final String DEFAULT_SUB = "日常";

    /**
     * 用户说法 → 分区映射表。value = {一级, 二级, tid}
     * 只收录 tid 已验证可靠的分区，未收录的一律回落到生活·日常。
     */
    private static final Map<String, String[]> PARTITION_TABLE = Map.of(
            "生活", new String[]{"生活", "日常", "171"},
            "日常", new String[]{"生活", "日常", "171"},
            "vlog", new String[]{"生活", "日常", "171"},
            "生活记录", new String[]{"生活", "日常", "171"}
    );

    /**
     * 从用户自然语言解析分区，未匹配时返回默认分区（生活·日常）。
     */
    public static int[] resolvePartition(String userText) {
        if (userText != null && !userText.isBlank()) {
            String lower = userText.toLowerCase();
            for (Map.Entry<String, String[]> entry : PARTITION_TABLE.entrySet()) {
                if (lower.contains(entry.getKey().toLowerCase())) {
                    return new int[]{Integer.parseInt(entry.getValue()[2])};
                }
            }
        }
        return new int[]{DEFAULT_TID};
    }

    public static String partitionMainOf(int tid) {
        return DEFAULT_MAIN;
    }

    public static String partitionSubOf(int tid) {
        return DEFAULT_SUB;
    }

    /** 构建默认元数据（标题用文件名去扩展名） */
    public static VideoMetadata defaults(String fileName) {
        String title = fileName;
        if (title != null) {
            int dot = title.lastIndexOf('.');
            if (dot > 0) {
                title = title.substring(0, dot);
            }
        }
        if (title == null || title.isBlank()) {
            title = "video";
        }
        return new VideoMetadata(title, "", List.of("日常"),
                DEFAULT_MAIN, DEFAULT_SUB, DEFAULT_TID);
    }

    /** 以本记录为基础，覆盖非空字段生成新实例 */
    public VideoMetadata merge(String newTitle, String newDesc, List<String> newTags) {
        return new VideoMetadata(
                newTitle != null && !newTitle.isBlank() ? newTitle : title,
                newDesc != null && !newDesc.isBlank() ? newDesc : description,
                newTags != null && !newTags.isEmpty() ? newTags : tags,
                partitionMain, partitionSub, tid);
    }
}
