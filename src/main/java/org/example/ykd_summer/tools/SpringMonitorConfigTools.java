package org.example.ykd_summer.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.SpringMonitorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring 监测配置工具集合，仅挂载到 SPRING Agent。
 * <p>
 * 提供监测范围修改/查看/重置三大配置能力。
 * 通过 router-system.txt 中的 SPRING 路由规则触发，不从 TEXT Agent 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringMonitorConfigTools {

    private final SpringMonitorService monitorService;

    @Tool(description = "【配置修改类工具】永久修改 Spring 项目监测范围（持久化配置修改，不是一次性查询）。\n"
            + "当用户明确表达修改监测范围的意图时使用，如：\n"
            + "「只关注XX」「不再关注XX」「修改监测范围」「设置关注列表」「换成监测XX」\n"
            + "「以后只看XX」「把监测范围改成XX」「监测范围改为XX」。\n"
            + "参数 repos 为完整的 GitHub 仓库全名列表（如 spring-projects/spring-ai）。")
    public String updateMonitorScope(
            @ToolParam(description = "GitHub 仓库全名列表，如 ['spring-projects/spring-ai', 'spring-projects/spring-boot']")
            List<String> repos) {
        log.info("updateMonitorScope: repos={}", repos);
        return monitorService.updateMonitorScope(repos);
    }

    @Tool(description = "【查询配置类工具】查看当前 Spring 项目监测范围（已配置的关注仓库列表）。\n"
            + "当用户询问「当前监测哪些仓库」「关注范围是什么」「监测范围有哪些」时使用。\n"
            + "此工具仅查询配置，不触发检测，不修改任何数据。")
    public String getMonitorScope() {
        log.info("getMonitorScope");
        return monitorService.getMonitorScope();
    }

    @Tool(description = "【配置重置类工具】重置 Spring 项目监测范围为 application.yml 中的默认配置（全部 7 个核心仓库）。\n"
            + "当用户说「恢复默认监测范围」「重置关注列表」「恢复默认配置」时使用。\n"
            + "执行后会丢弃所有手动修改，回退到系统初始配置。")
    public String resetMonitorScope() {
        log.info("resetMonitorScope");
        return monitorService.resetMonitorScope();
    }

    @Tool(description = "【配置修改类工具】设置 Spring 监测的维度（即决定定时任务执行哪些检查），持久化配置。\n"
            + "参数 dimensions 是字符串列表，可选值: \"releases\"（版本发布监测）, \"blogs\"（博客文章监测）。\n"
            + "适用场景举例：\n"
            + "- 用户说「不监测博客」「只看版本」「停止博客推送」→ dimensions=[\"releases\"]\n"
            + "- 用户说「恢复博客」「也看博客」「同时监测博客和版本」→ dimensions=[\"releases\", \"blogs\"]\n"
            + "- 用户说「只看博客不关注版本」→ dimensions=[\"blogs\"]\n"
            + "- 用户说「全都监测」→ dimensions=[\"releases\", \"blogs\"]")
    public String setMonitoringDimensions(
            @ToolParam(description = "要启用的监测维度列表，可选值: releases, blogs。如 ['releases'] 表示只监测版本，['releases','blogs'] 表示两者都监测")
            List<String> dimensions) {
        log.info("setMonitoringDimensions: dimensions={}", dimensions);
        return monitorService.setMonitoringDimensions(dimensions);
    }

    @Tool(description = "【查询配置类工具】查看当前完整监测配置（仓库范围、监测维度等所有可调参数）。\n"
            + "当用户询问「当前配置」「监测设置是什么」「博客监测开了吗」「当前监测维度」时使用。\n"
            + "此工具返回完整的可调参数列表，帮助用户了解当前监测状态。")
    public String getMonitoringConfig() {
        log.info("getMonitoringConfig");
        return monitorService.getMonitoringConfig();
    }
}
