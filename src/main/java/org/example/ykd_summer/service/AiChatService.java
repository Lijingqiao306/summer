package org.example.ykd_summer.service;

import org.example.ykd_summer.dto.MatchReplyResult;
import org.example.ykd_summer.dto.ReceivedContent;

import java.util.List;
import java.util.function.Consumer;

/**
 * AI 聊天服务接口
 * <p>
 * 采用「路由 + 分发」两级架构：先由路由 Agent 做意图分类，再分发到文本/图片子 Agent 执行。
 *
 * @see org.example.ykd_summer.runner.IlinkBotRunner
 */
public interface AiChatService {

    /**
     * 根据消息内容匹配回复内容，返回文本 + 可能的文件数据。
     *
     * @param userId      用户唯一标识
     * @param sessionId   会话标识（Actor 创建时生成，用于 ChatMemory 隔离，避免跨会话污染）
     * @param text        用户消息文本
     * @param attachments 消息中附带的文件列表（图片、音频等，使用 {@link ReceivedContent} 统一载体），可为 null 或空
     * @param onProgress  进度回调，透传给编排层；可为 null（不发送进度提示）
     * @return 回复结果（文本 + 可选文件数据）
     */
    MatchReplyResult matchReply(String userId, String sessionId, String text,
                                 List<ReceivedContent> attachments,
                                 Consumer<String> onProgress);

    /**
     * 清除指定用户的"上一轮对话概况"残留（Router 跨轮上下文）。
     * 用户发送 #new 重置会话时调用，避免下一轮 Router 注入已失效的上轮上下文。
     */
    void clearLastRound(String userId);
}
