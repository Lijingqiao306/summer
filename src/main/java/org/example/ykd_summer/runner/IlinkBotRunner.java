package org.example.ykd_summer.runner;

import com.github.wechat.ilink.sdk.ILinkClient;
import org.example.ykd_summer.utils.AttachmentContext;
import org.example.ykd_summer.utils.BilibiliToolContext;
import org.example.ykd_summer.utils.ImageWaitStore;
import org.example.ykd_summer.utils.LogUtils;
import org.example.ykd_summer.utils.TaskControlContext;
import org.example.ykd_summer.tools.ImageTools;
import org.example.ykd_summer.web.ConsoleEvent;
import org.example.ykd_summer.web.ConsoleEventBus;
import org.example.ykd_summer.web.TaskTracker;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import lombok.extern.slf4j.Slf4j;
import org.example.ykd_summer.dto.MatchReplyResult;
import org.example.ykd_summer.dto.ReceivedContent;
import org.example.ykd_summer.billing.BillingService;
import org.example.ykd_summer.billing.TokenUsageTracker;
import org.example.ykd_summer.service.AiChatService;
import org.example.ykd_summer.service.VoiceService;
import org.example.ykd_summer.session.SessionStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * iLink Bot 启动器
 * 应用启动后自动执行：二维码登录 → 长轮询拉取消息 → 关键字匹配回复
 */
@Slf4j
@Component
public class IlinkBotRunner implements ApplicationRunner {

    private final AiChatService aiChatService;
    private final VoiceService voiceService;
    private final SessionStore sessionStore;
    private final ConsoleEventBus eventBus;
    private final TaskTracker taskTracker;
    private final TokenUsageTracker tokenUsageTracker;
    private final BillingService billingService;

    public IlinkBotRunner(AiChatService aiChatService,
                          VoiceService voiceService,
                          SessionStore sessionStore,
                          ConsoleEventBus eventBus,
                          TaskTracker taskTracker,
                          TokenUsageTracker tokenUsageTracker,
                          BillingService billingService) {
        this.aiChatService = aiChatService;
        this.voiceService = voiceService;
        this.sessionStore = sessionStore;
        this.eventBus = eventBus;
        this.taskTracker = taskTracker;
        this.tokenUsageTracker = tokenUsageTracker;
        this.billingService = billingService;
    }

    // ==================== Per-user Actor 模式 ====================

    /**
     * 每个用户专属的串行消息处理器。
     * 单线程虚拟线程执行器 + 线程安全队列，保证同用户消息严格串行，
     * 不同用户之间完全并行互不干扰。
     */
    private static class UserActor {
        final ExecutorService executor;
        final Queue<PendingMessage> queue = new ConcurrentLinkedQueue<>();
        volatile boolean running = false;
        volatile String sessionId = UUID.randomUUID().toString();

        UserActor() {
            this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        }
    }

    /** 待处理消息的原始数据容器 */
    private record PendingMessage(List<MessageItem> items) {}

    /** 从消息中提取的内容：（合并后的文本，附件列表，是否为语音来源） */
    private record ExtractedContent(String text, List<ReceivedContent> attachments, boolean isVoice) {}

    /** Per-user Actor 注册表 */
    private final ConcurrentHashMap<String, UserActor> userActors = new ConcurrentHashMap<>();

    // =============================================================

