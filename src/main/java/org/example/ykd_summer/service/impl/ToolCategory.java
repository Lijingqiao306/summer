package org.example.ykd_summer.service.impl;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具按业务领域的分类枚举，用于按需过滤 TEXT Agent 的工具注入。
 * <p>
 * 过滤粒度：Bean 级（一个 Bean 下的所有 @Tool 方法绑定到同一类别）。
 * 当前 6 个 Bean 的工具方法数：SYSTEM(3), MAP(4), LOCATION(2), PERSONAL(3), MAIL(1), SEARCH(1)。
 *
 * @see OrchestrationServiceImpl#filterToolsByTask
 */
enum ToolCategory {

    /** BotTools：天气查询、版本号、服务状态 */
    SYSTEM,
    /** AmapTools：地理编码、POI 搜索、POI 详情、路径规划 */
    MAP,
    /** LocationTools：设置/获取用户当前位置 */
    LOCATION,
    /** PersonalTools：保存/删除/列出个人信息 */
    PERSONAL,
    /** MailSendTools：发送 QQ 邮件 */
    MAIL,
    /** WebSearchTools：联网搜索 */
    SEARCH;

    // ==================== Bean 类名 → Category 映射 ====================

    private static final Map<String, ToolCategory> CLASS_NAME_MAP = Map.of(
            "BotTools",        SYSTEM,
            "AmapTools",       MAP,
            "LocationTools",   LOCATION,
            "PersonalTools",   PERSONAL,
            "MailSendTools",   MAIL,
            "WebSearchTools",  SEARCH
    );

    static ToolCategory fromClassName(String simpleName) {
        return CLASS_NAME_MAP.getOrDefault(simpleName, null);
    }

    // ==================== STANDARD 模式下的依赖连带 ====================

    /**
     * STANDARD 模式下各类别的附加依赖。
     * - MAP 搜索附近需要知道位置（LOCATION）和个人偏好（PERSONAL）
     * - SEARCH 可能涉及地理搜索（保守策略：同时带 MAP + LOCATION + PERSONAL）
     * - LOCATION 管理常关联个人信息
     * - SYSTEM / PERSONAL / MAIL 独立运行
     */
    private static final Map<ToolCategory, Set<ToolCategory>> STANDARD_DEPENDENCIES = Map.of(
            MAP,      Set.of(LOCATION, PERSONAL),
            SEARCH,   Set.of(MAP, LOCATION, PERSONAL),
            LOCATION, Set.of(PERSONAL)
    );

    /**
     * 对给定的类别集合，补齐 STANDARD 模式所需的连带依赖。
     * 返回新的不可变集合（不修改入参）。
     */
    static Set<ToolCategory> withStandardDependencies(Set<ToolCategory> base) {
        if (base.isEmpty()) return base;
        EnumSet<ToolCategory> expanded = EnumSet.copyOf(base);
        for (ToolCategory cat : base) {
            Set<ToolCategory> deps = STANDARD_DEPENDENCIES.get(cat);
            if (deps != null) {
                expanded.addAll(deps);
            }
        }
        return Collections.unmodifiableSet(expanded);
    }
}
