package org.example.ykd_summer.dto;

/**
 * B站视频上传结果。
 *
 * @param fileName 上传后的文件名（如 "n240730sa2zg7ez5rq4jzi3hswekf8tq.mp4"）
 * @param cid      视频 CID
 * @param bizId    业务 ID（来自 preupload 响应）
 * @param draftAid 草稿 ID（aid，来自草稿保存接口，用于 Playwright 打开发布页）
 */
public record VideoUploadResult(String fileName, String cid, long bizId, Long draftAid) {

    public boolean isSuccess() {
        return fileName != null && !fileName.isBlank() && cid != null && !cid.isBlank();
    }
}
