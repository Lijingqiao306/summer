package org.example.ykd_summer.service;

/**
 * B站 Cookie 过期后的自动登录服务。
 * <p>
 * 使用独立 Playwright 实例（不与 BrowserServiceImpl 共用），
 * 打开登录页 → 截图二维码发邮件 → 轮询等待扫码 → 提取新 Cookie 持久化。
 */
public interface BilibiliLoginService {

    /**
     * 执行完整的扫码登录流程。
     * <p>
     * 流程：创建独立 Playwright → 打开登录页 → 截图发邮件 → 轮询 SESSDATA →
     * 二维码过期自动刷新（最多 3 次） → 保存 Cookie → 通知。
     *
     * @return true 表示登录成功并已持久化 Cookie
     */
    boolean loginAndSaveCookie();
}
