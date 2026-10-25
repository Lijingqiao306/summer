package org.example.ykd_summer.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具类启用/禁用状态管理器。
 * <p>
 * 内存中以 {@code ConcurrentHashMap} 维护禁用工具类集合（线程安全），
 * 每次变更后自动持久化到 {@code data/tool_disable.json}，启动时加载恢复。
 * <p>
 * 仅控制作为 LLM function-calling 工具注入的类，支撑组件
 * （BilibiliStoreTools / CommentModerationTool / NotifiedCommentStore）
 * 不在此处管理，即 isDisabled 对它们始终返回 false。
 */
@Slf4j
@Component
public class ToolStateManager {

    private static final Path STATE_FILE = Path.of("data/tool_disable.json");
    private static final Gson GSON = new Gson();
    private static final Type SET_TYPE = new TypeToken<Set<String>>() {}.getType();

    /** 禁用的工具类简单类名集合（线程安全） */
    private final Set<String> disabledTools = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void load() {
        if (!Files.exists(STATE_FILE)) {
            log.info("ToolStateManager: 禁用状态文件不存在，所有工具类默认启用");
            return;
        }
        try {
            String json = Files.readString(STATE_FILE);
            Set<String> loaded = GSON.fromJson(json, SET_TYPE);
            if (loaded != null) {
                disabledTools.addAll(loaded);
                log.info("ToolStateManager: 已从 {} 恢复 {} 个禁用工具: {}", STATE_FILE,
                        loaded.size(), loaded);
            }
        } catch (Exception e) {
            log.warn("ToolStateManager: 无法读取禁用状态文件 {}: {}", STATE_FILE, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            String json = GSON.toJson(disabledTools);
            Files.writeString(STATE_FILE, json, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("ToolStateManager: 无法写入禁用状态文件 {}: {}", STATE_FILE, e.getMessage());
        }
    }

    /**
     * 禁用指定工具类。若已禁用则无操作。
     * @return true = 状态有变更
     */
    public boolean disable(String className) {
        boolean changed = disabledTools.add(className);
        if (changed) {
            log.info("ToolStateManager: 已禁用工具类 [{}]", className);
            save();
        }
        return changed;
    }

    /**
     * 启用指定工具类。若已启用则无操作。
     * @return true = 状态有变更
     */
    public boolean enable(String className) {
        boolean changed = disabledTools.remove(className);
        if (changed) {
            log.info("ToolStateManager: 已启用工具类 [{}]", className);
            save();
        }
        return changed;
    }

    /** 查询工具类是否被禁用 */
    public boolean isDisabled(String className) {
        return disabledTools.contains(className);
    }

    /**
     * 查询工具对象对应的类是否被禁用（处理 CGLIB 代理）。
     * 自动剥离 Spring CGLIB 代理后缀（如 $$SpringCGLIB$$0）。
     */
    public boolean isBeanDisabled(Object toolBean) {
        String className = AopUtils.getTargetClass(toolBean).getSimpleName();
        return isDisabled(className);
    }

    /** 获取被禁用的工具类数量 */
    public int disabledCount() {
        return disabledTools.size();
    }

    /** 获取所有禁用工具类名快照 */
    public Set<String> getDisabledTools() {
        return Collections.unmodifiableSet(disabledTools);
    }

    /** 切换工具类的启用/禁用状态。返回变更后是否被禁用。 */
    public boolean toggle(String className) {
        if (disabledTools.contains(className)) {
            enable(className);
            return false; // 已启用
        } else {
            disable(className);
            return true; // 已禁用
        }
    }
}
