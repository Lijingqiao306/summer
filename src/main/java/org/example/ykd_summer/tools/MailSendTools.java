package org.example.ykd_summer.tools;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.ReceivedContent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件发送工具
 * 通过 QQ 邮箱 SMTP 发送邮件，供 AI 通过 @Tool 注解调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSendTools {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String senderUsername;

    @Tool(description = "发送邮件到 QQ 邮箱（qqNumber@qq.com）。")
    public String sendMail(
            @ToolParam(description = "收件人 QQ 号，纯数字") String qqNumber,
            @ToolParam(description = "邮件标题") String title,
            @ToolParam(description = "邮件正文内容") String content,
            @ToolParam(description = "附件文件名（可选），如之前上传的文档名", required = false) String attachmentFileName) {
        // 校验 QQ 号格式
        if (qqNumber == null || !qqNumber.matches("\\d{5,12}")) {
            log.warn("无效的 QQ 号: {}", qqNumber);
            return "发送失败：QQ 号格式不正确，请输入 5-12 位的纯数字 QQ 号。";
        }

        // 解析发件人地址：补全不完整的邮箱格式
        String fromAddress = resolveFromAddress(senderUsername);
        if (fromAddress == null) {
            log.error("发件人地址无效，无法发送邮件: senderUsername={}", senderUsername);
            return "邮件发送失败：发件人邮箱地址未配置或格式不正确，请联系管理员检查 QQ_MAIL_USERNAME 环境变量。";
        }

        String toAddress = qqNumber + "@qq.com";

        // 查找附件数据
        ReceivedContent attachment = null;
        if (attachmentFileName != null && !attachmentFileName.isBlank()) {
            attachment = FileTools.findFileAsAttachment(attachmentFileName.trim());
            if (attachment == null) {
                log.warn("sendMail: 未找到附件文件「{}」，将发送无附件邮件", attachmentFileName);
            }
        }

        // 重试机制：最多尝试 2 次
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attachment != null) {
                    sendMailWithAttachment(fromAddress, toAddress, title, content, attachment);
                } else {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(fromAddress);
                    message.setTo(toAddress);
                    message.setSubject(title);
                    message.setText(content);
                    mailSender.send(message);
                }
                String result = attachment != null
                        ? String.format("邮件发送成功！已发送至 %s，标题：「%s」，附件：「%s」",
                                toAddress, title, attachment.fileName())
                        : String.format("邮件发送成功！已发送至 %s，标题：「%s」", toAddress, title);
                log.info("邮件发送成功: from={}, to={}, title={}, hasAttachment={}, attempt={}",
                        fromAddress, toAddress, title, attachment != null, attempt);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("邮件发送失败(attempt={}/{}): to={}, error={}", attempt, maxRetries, toAddress, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000); // 等 2 秒再重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 所有重试都失败，根据常见错误给出具体建议
        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        log.error("邮件发送最终失败: to={}, error={}", toAddress, errorMsg);

        if (errorMsg != null) {
            if (errorMsg.contains("验证失败") || errorMsg.contains("authentication") || errorMsg.contains("535")) {
                return "邮件发送失败：发件人认证失败，请检查 QQ 邮箱 SMTP 授权码是否正确配置。";
            } else if (errorMsg.contains("frequency") || errorMsg.contains("limit") || errorMsg.contains("频率")) {
                return "邮件发送失败：发送频率过高，QQ 邮箱暂时限制了发送，请稍后再试。";
            } else if (errorMsg.contains("收件人") || errorMsg.contains("recipient") || errorMsg.contains("550")) {
                return "邮件发送失败：收件人邮箱 " + toAddress + " 可能不存在或未激活，请确认该 QQ 号已开通邮箱功能。";
            }
        }
        return "邮件发送失败：" + errorMsg + "。请稍后重试或联系管理员检查 SMTP 配置。";
    }

    /**
     * 发送 HTML 邮件（供定时任务等非 AI Tool 调用场景使用）。
     * <p>
     * 与 {@link #sendMail} 的区别：
     * <ul>
     *   <li>正文为 HTML 格式</li>
     *   <li>附件直接传 byte[]，不依赖 {@link ReceivedContent}</li>
     *   <li>有独立的重试机制</li>
     * </ul>
     *
     * @param qqNumber          收件人 QQ 号（纯数字）
     * @param title             邮件标题
     * @param htmlContent       HTML 格式正文
     * @param attachmentFileName 附件文件名（可选）
     * @param attachmentBytes   附件字节数据（可选）
     * @return 发送结果描述
     */
    public String sendHtmlMail(String qqNumber, String title, String htmlContent,
                                String attachmentFileName, byte[] attachmentBytes) {
        // 校验 QQ 号格式
        if (qqNumber == null || !qqNumber.matches("\\d{5,12}")) {
            log.warn("无效的 QQ 号: {}", qqNumber);
            return "发送失败：QQ 号格式不正确，请输入 5-12 位的纯数字 QQ 号。";
        }

        // 解析发件人地址
        String fromAddress = resolveFromAddress(senderUsername);
        if (fromAddress == null) {
            log.error("发件人地址无效，无法发送邮件: senderUsername={}", senderUsername);
            return "邮件发送失败：发件人邮箱地址未配置或格式不正确，请联系管理员检查 QQ_MAIL_USERNAME 环境变量。";
        }

        String toAddress = qqNumber + "@qq.com";
        boolean hasAttachment = attachmentBytes != null && attachmentFileName != null
                && !attachmentFileName.isBlank();

        // 重试机制：最多尝试 2 次
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setFrom(fromAddress);
                helper.setTo(toAddress);
                helper.setSubject(title);
                helper.setText(htmlContent, true); // true = HTML

                if (hasAttachment) {
                    helper.addAttachment(attachmentFileName,
                            new ByteArrayResource(attachmentBytes));
                }

                mailSender.send(mimeMessage);

                String result = hasAttachment
                        ? String.format("邮件发送成功！已发送至 %s，标题：「%s」，附件：「%s」",
                                toAddress, title, attachmentFileName)
                        : String.format("邮件发送成功！已发送至 %s，标题：「%s」", toAddress, title);
                log.info("HTML 邮件发送成功: from={}, to={}, title={}, hasAttachment={}, attempt={}",
                        fromAddress, toAddress, title, hasAttachment, attempt);
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("HTML 邮件发送失败(attempt={}/{}): to={}, error={}",
                        attempt, maxRetries, toAddress, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        String errorMsg = lastException != null ? lastException.getMessage() : "未知错误";
        log.error("HTML 邮件发送最终失败: to={}, error={}", toAddress, errorMsg);

        if (errorMsg != null) {
            if (errorMsg.contains("验证失败") || errorMsg.contains("authentication") || errorMsg.contains("535")) {
                return "邮件发送失败：发件人认证失败，请检查 QQ 邮箱 SMTP 授权码是否正确配置。";
            } else if (errorMsg.contains("frequency") || errorMsg.contains("limit") || errorMsg.contains("频率")) {
                return "邮件发送失败：发送频率过高，QQ 邮箱暂时限制了发送，请稍后再试。";
            } else if (errorMsg.contains("收件人") || errorMsg.contains("recipient") || errorMsg.contains("550")) {
                return "邮件发送失败：收件人邮箱 " + toAddress + " 可能不存在或未激活，请确认该 QQ 号已开通邮箱功能。";
            }
        }
        return "邮件发送失败：" + errorMsg + "。请稍后重试或联系管理员检查 SMTP 配置。";
    }

    /**
     * 发送带附件的 MIME 邮件。
     */
    private void sendMailWithAttachment(String from, String to, String title,
                                         String content, ReceivedContent attachment) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(title);
        helper.setText(content);

        byte[] fileBytes = attachment.toBytes();
        String fileName = attachment.fileName();
        if (fileBytes != null && fileName != null) {
            helper.addAttachment(fileName, new ByteArrayResource(fileBytes), attachment.mimeType());
        }

        mailSender.send(mimeMessage);
    }

    /**
     * 解析并校验发件人地址。
     * <p>
     * 兼容以下输入：
     * <ul>
     *   <li>完整邮箱 {@code xxx@qq.com} → 直接返回</li>
     *   <li>纯 QQ 号 {@code 123456789} → 自动拼接 {@code 123456789@qq.com}</li>
     *   <li>空字符串或 null → 返回 null</li>
     * </ul>
     */
    private String resolveFromAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("发件人地址为空（QQ_MAIL_USERNAME 未设置）");
            return null;
        }
        String candidate = raw.trim();
        // 不含 @ 的纯数字 → 自动补全为 QQ 邮箱
        if (!candidate.contains("@")) {
            if (candidate.matches("\\d{5,12}")) {
                String resolved = candidate + "@qq.com";
                log.info("发件人地址已自动补全: {} → {}", candidate, resolved);
                return resolved;
            }
            log.warn("发件人地址不包含 @ 且非纯数字 QQ 号，无法补全: {}", candidate);
            return null;
        }
        // 含 @ → 校验是否为合法邮箱格式
        try {
            new InternetAddress(candidate).validate();
            return candidate;
        } catch (Exception e) {
            log.warn("发件人邮箱格式校验失败: {}, error={}", candidate, e.getMessage());
            return null;
        }
    }
}
