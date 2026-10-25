package org.example.ykd_summer.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.service.BrowserService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 浏览器自动化工具（Spring AI Tool 入口薄层）。
 * <p>
 * 本类仅包含 {@code @Tool} 注解的方法，作为 Agent 调用的工具接口。
 * 所有浏览器状态管理、Playwright 交互、Chromium 生命周期委托给 {@link BrowserService}。
 * </p>
 */
@Slf4j
@Component
public class BrowserTools {

    private final BrowserService browserService;

    public BrowserTools(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Tool(description = "使用浏览器打开用户指定的网页。当用户要求打开某个网站或网页时使用。" +
            "参数 url 为完整的网页地址（需包含 https:// 或 http://）。成功返回'已完成'，失败返回降级提示。")
    public String openWebPage(
            @ToolParam(description = "要打开的网页完整地址，例如 https://www.bilibili.com")
            String url) {
        return browserService.openWebPage(url);
    }

    @Tool(description = "获取当前页面的结构化快照，为所有可交互元素分配唯一数字 ID（ref 编号）。" +
            "返回元素类型（a/button/input）、ref 编号、元素文本。" +
            "后续 clickPageElement 和 typeText 可直接使用 ref 编号（如 [15]）精确定位元素。" +
            "参数 maxElements 控制最大返回元素数，不传则默认 80。")
    public String getPageSnapshot(
            @ToolParam(description = "最大返回的可交互元素数量，不传则默认 80") Integer maxElements) {
        return browserService.getPageSnapshot(maxElements);
    }

    @Tool(description = "获取当前打开网页的完整文字内容和可点击链接列表（带 ref 编号，最多 100 个）。" +
            "每个链接带 ref 编号（如 [1] 链接文本），可直接用于 clickPageElement 精确定位。" +
            "通常在 getPageSnapshot 结果中找不到目标时调用，作为补充获取更多链接信息。")
    public String getPageContent() {
        return browserService.getPageContent();
    }

    @Tool(description = "在当前打开的网页中点击指定元素，实现页面跳转或交互。" +
            "target 支持两种格式：1) ref 编号如 [15]（推荐，精确定位 getPageSnapshot/getPageContent 返回的元素）；" +
            "2) 文字描述如链接文本、按钮文字（兜底方案，系统自动匹配）。")
    public String clickPageElement(
            @ToolParam(description = "要点击的目标。支持 ref 编号（如 [15]）或文字描述（如视频标题、链接文本、按钮文字等）")
            String target) {
        return browserService.clickPageElement(target);
    }

    @Tool(description = "在当前打开的网页的输入框（如搜索框、表单字段）中输入文字。" +
            "target 支持两种格式：1) ref 编号如 [3]（推荐，精确定位 getPageSnapshot 返回的输入框）；" +
            "2) 输入框的描述如占位符文字（placeholder）、标签文字（兜底方案）。" +
            "text 是要输入的内容。如果 text 末尾追加 \\n 换行符，工具会自动按 Enter 键触发提交（如 B站搜索框直接按回车即可搜索，无需点击搜索按钮）。")
    public String typeText(
            @ToolParam(description = "输入框的定位方式。支持 ref 编号（如 [3]）或文字描述（如占位符文字、标签文字）")
            String target,
            @ToolParam(description = "要输入的文字内容。如果想输入后立即回车提交，文本末尾追加 \\n（例如：'高能击杀秀\\n'）。")
            String text) {
        return browserService.typeText(target, text);
    }

    @Tool(description = "列出所有已打开的浏览器标签页。每个标签页有一个数字索引（从0开始），" +
            "当前活跃的标签页会标注 [当前]。配合 switchTab 使用可切换标签页。")
    public String listTabs() {
        return browserService.listTabs();
    }

    @Tool(description = "切换到指定索引的标签页。索引从 listTabs 获取。" +
            "切换后该标签页成为当前活跃页面，后续所有操作都作用于新标签页。")
    public String switchTab(
            @ToolParam(description = "要切换到的标签页索引（从 0 开始）") int index) {
        return browserService.switchTab(index);
    }
}
