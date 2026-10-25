package org.example.ykd_summer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 路由 Agent 输出的编排计划。
 * <p>
 * 包含一个或多个按依赖关系排列的步骤，空 steps 表示功能不支持。
 *
 * @param steps 步骤列表
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrchestrationPlan(
        @JsonProperty("steps") List<PlanStep> steps,
        @JsonProperty("new_topic") boolean newTopic) {

    /**
     * 计划是否为空（无可用步骤 = 不支持的功能）。
     */
    public boolean isEmpty() {
        return steps == null || steps.isEmpty();
    }
}
