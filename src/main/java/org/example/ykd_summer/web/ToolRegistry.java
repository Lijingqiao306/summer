package org.example.ykd_summer.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 工具类注册表。
 * <p>
 * 启动后扫描 {@code org.example.ykd_summer.tools} 包下所有含 {@link Tool} 注解方法的
 * Spring Bean，生成监控页面工具方块列表（含中文显示名和图标）。
 */
@Slf4j
@Component
public class ToolRegistry {

    public record ToolMeta(String className, String displayName, String icon) {
        /** 是否为支撑组件（不直接作为 LLM function-calling 工具，不可启用/禁用） */
        public boolean isSupportComponent() {
            return SUPPORT_COMPONENTS.contains(className);
        }
    }

    /** 已知工具类的显示元数据：类名 -> [显示名, 图标]（LinkedHashMap 保持展示顺序） */
    private static final Map<String, String[]> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("BotTools",                new String[]{"聊天机器人", "🤖"});
        KNOWN.put("PersonalTools",           new String[]{"个人档案",   "👤"});
        KNOWN.put("LocationTools",           new String[]{"位置服务",   "📍"});
        KNOWN.put("AmapTools",               new String[]{"高德地图",   "🗺️"});
        KNOWN.put("WebSearchTools",          new String[]{"联网搜索",   "🔍"});
        KNOWN.put("FileTools",               new String[]{"文件工具",   "📁"});
        KNOWN.put("ImageTools",              new String[]{"图像工具",   "🖼️"});
        KNOWN.put("BrowserTools",            new String[]{"浏览器",     "🌐"});
        KNOWN.put("MailSendTools",           new String[]{"邮件发送",   "📧"});
        KNOWN.put("BilibiliAgentTools",      new String[]{"B站助手",    "📺"});
        KNOWN.put("BilibiliStoreTools",      new String[]{"B站创作档案", "🗂️"});
        KNOWN.put("CommentModerationTool",   new String[]{"评论审核",   "🛡️"});
        KNOWN.put("NotifiedCommentStore",    new String[]{"评论记录",   "💾"});
        KNOWN.put("SpringMonitorTools",      new String[]{"Spring 监测", "🌱"});
        KNOWN.put("SpringMonitorConfigTools", new String[]{"监测配置",  "⚙️"});
    }

    /** 支撑组件类名集合（不作为 LLM Tool，不可启用/禁用，仅供展示） */
    private static final Set<String> SUPPORT_COMPONENTS = Set.of(
            "BilibiliStoreTools", "CommentModerationTool", "NotifiedCommentStore"
    );

    private final ApplicationContext applicationContext;

    /** 扫描结果（启动后不再变化，volatile 保证可见性） */
    private volatile List<ToolMeta> tools = List.of();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scanTools() {
        List<ToolMeta> found = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                // 工具类可能被 AOP 代理，取目标类
                Class<?> cls = AopUtils.getTargetClass(bean);
                // 收录含 @Tool 方法的工具类；已显式注册到 KNOWN 的类（如评论审核、
                // 创作档案等无 @Tool 的支撑组件）也一并展示在监控页
                if (cls.getPackageName().startsWith("org.example.ykd_summer.tools")
                        && (hasToolMethod(cls) || KNOWN.containsKey(cls.getSimpleName()))) {
                    String simple = cls.getSimpleName();
                    String[] meta = KNOWN.get(simple);
                    found.add(meta != null
                            ? new ToolMeta(simple, meta[0], meta[1])
                            : new ToolMeta(simple, simple, "🔧"));
                }
            } catch (Exception ignored) {
                // 跳过无法正常实例化的 Bean
            }
        }
        // 按 KNOWN 中定义的顺序排序，未知工具排最后
        List<String> order = new ArrayList<>(KNOWN.keySet());
        found.sort(Comparator.comparingInt(t -> {
            int i = order.indexOf(t.className());
            return i >= 0 ? i : order.size();
        }));
        tools = List.copyOf(found);
        log.info("ToolRegistry 扫描到 {} 个工具类: {}", Optional.of(tools.size()),
                tools.stream().map(ToolMeta::className).toList());
    }

    private static boolean hasToolMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                return true;
            }
        }
        return false;
    }

    public List<ToolMeta> listTools() {
        return tools;
    }
}
