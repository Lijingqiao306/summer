package org.example.ykd_summer.service;

/**
 * BILIBILI Agent 专用的异步登录会话管理器。
 * <p>
 * 与 {@link BilibiliLoginService} 的区别：支持跨多轮对话保持 Playwright 实例存活。
 * <p>
 * 流程：
 * <ol>
 *   <li>第一轮：{@link #startLogin()} → 创建 Playwright → 截图二维码 → 保持 Browser 不关闭</li>
 *   <li>第二轮（用户说"继续"后）：{@link #completeLoginIfPending(String)} → 检查 SESSDATA → 提取 Cookie → 持久化 → 关闭 Playwright</li>
 *   <li>超时：定时清理超过 5 分钟的会话</li>
 * </ol>
 */
public interface BilibiliLoginSessionService {

    /**
     * 启动非阻塞登录流程，返回二维码截图字节。
     * <p>
     * 调用方负责将截图发送给用户，等待用户扫码后通过
     * {@link #completeLoginIfPending(String)} 完成登录。
     *
     * @return 二维码 PNG 截图字节数组，失败返回 null
     */
    byte[] startLogin();

    /**
     * 检查是否有活跃的登录会话（用于判断是否还在等扫码）。
     *
     * @return true 表示存在活跃会话
     */
    boolean hasActiveSession();

    /**
     * 遍历所有活跃会话，检测是否有用户已完成扫码登录。
     * <p>
     * 检测到 SESSDATA 后：提取 Cookie → 持久化 → 关闭 Playwright → 移除会话。
     *
     * @param userId 用户 ID（用于日志）
     * @return true 表示登录成功并已持久化
     */
    boolean completeLoginIfPending(String userId);
}
