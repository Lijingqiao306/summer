package org.example.ykd_summer.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.BilibiliComment;
import org.example.ykd_summer.dto.ModerationResult;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 评论自动审核工具：关键词词典匹配 + AI 批量语义审核。
 */
@Slf4j
@Component
public class CommentModerationTool {

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 直接删除：人身攻击、侮辱、广告 spam */
    private static final Set<String> HARSH_BLOCK = Set.of(
            "sb", "傻逼", "傻叉", "傻狗", "脑残", "废物", "狗东西", "畜牲", "畜生",
            "尼玛死了", "你妈死了", "你妈", "操你", "cnm", "nmsl",
            "加微信", "加V信", "加wx", "看片", "资源私", "加扣扣", "加QQ群",
            "赌博", "六合彩", "菠菜"
    );

    /** 每批送 AI 的最大条数 */
    private static final int AI_BATCH_SIZE = 20;

    private static final String AI_SYSTEM_PROMPT = """
            你是一个B站评论审核助手。请逐一审查以下评论，判断是否需要删除。

            删除标准（严格判定，仅删除明显违规内容）：
            1. 人身攻击：辱骂、侮辱、诅咒、歧视性言论
            2. 引战串子：故意挑拨对立、带节奏、钓鱼引战、恶意贬低
            3. 敏感违法：涉政敏感、色情低俗、违法信息、广告 spam

            注意：
            - 网络流行语/玩梗（如"逆天""抽象""绝绝子"）不构成违规
            - 正常的批评和建议不算引战
            - 不确定 → 保留（宁可漏过，不可误删）

            返回 JSON 数组，每个元素包含：
            - index: 评论编号（整数）
            - action: "delete" 或 "keep"
            - reason: 删除原因（仅 action=delete 时填写，≤ 10 字）

            只输出 JSON 数组，不要任何解释。""";

    public CommentModerationTool(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 对评论列表执行完整审核：先关键词粗筛 → 剩余送 AI。
     *
     * @param comments 待审核评论列表
     * @return 审核结果列表（与输入顺序不一致）
     */
    public List<ModerationResult> moderate(List<BilibiliComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return Collections.emptyList();
        }

        List<ModerationResult> results = new ArrayList<>();
        List<BilibiliComment> aiBatch = new ArrayList<>();

        // 阶段 1：关键词匹配
        for (BilibiliComment c : comments) {
            String content = c.content();
            if (content == null || content.isBlank()) {
                results.add(new ModerationResult(c.rpid(), ModerationResult.Action.KEEP, null, ModerationResult.Source.KEYWORD));
                continue;
            }
            String lower = content.toLowerCase();

            boolean blocked = false;
            for (String kw : HARSH_BLOCK) {
                if (lower.contains(kw)) {
                    results.add(new ModerationResult(c.rpid(), ModerationResult.Action.DELETE,
                            "关键词命中: " + kw, ModerationResult.Source.KEYWORD));
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                aiBatch.add(c);
            }
        }

        log.debug("关键词阶段: {} 条需 AI 审核, {} 条已命中", aiBatch.size(),
                comments.size() - aiBatch.size());

        // 阶段 2：AI 批量审核
        if (!aiBatch.isEmpty()) {
            List<ModerationResult> aiResults = moderateWithAi(aiBatch);
            results.addAll(aiResults);
        }

        return results;
    }

    // ---- AI 审核 ----

    private List<ModerationResult> moderateWithAi(List<BilibiliComment> comments) {
        List<ModerationResult> allResults = new ArrayList<>();

        for (int i = 0; i < comments.size(); i += AI_BATCH_SIZE) {
            int end = Math.min(i + AI_BATCH_SIZE, comments.size());
            List<BilibiliComment> batch = comments.subList(i, end);
            String response = callAiForBatch(batch, i);
            if (response == null) {
                // AI 不可用 → 全部保留
                for (BilibiliComment c : batch) {
                    allResults.add(new ModerationResult(c.rpid(), ModerationResult.Action.KEEP, null, ModerationResult.Source.AI));
                }
                continue;
            }
            List<ModerationResult> parsed = parseAiResponse(response, batch);
            allResults.addAll(parsed);
        }

        return allResults;
    }

    private String callAiForBatch(List<BilibiliComment> batch, int baseIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            BilibiliComment c = batch.get(i);
            String content = c.content();
            if (content != null && content.length() > 100) {
                content = content.substring(0, 100);
            }
            sb.append('[').append(baseIndex + i + 1).append("] ").append(content).append('\n');
        }

        try {
            var chatResponse = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(AI_SYSTEM_PROMPT),
                            new UserMessage(sb.toString())
                    ))
            );
            String response = chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : null;

            log.debug("AI 审核响应: {} 条评论, 响应长度: {}", batch.size(),
                    response != null ? response.length() : 0);
            return response;
        } catch (Exception e) {
            log.warn("AI 审核调用失败（评论将跳过删除）: {}", e.getMessage());
            return null;
        }
    }

    private List<ModerationResult> parseAiResponse(String response, List<BilibiliComment> batch) {
        List<ModerationResult> results = new ArrayList<>();
        // 预填 KEEP
        for (BilibiliComment c : batch) {
            results.add(new ModerationResult(c.rpid(), ModerationResult.Action.KEEP, null, ModerationResult.Source.AI));
        }

        try {
            // 提取 JSON 数组
            String json = response.trim();
            int arrayStart = json.indexOf('[');
            int arrayEnd = json.lastIndexOf(']');
            if (arrayStart < 0 || arrayEnd <= arrayStart) {
                log.warn("AI 返回内容中未找到JSON数组: {}", json.substring(0, Math.min(200, json.length())));
                return results;
            }
            json = json.substring(arrayStart, arrayEnd + 1);

            List<Map<String, Object>> items = objectMapper.readValue(
                    json, new TypeReference<>() {});
            for (Map<String, Object> item : items) {
                int index = ((Number) item.get("index")).intValue() - 1; // 转为 0-based
                String action = (String) item.get("action");
                if (index >= 0 && index < batch.size() && "delete".equalsIgnoreCase(action)) {
                    BilibiliComment c = batch.get(index);
                    String reason = (String) item.getOrDefault("reason", "AI判定违规");
                    results.set(index, new ModerationResult(c.rpid(), ModerationResult.Action.DELETE,
                            "AI: " + reason, ModerationResult.Source.AI));
                }
            }
            log.debug("AI 审核解析结果: 共{}条, 删除{}条", batch.size(),
                    results.stream().filter(r -> r.action() == ModerationResult.Action.DELETE).count());
        } catch (Exception e) {
            log.warn("解析 AI 审核响应失败（评论将保留）: {}", e.getMessage());
        }
        return results;
    }
}