    private volatile ILinkClient client;
    private volatile boolean loggedIn = false;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("正在启动 iLink Bot...");
        eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "iLink Bot 正在启动..."));

        // 尝试从磁盘恢复上次登录的会话
        LoginContext storedCtx = sessionStore.load();
        client = buildClient(storedCtx);

        // 在新线程中执行登录和消息循环
        boolean hasStored = storedCtx != null;
        Thread botThread = new Thread(() -> runBotLoop(hasStored), "ilink-bot-thread");
        botThread.setDaemon(false);
        botThread.start();
    }

    // ==================== Bot 登录与会话恢复 ====================

    /**
     * 构建 ILinkClient，支持传入已有 LoginContext 以跳过扫码。
     */
    private ILinkClient buildClient(LoginContext storedCtx) {
        var builder = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        loggedIn = true;
                        sessionStore.save(context);
                        log.info("iLink 登录成功！botId = {}", context.getBotId());
                        eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "扫码登录成功, botId=" + context.getBotId()));
                        System.out.println("=================================");
                        System.out.println("  iLink Bot 登录成功！");
                        System.out.println("  Bot ID: " + context.getBotId());
                        System.out.println("  等待用户发送消息...");
                        System.out.println("=================================");
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("iLink 登录失败", throwable);
                        eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "登录失败: " + throwable.getMessage()));
                        System.err.println("iLink 登录失败: " + throwable.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        handleMessages(messages);
                    }
                });

        if (storedCtx != null) {
            builder.loginContext(storedCtx);
        }
        return builder.build();
    }

    /**
     * Bot 主循环：尝试恢复会话 → 回退扫码登录 → 长轮询拉取消息。
     * <p>
     * 外层 while(true) 兜底：当 Token 过期导致 getUpdates 认证失败时，
     * 自动清除过期会话，重新进入扫码登录流程。
     */
    private void runBotLoop(boolean hasStored) {
        try {
            boolean restored = hasStored;
            while (true) {
                if (restored) {
                    log.info("使用已存储的登录会话恢复...");
                    loggedIn = true;
                    eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "会话恢复成功"));
                    System.out.println("=================================");
                    System.out.println("  iLink Bot 会话恢复成功！");
                    System.out.println("  等待用户发送消息...");
                    System.out.println("=================================");

                    if (runPollingLoop()) {
                        return; // 正常退出
                    }

                    // Token 过期 → 清除存储 → 回退扫码登录
                    sessionStore.clear();
                    loggedIn = false;
                    restored = false;
                    client = buildClient(null);
                    System.out.println("\n=================================");
                    System.out.println("  会话已过期，请重新扫码登录");
                    System.out.println("=================================");
                    log.warn("会话恢复失败（Token 可能已过期），回退到扫码登录");
                }

                // 扫码登录
                log.info("正在获取登录二维码...");
                eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "等待扫码登录..."));
                String qrCodeContent = client.executeLogin();
                System.out.println("\n=================================");
                System.out.println("  请用微信扫描以下二维码登录 iLink Bot");
                System.out.println("=================================");
                System.out.println(qrCodeContent);
                System.out.println("=================================\n");

                // 阻塞等待扫码成功（onLoginSuccess 回调中已自动保存会话）
                client.getLoginFuture().get();

                log.info("开始长轮询拉取消息...");
                if (runPollingLoop()) {
                    return; // 正常退出
                }

                // 扫码后认证又失败（极端情况：Token 签发后立即失效）
                loggedIn = false;
                client = buildClient(null);
            }
        } catch (Exception e) {
            log.error("iLink Bot 运行异常", e);
        }
    }

    /**
     * 消息长轮询循环。
     *
     * @return true = 正常退出，false = 认证失败需要重新登录
     */
    private boolean runPollingLoop() {
        while (loggedIn) {
            try {
                client.getUpdates();
            } catch (Exception e) {
                if (!loggedIn) {
                    return true;
                }
                if (isAuthError(e)) {
                    log.warn("检测到认证异常，需要重新登录: {}", e.toString());
                    return false;
                }
                log.error("拉取消息异常，3秒后重试...", e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
        }
        return true;
    }

    /**
     * 判定异常是否为 Token 过期/认证失败（而非网络抖动）。
     */
    private boolean isAuthError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.toString().toLowerCase();
            if (msg.contains("401") || msg.contains("403")
                    || msg.contains("unauthorized") || msg.contains("unauthenticated")
                    || msg.contains("auth failed") || msg.contains("invalid token")
                    || msg.contains("token expired")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // =============================================================

    /**
     * 处理收到的消息列表（SDK 回调线程中执行）。
     * <p>
     * 仅做校验 + 入队 + 触发 Actor，不阻塞 SDK 回调线程。
     */
    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            try {
                // 1. 提取消息关键信息
                String userId = msg.getFrom_user_id();
                List<MessageItem> itemList = msg.getItem_list();
                if (itemList == null || itemList.isEmpty()) {
                    continue;
                    // 跳过无效消息，避免无意义入队
                }

                // ===== 暂停/取消/继续指令检测（不入队短路处理） =====
                String textContent = extractTextFromItems(itemList);
                boolean actorRunning = false;
                UserActor existingActor = userActors.get(userId);
                if (existingActor != null) {
                    actorRunning = existingActor.running;
                }

                // 1. "暂停"指令
                if (isPauseCommand(textContent)) {
                    if (actorRunning) {
                        TaskControlContext.setPaused(userId);
                        log.info("收到暂停指令 userId={}, 已设置暂停标志", userId);
                        eventBus.publish(ConsoleEvent.step("IlinkBotRunner",
                                "用户请求暂停任务", userId));
                    } else if (TaskControlContext.hasPausedTask(userId)) {
                        try {
                            client.sendText(userId, "任务已处于暂停状态，回复「继续」继续执行，回复「取消」放弃任务~");
                        } catch (java.io.IOException e) {
                            log.warn("发送暂停状态提醒失败: {}", e.getMessage());
                        }
                    } else {
                        try {
                            client.sendText(userId, "当前没有正在执行的任务，无需暂停~");
                        } catch (java.io.IOException e) {
                            log.warn("发送暂停回复失败: {}", e.getMessage());
                        }
                    }
                    continue;
                }

                // 2. "取消"指令
                if (isCancelCommand(textContent)) {
                    if (actorRunning) {
                        TaskControlContext.setCancelled(userId);
                        log.info("收到取消指令 userId={}, 已设置取消标志", userId);
                        eventBus.publish(ConsoleEvent.step("IlinkBotRunner",
                                "用户请求取消任务", userId));
                    } else if (TaskControlContext.hasPausedTask(userId)) {
                        log.info("收到取消指令，清理暂停任务资源 userId={}", userId);
                        ImageTools.discardGeneratedImage();
                        ImageTools.clearAllImageAttachments();
                        AttachmentContext.clear(userId);
                        BilibiliToolContext.clear(userId);
                        TaskControlContext.discardPausedTask(userId);
                        try {
                            client.sendText(userId, "暂停的任务已取消~");
                        } catch (java.io.IOException e) {
                            log.warn("发送取消回复失败: {}", e.getMessage());
                        }
                    } else {
                        try {
                            client.sendText(userId, "当前没有正在执行的任务~");
                        } catch (java.io.IOException e) {
                            log.warn("发送取消回复失败: {}", e.getMessage());
                        }
                    }
                    continue;
                }

                // 3. "继续"指令 → 不在此处短路，正常入队后交由 AiChatServiceImpl.matchReply() 分流
                //    （有暂停任务 → resume，无暂停任务 → 正常 Router 流程）

                // 2. 获取或创建用户专属 Actor
                // computeIfAbsent 保证线程安全地创建 UserActor，避免重复创建
                UserActor actor = userActors.computeIfAbsent(userId, k -> new UserActor());

                // 3. 原子化入队 + 唤醒判断 原子化 = 互斥 + 不可中断
                //"原子化入队"就是给"放消息"和"叫工人"这两个动作上了一把锁，确保放完消息后，一定能叫到工人，不会出现"消息放了但没人管"的情况。
                synchronized (actor) {
                    // 将消息封装为 PendingMessage 入队
                    actor.queue.offer(new PendingMessage(itemList));
                    // 仅当 Actor 未运行时才提交处理任务，避免重复提交
                    if (!actor.running) {
                        actor.running = true;
                        actor.executor.submit(() -> processNext(userId, actor));
                    }
                }
                log.debug("消息已入队 userId={}, queueSize={}", userId, actor.queue.size());
                eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "收到消息: userId=" + userId, userId));
            } catch (Exception e) {
                // 4. 异常隔离：单条消息失败不影响整体流程
                log.error("消息入队失败", e);
            }
        }
    }

    // ==================== 以下为 Per-user Actor 处理逻辑 ====================

    /**
     * 从 MessageItem 列表中提取文本和附件（含语音转写）。
     * 使用 {@link ReceivedContent} 统一载体替代裸 byte[] 和 base64 字符串。
     */
    private ExtractedContent extractContent(List<MessageItem> itemList) {
        MessageItem textItem = null;
        List<ReceivedContent> attachments = new ArrayList<>();
        StringBuilder voiceText = new StringBuilder();

        for (MessageItem item : itemList) {
            if (item.getText_item() != null && textItem == null) {
                textItem = item;
            }
            if (item.getImage_item() != null) {
                try {
                    byte[] imageBytes = client.downloadImageFromMessageItem(item);
                    String[] mimeAndName = detectImageMimeAndName(imageBytes);
                    attachments.add(ReceivedContent.build(imageBytes, mimeAndName[0], mimeAndName[1]));
                    log.info("图片下载成功: size={} bytes, mime={}, fileName={}",
                            imageBytes.length, mimeAndName[0], mimeAndName[1]);
                } catch (Exception e) {
                    log.warn("图片下载失败, 跳过该图片: {}", e.getMessage());
                }
            }
            if (item.getFile_item() != null) {
                try {
                    byte[] fileBytes = client.downloadFileFromMessageItem(item);
                    String fileName = item.getFile_item().getFile_name();
                    if (fileName == null || fileName.isBlank()) {
                        fileName = "document.bin";
                    }
                    String mimeType = inferMimeType(fileName);
                    ReceivedContent rc = ReceivedContent.build(fileBytes, mimeType, fileName);
                    log.info("文件下载成功: fileName={}, size={} bytes", fileName, fileBytes.length);

                    // 音频文件：调用 DashScope ASR 转写为文字，不将其作为附件传给 AI
                    if (rc.isAudio()) {
                        String transcribed = voiceService.transcribe(rc.resource());
                        if (transcribed != null && !transcribed.isBlank()) {
                            voiceText.append(transcribed.trim());
                            log.info("音频文件转写完成: fileName={}, text={}", fileName, transcribed.trim());
                        } else {
                            log.warn("音频文件转写为空: fileName={}", fileName);
                        }
                    } else {
                        attachments.add(rc);
                    }
                } catch (Exception e) {
                    log.warn("文件下载失败, 跳过该文件: {}", e.getMessage());
                }
            }
            if (item.getVoice_item() != null) {
                try {
                    VoiceItem voiceItem = item.getVoice_item();
                    // 优先使用微信内置的语音转文字结果
                    String builtinTranscription = voiceItem.getText();
                    if (builtinTranscription != null && !builtinTranscription.isBlank()) {
                        voiceText.append(builtinTranscription.trim());
                        log.info("使用微信内置语音转写: text={}", builtinTranscription.trim());
                    } else {
                        // 微信未提供转写，下载音频交给 DashScope ASR
                        byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
                        if (voiceBytes != null && voiceBytes.length > 0) {
                            String transcribed = voiceService.transcribe(
                                    new org.springframework.core.io.ByteArrayResource(voiceBytes));
                            if (transcribed != null && !transcribed.isBlank()) {
                                voiceText.append(transcribed.trim());
                                log.info("DashScope 语音转写完成: text={}", transcribed.trim());
                            } else {
                                log.warn("语音转写为空，跳过该语音");
                            }
                        } else {
                            log.warn("语音下载为空，跳过该语音");
                        }
                    }
                } catch (Exception e) {
                    log.warn("语音处理失败, 跳过该语音: {}", e.getMessage());
                }
            }
            if (item.getVideo_item() != null) {
                try {
                    byte[] videoBytes = client.downloadVideoFromMessageItem(item);
                    if (videoBytes != null && videoBytes.length > 0) {
                        // VideoItem 不包含文件名，使用通用名称
                        String fileName = "video.mp4";
                        String mimeType = "video/mp4";
                        ReceivedContent rc = ReceivedContent.build(videoBytes, mimeType, fileName);
                        attachments.add(rc);
                        log.info("视频下载成功: size={} bytes, mime={}", videoBytes.length, mimeType);
                    } else {
                        log.warn("视频下载为空，跳过该视频");
                    }
                } catch (Exception e) {
                    log.warn("视频下载失败, 跳过该视频: {}", e.getMessage());
                }
            }
        }

        // 合并语音转写文字到文本
        String finalText;
        if (textItem != null) {
            String rawText = textItem.getText_item().getText();
            if (!voiceText.isEmpty()) {
                finalText = (rawText != null && !rawText.isBlank())
                        ? rawText + "，" + voiceText : voiceText.toString();
            } else {
                finalText = (rawText != null) ? rawText : "";
            }
        } else {
            finalText = voiceText.toString();
        }

        log.info("内容提取完成: text={}, attachmentCount={}",
                LogUtils.truncate(finalText, 50), attachments.size());
        boolean hasVoice = itemList.stream().anyMatch(item -> item.getVoice_item() != null);
        return new ExtractedContent(finalText, attachments, hasVoice);
    }

    /**
     * 提交 AI 任务并阻塞等待结果，在结果文本末尾附加处理耗时。
     * <p>
     * Actor 虚拟线程上直接调用 matchReply（IO 等待时会自动卸载 carrier 线程，不浪费资源）。
     */
    private MatchReplyResult processMessage(String userId, String sessionId, String text,
                                             List<ReceivedContent> attachments, boolean isVoice,
                                             Consumer<String> onProgress) {
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if ((text == null || isSemanticallyEmpty(text)) && !hasAttachments) {
            return null;
        }
        final String finalText = (text != null && !isSemanticallyEmpty(text))
                ? text : buildFallbackText(attachments);
        final List<ReceivedContent> safeAttachments = attachments != null ? attachments : List.of();

        log.info("提交 AI 任务 from={}, text={}, attachmentCount={}", userId,
                LogUtils.truncate(finalText, 50), safeAttachments.size());

        var task = taskTracker.register(userId, "处理消息: " + LogUtils.truncate(finalText, 30));
        long start = System.currentTimeMillis();
        // 采样开始时的累计 token 计数器，任务完成后与终态相减得到本次消耗。
        // 必须在 register 之后、matchReply 之前采样，确保覆盖到本任务产生的所有 LLM 调用。
        TokenUsageTracker.Totals startTotals = tokenUsageTracker.getCurrentTotals();
        try {
            MatchReplyResult result = aiChatService.matchReply(userId, sessionId, finalText, safeAttachments, onProgress);
            long elapsed = System.currentTimeMillis() - start;
            // 计算本次任务消耗的 token（终态 - 始态）
            TokenUsageTracker.Totals endTotals = tokenUsageTracker.getCurrentTotals();
            long promptUsed = Math.max(0L, endTotals.promptTokens() - startTotals.promptTokens());
            long completionUsed = Math.max(0L, endTotals.completionTokens() - startTotals.completionTokens());
            long totalUsed = promptUsed + completionUsed;
            double costUsed = Math.max(0.0, endTotals.cost() - startTotals.cost());
            taskTracker.complete(task.taskId, promptUsed, completionUsed, totalUsed, costUsed);
            double seconds = elapsed / 1000.0;
            String timedReply = result.textReply() + String.format("\n\n⏱ 处理耗时 %.1f 秒", seconds);
            log.info("AI 返回 from={}, reply={}, hasFile={}, elapsed={}s, promptTokens={}, completionTokens={}, cost=¥{:.6f}",
                    userId, LogUtils.truncate(result.textReply(), 50), result.hasFile(),
                    String.format("%.1f", seconds), promptUsed, completionUsed, costUsed);
            eventBus.publish(ConsoleEvent.step("IlinkBotRunner",
                    String.format("AI 回复完成 (%.1fs, %d tokens, ¥%.4f)", seconds, totalUsed, costUsed), userId));
            // 任务完成后主动推送一次最新余额（带防抖，避免 BSS 限流）
            billingService.pushToAll();
            return new MatchReplyResult(timedReply, result.fileData(), isVoice, result.generatedFiles());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            taskTracker.fail(task.taskId, e.getMessage());
            log.error("AI 调用失败 from={}, elapsed={}ms", userId, elapsed, e);
            eventBus.publish(ConsoleEvent.step("IlinkBotRunner", "AI 调用失败: " + e.getMessage(), userId));
            // 即便任务失败也尝试刷新余额（可能仍有部分 LLM 调用成功）
            billingService.pushToAll();
            return new MatchReplyResult("抱歉，AI 服务暂时不可用，请稍后再试~", null, false);
        }
    }

    /**
     * Per-user Actor 主循环。
     * <p>
     * 在用户专属的虚拟线程中运行，循环取出队列中所有待处理消息，
     * 批量拼接后调用 AI，处理完直接回复。不再需要 LLM 合并判断，
     * 因为 ChatMemory 持久化已自动维护会话上下文连续性。
     */
    private void processNext(String userId, UserActor actor) {
        try {
            while (true) {
                // ────── 阶段 1：清空队列，批量累积 ──────
                String accumulatedText = null;
                List<ReceivedContent> accumulatedAttachments = new ArrayList<>();
                boolean accumulatedIsVoice = false;

                while (true) {
                    PendingMessage pending;
                    synchronized (actor) {
                        pending = actor.queue.poll();
                        if (pending == null) {
                            if (accumulatedText == null) {
                                actor.running = false;
                            }
                            break;
                        }
                    }
                    ExtractedContent content = extractContent(pending.items());
                    if (accumulatedText == null) {
                        accumulatedText = content.text();
                    } else {
                        accumulatedText = accumulatedText + "，" + content.text();
                    }
                    accumulatedAttachments.addAll(content.attachments());
                    accumulatedIsVoice = accumulatedIsVoice || content.isVoice();
                }

                // ────── 阶段 1.5：图文时序竞态缓冲 ──────
                // 当文本含图片关键词但本轮无图片时，**立即回复用户等待提示**，然后进入 10 秒轮询。
                // 图片到达后合并，走正常路由流程（此时有图有文，Router 直接输出 BILIBILI 带图步骤）。
                // 不再预调路由（避免用户等 20 秒才收到"请发送配图"的回复）。
                // ImageWaitStore 作为安全网：若 Router 仍误判为 WAIT_IMAGE，matchReply 入口会短路。
                    if (accumulatedText != null
                        && !accumulatedAttachments.stream().anyMatch(ReceivedContent::isImage)
                        && hasImageIntent(accumulatedText)) {
                    log.info("文本含图片关键词且无图片附件，立即回复并进入轮询等待窗口 userId={}", userId);
                    // 暂存原始指令：作为 matchReply 入口的安全网
                    ImageWaitStore.put(userId, accumulatedText);
                    // 立即告知用户正在等待图片（不等路由）
                    sendReply(userId, new MatchReplyResult(
                            "检测到你要发带图动态，请发送配图图片，我会等你 20 秒~", null, false));

                    PendingMessage latePending = null;
                    for (int retry = 0; retry < 40; retry++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        synchronized (actor) {
                            latePending = actor.queue.poll();
                        }
                        if (latePending != null) {
                            log.info("缓冲窗口轮询第{}次捕获到消息 userId={}", retry + 1, userId);
                            break;
                        }
                    }
                    if (latePending != null) {
                        ExtractedContent lateContent = extractContent(latePending.items());
                        accumulatedText = accumulatedText + "，" + lateContent.text();
                        accumulatedAttachments.addAll(lateContent.attachments());
                        accumulatedIsVoice = accumulatedIsVoice || lateContent.isVoice();
                        log.info("缓冲窗口内已合并追加消息 userId={}, text={}", userId, lateContent.text());
                        // 图片已到，继续往下走正常路由流程（有图有文 → Router 输出 BILIBILI 带图）
                    } else {
                        // 超时未收到图片：本次发图意图直接取消，任务作废。
                        // 不再继续走 Router 等图（避免重复"请发图"提示与自相矛盾的话术）。
                        log.info("图片等待窗口已超时（20s 内未收到图片），本次任务取消 userId={}", userId);
                        ImageWaitStore.remove(userId);
                        sendReply(userId, new MatchReplyResult(
                                "20 秒内未收到图片，本次发图任务已取消~", null, false));
                        continue; // 跳过本轮后续 AI 处理，回到循环开头（队列空则自然退出）
                    }
                }

                // ────── 阶段 2：队列已空，无待处理消息则退出 ──────
                if (accumulatedText == null) {
                    break;
                }

                // ────── 阶段 3：跳过空内容 ──────
                if (accumulatedText.isBlank() && accumulatedAttachments.isEmpty()) {
                    continue;
                }

                // ────── 阶段 3.5：#new 指令：重置会话记忆 ──────
                if ("#new".equals(accumulatedText.trim())) {
                    actor.sessionId = UUID.randomUUID().toString();
                    aiChatService.clearLastRound(userId);
                    log.info("用户 {} 发送 #new 指令，会话已重置", userId);
                    client.sendText(userId, "\u2705 会话已重置，记忆已清空，有什么能帮你的？");
                    continue;
                }

                // ────── 阶段 3.6：纯图片无文字 → 暂存图片并询问意图 ──────
                if (isSemanticallyEmpty(accumulatedText)
                        && !accumulatedAttachments.isEmpty()
                        && accumulatedAttachments.stream().allMatch(ReceivedContent::isImage)) {
                    // 特例：有等待封面的视频上传任务 → 图片直接作为封面，注入"继续"自动恢复流程
                    if (org.example.ykd_summer.utils.VideoUploadPendingStore.hasPending(userId)) {
                        log.info("检测到等待封面的上传任务，纯图片自动作为封面继续 userId={}", userId);
                        accumulatedText = "继续";
                        try {
                            client.sendText(userId, "收到封面图片，正在继续上传发布...");
                        } catch (Exception e) {
                            log.warn("发送封面确认消息失败: {}", e.getMessage());
                        }
                        // 不 continue，落入后续正常 AI 处理流程（图片随附件合并）
                    } else {
                        AttachmentContext.put(userId, accumulatedAttachments);
                        log.info("纯图片消息已暂存 userId={}, imageCount={}", userId, accumulatedAttachments.size());
                        try {
                            client.sendText(userId, buildImageIntentPrompt());
                        } catch (Exception e) {
                            log.warn("发送图片意图询问失败: {}", e.getMessage());
                        }
                        continue;
                    }
                }

                // ────── 阶段 3.61：纯视频无文字 → 暂存视频并询问意图 ──────
                if (isSemanticallyEmpty(accumulatedText)
                        && !accumulatedAttachments.isEmpty()
                        && accumulatedAttachments.stream().allMatch(a -> a.isVideo() || a.isImage())) {
                    boolean hasVideo = accumulatedAttachments.stream().anyMatch(ReceivedContent::isVideo);
                    if (hasVideo) {
                        AttachmentContext.put(userId, accumulatedAttachments);
                        log.info("纯视频消息已暂存 userId={}, videoCount={}", userId, accumulatedAttachments.size());
                        try {
                            client.sendText(userId, buildVideoIntentPrompt());
                        } catch (Exception e) {
                            log.warn("发送视频意图询问失败: {}", e.getMessage());
                        }
                        continue;
                    }
                }

                // ────── 阶段 3.65：图文/视频同批到达 → 将附件存入 AttachmentContext ──────
                // 当本轮同时有文字和图片/视频时，将附件暂存到 AttachmentContext，
                // 使 BilibiliAgentTools 的各个工具能找到附件。
                if (!isSemanticallyEmpty(accumulatedText)
                        && accumulatedAttachments.stream().anyMatch(a -> a.isImage() || a.isVideo())) {
                    List<ReceivedContent> mediaAttachments = accumulatedAttachments.stream()
                            .filter(a -> a.isImage() || a.isVideo())
                            .toList();
                    AttachmentContext.put(userId, mediaAttachments);
                    log.info("图文/视频同批已暂存 userId={}, mediaCount={}", userId, mediaAttachments.size());
                }

                // ────── 阶段 3.7：合并暂存图片（peek 不消费，留给 BILIBILI Agent 工具取用）──────
                List<ReceivedContent> pendingImages = AttachmentContext.peek(userId);
                List<ReceivedContent> allAttachments = new ArrayList<>();
                if (pendingImages != null) {
                    // 去重：阶段 3.65 已把本轮媒体暂存进 AttachmentContext（同一批对象引用），
                    // 避免与 accumulatedAttachments 重复合并导致同一张图发送两次
                    List<ReceivedContent> deduped = pendingImages.stream()
                            .filter(p -> accumulatedAttachments.stream().noneMatch(a -> a == p))
                            .toList();
                    allAttachments.addAll(deduped);
                    if (!deduped.isEmpty()) {
                        log.info("已合并暂存图片 userId={}, count={}", userId, deduped.size());
                    }
                }
                allAttachments.addAll(accumulatedAttachments);

                // ────── 阶段 4：调用 AI 处理 ──────
                MatchReplyResult result = processMessage(userId, actor.sessionId, accumulatedText,
                        allAttachments, accumulatedIsVoice,
                        msg -> {
                            try {
                                client.sendText(userId, msg);
                            } catch (java.io.IOException e) {
                                log.warn("发送进度提示失败: {}", e.getMessage());
                            }
                        });
                if (result == null) {
                    continue;
                }

                // 任务取消后重置会话记忆，避免被取消的上下文污染后续对话
                // 注意："已暂停"不重置会话记忆，恢复时需要依赖原有 ChatMemory
                if (result.textReply() != null && result.textReply().startsWith("任务已取消")) {
                    actor.sessionId = UUID.randomUUID().toString();
                    log.info("任务已取消，已重置 userId={} 的会话记忆", userId);
                }

                // ────── 阶段 5：发送回复 ──────
                // 检查 BILIBILI Agent 是否产生了待发送的二维码图片
                byte[] qrImage = BilibiliToolContext.getAndClearPendingImage(userId);
                if (qrImage != null && qrImage.length > 0) {
                    try {
                        client.sendImage(userId, qrImage, "bilibili_qrcode.png", result.textReply());
                        log.info("已发送B站登录二维码图片 from={}, size={} bytes", userId, qrImage.length);
                    } catch (Exception e) {
                        log.warn("发送B站登录二维码失败: {}", e.getMessage());
                        client.sendText(userId, result.textReply());
                    }
                } else {
                    sendReply(userId, result);
                }
                BilibiliToolContext.clear(userId);
                // 清理暂存图片（BILIBILI Agent 已消费或未使用，均可安全清除）
                AttachmentContext.clear(userId);
                // 不再主动 clearContext：AI 处理期间可能已有新消息到达，
                // 提前清除会导致新消息的 context token 丢失，后续 sendText 失败。
                // iLink SDK 自行管理 context token 生命周期。
            }
        } catch (Exception e) {
            log.error("用户 {} Actor 循环异常", userId, e);
            actor.running = false;
        }
    }

    /**
     * 发送回复给用户（文字、图片或语音）。
     * <p>
     * FILE Agent 生成的文档文件（Word/PDF）通过 generatedFiles 字段传递，
     * 在文字回复后以文件附件形式发送。
     */
    private void sendReply(String userId, MatchReplyResult result) {
        try {
            if (result.hasFile()) {
                ReceivedContent file = result.fileData();
                byte[] fileBytes = file.toBytes();
                if (fileBytes != null) {
                    client.sendImage(userId, fileBytes, file.fileName(), result.textReply());
                    log.info("已回复图片 from={}, fileName={}, caption={}",
                            userId, file.fileName(), LogUtils.truncate(result.textReply(), 30));
                } else {
                    // 图片读取失败，降级为纯文本回复
                    client.sendText(userId, result.textReply());
                    log.warn("图片数据读取失败，降级为纯文本 from={}", userId);
                }
            } else if (result.isVoice()) {
                // iLink Bot API 目前不支持原生语音气泡（见 GitHub Issue #6），
                // 改为：先发文字 + 后发 TTS 音频文件（用户可点击下载播放）
                client.sendText(userId, result.textReply());
                log.info("已回复文字 from={}, reply={}", userId, LogUtils.truncate(result.textReply(), 50));

                VoiceService.VoiceResult voiceResult = voiceService.synthesize(result.textReply());
                if (voiceResult.success()) {
                    String fileName = "tts_voice." + voiceResult.format();
                    client.sendFile(userId, voiceResult.bytes(), fileName,
                            "🎤 点击下载播放语音（" + (estimateVoiceDuration(result.textReply()) / 1000) + " 秒）");
                    log.info("已发送语音文件附件 from={}, format={}, size={} bytes",
                            userId, voiceResult.format(), voiceResult.bytes().length);
                } else {
                    log.info("TTS 合成失败，已降级为纯文本 from={}", userId);
                }
            } else {
                client.sendText(userId, result.textReply());
                log.info("已回复 from={}, reply={}", userId, LogUtils.truncate(result.textReply(), 50));
            }

            // 发送 FILE Agent 生成的文档文件（Word/PDF）
            if (result.hasGeneratedFiles()) {
                for (ReceivedContent genFile : result.generatedFiles()) {
                    byte[] fileBytes = genFile.toBytes();
                    if (fileBytes != null && fileBytes.length > 0) {
                        client.sendFile(userId, fileBytes, genFile.fileName(),
                                "📄 " + genFile.fileName());
                        log.info("已发送文档文件 from={}, fileName={}, size={} bytes",
                                userId, genFile.fileName(), fileBytes.length);
                    }
                }
            }
        } catch (Exception e) {
            log.error("发送回复失败 from={}", userId, e);
        }
    }

    /**
     * 估算中文语音播放时长：约 4 字/秒，最少 1 秒。
     */
    private static int estimateVoiceDuration(String text) {
        if (text == null || text.isBlank()) return 1000;
        int chars = text.replaceAll("\\s+", "").length();
        return Math.max(1000, chars * 250);
    }

    /**
     * 构建纯图片无文字时的意图询问提示。
     * <p>
     * 当用户只发图片不带文字时，不直接走识图流程，而是询问用户意图。
     */
    private static String buildImageIntentPrompt() {
        return "你发了一张图片，请问想做以下哪种操作？\n"
                + "1. \uD83D\uDD0D 识别这张图片的内容\n"
                + "2. \uD83D\uDCDD 用这张图发布一条 B站动态\n"
                + "3. \uD83D\uDDBC\uFE0F 以这张图为参考生成新图片\n"
                + "直接回复选项编号或描述你的需求即可~";
    }

    /**
     * 构建纯视频无文字时的意图询问提示。
     * <p>
     * 当用户只发视频不带文字时，不直接走解析流程，而是询问用户意图。
     */
    private static String buildVideoIntentPrompt() {
        return "你发了一个视频，请问想做以下哪种操作？\n"
                + "1. \uD83C\uDFAC 把这个视频上传到 B站\n"
                + "2. \uD83D\uDCDD 识别视频信息并描述内容\n"
                + "直接回复选项编号或描述你的需求即可~";
    }

    /**
     * 根据附件类型生成语义正确的 fallback 文本。
     * <p>
     * 当用户只发文件不带文字时，生成"仅解析"意图的提示，由路由模型识别后进入仅解析模式。
     * <ul>
     *   <li>全是文档 → "请读取文件内容，告知概况，等待我的指令"</li>
     *   <li>全是图片 → "请描述图片内容，等待我的指令"</li>
     *   <li>全是音频 → "请识别这段音频内容"</li>
     *   <li>混合 → "请依次读取各项内容，告知概况，等待我的指令"</li>
     * </ul>
     */
    private static String buildFallbackText(List<ReceivedContent> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "请描述图片内容，等待我的指令"; // 兜底（理论上不会走到这里）
        }
        boolean hasImage = attachments.stream().anyMatch(ReceivedContent::isImage);
        boolean hasVideo = attachments.stream().anyMatch(ReceivedContent::isVideo);
        boolean hasDoc = attachments.stream().anyMatch(ReceivedContent::isDocument);
        boolean hasAudio = attachments.stream().anyMatch(ReceivedContent::isAudio);
        boolean hasOther = attachments.stream().anyMatch(a -> !a.isImage() && !a.isDocument() && !a.isAudio() && !a.isVideo());

        if (hasAudio && !hasImage && !hasDoc && !hasVideo && !hasOther) {
            return "请识别这段音频内容";
        }
        if (hasImage && !hasDoc && !hasAudio && !hasVideo && !hasOther) {
            return "请描述图片内容，等待我的指令";
        }
        if (hasVideo && !hasImage && !hasDoc && !hasAudio && !hasOther) {
            return "请描述这个视频的信息，等待我的指令";
        }
        if (hasDoc && !hasImage && !hasAudio && !hasVideo && !hasOther) {
            return "请读取文件内容，告知概况，等待我的指令";
        }
        // 混合附件或未知类型
        return "请依次读取各项内容，告知概况，等待我的指令";
    }

    /**
     * 根据文件后缀名推断 MIME 类型。
     */
    private static String inferMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        // 视频 MIME 类型
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".flv")) return "video/x-flv";
        return "application/octet-stream";
    }

    /**
     * 判断文本是否语义上为空（仅含标点、空白等无意义字符）。
     * <p>
     * 防止多条空消息拼接产生纯标点文本（如 "，"）被当作有效输入。
     */
    private static boolean isSemanticallyEmpty(String text) {
        if (text == null) return true;
        String cleaned = text.replaceAll("[\\s\\p{P}\\p{S}]", "");
        return cleaned.isEmpty();
    }

    /** 检测文本是否包含图片相关关键词（快速初筛，避免每条消息都预调路由） */
    private static boolean hasImageIntent(String text) {
        if (text == null) return false;
        boolean mentionsImage = text.contains("配图") || text.contains("图片") || text.contains("照片")
                || text.contains("如图") || text.contains("看图") || text.contains("发图");
        if (!mentionsImage) return false;
        // 排除「让 AI 生成图」的意图：这类指令应直接走 Router 输出 IMAGE 步骤，
        // 而非等待用户自己发配图（如"帮我画一张小猫图片""生成一张海报"）。
        boolean isAiDrawing = text.contains("画") || text.contains("生成") || text.contains("作图")
                || text.contains("绘制") || text.contains("文生图") || text.contains("绘一幅")
                || text.contains("做一张图") || text.contains("画一张");
        return !isAiDrawing;
    }

    /** 根据文件头魔数检测图片真实格式，返回 [mimeType, fileName] */
    private static String[] detectImageMimeAndName(byte[] data) {
        if (data == null || data.length < 4) return new String[]{"image/png", "image.png"};
        int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF, b2 = data[2] & 0xFF, b3 = data[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return new String[]{"image/jpeg", "image.jpg"};
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return new String[]{"image/png", "image.png"};
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) return new String[]{"image/gif", "image.gif"};
        if (data.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && (data[8] & 0xFF) == 0x57 && (data[9] & 0xFF) == 0x45) return new String[]{"image/webp", "image.webp"};
        return new String[]{"image/png", "image.png"};
    }

    // ==================== 指令检测辅助方法 ====================

    /** 从 MessageItem 列表中提取第一条文本内容 */
    private static String extractTextFromItems(List<MessageItem> items) {
        for (MessageItem item : items) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

    /** 暂停指令匹配 */
    private static boolean isPauseCommand(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.strip();
        return "暂停".equals(t) || "pause".equalsIgnoreCase(t);
    }

    /** 取消指令匹配 */
    private static boolean isCancelCommand(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.strip();
        return "取消".equals(t) || "cancel".equalsIgnoreCase(t)
                || "停止".equals(t) || "stop".equalsIgnoreCase(t)
                || "终止".equals(t);
    }
}
