package org.example.ykd_summer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 编排计划中的单个步骤。
 * <p>
 * 由路由 Agent（LLM）输出，描述一个子任务及其依赖关系。
 *
 * @param index      步骤序号，从 0 开始，唯一标识
 * @param agent      目标子 Agent 名称：TEXT / IMAGE / FILE
 * @param task       子任务描述文本（将作为子 Agent 的 user prompt）
 * @param inputHint  上下文提示，如城市名、风格偏好等
 * @param dependsOn  依赖的前置步骤 index 列表，空列表表示无依赖
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanStep(
        int index,
        String agent,
        String task,
        @JsonProperty("input_hint") String inputHint,
        @JsonProperty("depends_on") List<Integer> dependsOn
) {
}
