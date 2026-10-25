package org.example.ykd_summer.dto;

import java.util.List;

/**
 * matchReply 返回值：文本回复 + 可选文件数据 + 语音标记。
 * <p>
 * 当生图流程产生了图片时，fileData 非 null，Runner 将用 sendImage 发送。
 * 当 FILE Agent 生成了 Word/PDF 等文档时，generatedFiles 非 null。
 * 当 isVoice 为 true 时，Runner 将调用 TTS 合成语音发送。
 *
 * @param textReply      AI 生成的文本回复（不会为 null）
 * @param fileData       生成的图片数据（使用 {@link ReceivedContent} 统一载体），无文件时为 null
 * @param isVoice        是否为语音回复（需要 TTS 合成），默认 false
 * @param generatedFiles 生成的文档列表（Word/PDF），无文档时为 null 或空列表
 */
public record MatchReplyResult(String textReply, ReceivedContent fileData, boolean isVoice,
                                List<ReceivedContent> generatedFiles, boolean shouldWaitImage) {

    public boolean hasFile() {
        return fileData != null;
    }

    public boolean hasGeneratedFiles() {
        return generatedFiles != null && !generatedFiles.isEmpty();
    }

    /** 兼容旧构造器（无 generatedFiles） */
    public MatchReplyResult(String textReply, ReceivedContent fileData, boolean isVoice) {
        this(textReply, fileData, isVoice, null, false);
    }

    /** 兼容旧构造器（有 generatedFiles） */
    public MatchReplyResult(String textReply, ReceivedContent fileData, boolean isVoice,
                            List<ReceivedContent> generatedFiles) {
        this(textReply, fileData, isVoice, generatedFiles, false);
    }
}
