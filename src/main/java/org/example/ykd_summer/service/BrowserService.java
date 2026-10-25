package org.example.ykd_summer.service;

/**
 * 浏览器自动化引擎服务，封装 Playwright 网页能力（状态管理、页面交互、Chromium 生命周期）。
 * <p>
 * 作为 BrowserTools（Spring AI Tool 入口）的底层支撑，BrowserTools 的每个
 * {@code @Tool} 方法直接委托到本接口方法。
 * </p>
 *
 * @see org.example.ykd_summer.service.impl.BrowserServiceImpl
 * @see org.example.ykd_summer.tools.BrowserTools
 */
public interface BrowserService {

    /**
     * 使用浏览器打开指定网页。
     *
     * @param url 要打开的网页完整地址（需包含 https:// 或 http://）
     * @return 操作结果描述
     */
    String openWebPage(String url);

    /**
     * 获取当前页面的结构化快照，为所有可交互元素分配唯一数字 ID（ref 编号）。
     * <p>
     * 返回元素类型（a/button/input）、ref 编号、元素文本。
     * 后续 clickPageElement 和 typeText 可直接使用 ref 编号（如 [15]）精确定位元素。
     * </p>
     *
     * @param maxElements 最大返回的可交互元素数量，不传则默认 80
     * @return 页面元素快照
     */
    String getPageSnapshot(Integer maxElements);

    /**
     * 获取当前打开网页的完整文字内容和可点击链接列表（带 ref 编号）。
     *
     * @return 页面内容与链接列表
     */
    String getPageContent();

    /**
     * 在当前打开的网页中点击指定元素，实现页面跳转或交互。
     *
     * @param target 点击目标，支持 ref 编号（如 [15]）或文字描述（如链接文本、按钮文字）
     * @return 点击结果描述
     */
    String clickPageElement(String target);

    /**
     * 在当前打开的网页的输入框中输入文字。
     *
     * @param target 输入框定位方式，支持 ref 编号（如 [3]）或文字描述（如占位符文字）
     * @param text   要输入的文字内容
     * @return 输入结果描述
     */
    String typeText(String target, String text);

    /**
     * 列出所有已打开的浏览器标签页。
     *
     * @return 标签页列表描述
     */
    String listTabs();

    /**
     * 切换到指定索引的标签页。
     *
     * @param index 标签页索引（从 0 开始）
     * @return 切换结果描述
     */
    String switchTab(int index);

    /**
     * 释放浏览器资源，关闭所有标签页、浏览器和 Playwright 实例。
     */
    void shutdown();
}
