package log.summer.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.summer.aigc.config.BotProperties;
import log.summer.aigc.loop.AgentLoop;
import log.summer.aigc.port.BotInboundPort;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatPersistenceService;
import log.summer.aigc.tool.BotMetrics;
import log.summer.common.enums.RouteContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ILink 微信机器人适配器 —— 封装 {@link ILinkClient}，实现 {@link BotInboundPort} 和 {@link MessageSender}。
 *
 * <p>消息处理流程：ILink 回调接收 {@link WeixinMessage} → 构建 {@link BotMessage} →
 * {@link SlashInterceptor#intercept(BotMessage, MessageSender)} → 若未消费：
 * {@link AgentLoop#orchestrate(BotMessage, MessageSender)}。</p>
 *
 * <p>媒体下载（图片/文件 CDN）在此层完成，属于传输层职责。</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ILinkBotAdapter implements BotInboundPort, MessageSender {

    private final AgentLoop agentLoop;
    private final SlashInterceptor slashInterceptor;
    private final BotMetrics botMetrics;
    private final BotProperties botProperties;
    private final ChatPersistenceService persistence;
    private final RetrySender retrySender;

    private ILinkClient client;
    private volatile boolean isRunning = false;

    /** 用户 → 当前活跃会话 ID（内存映射，与 DB 中的 conversation.id 对应） */
    private final java.util.Map<String, Long> activeConversation = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Thread.startVirtualThread(this::startBot);
    }

    private void startBot() {
        try {
            log.info("[BOT] 正在启动微信机器人...");
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(35000).readTimeoutMs(35000).writeTimeoutMs(35000)
                    .httpMaxRetries(3).heartbeatEnabled(true).heartbeatIntervalMs(30000)
                    .build();

            client = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        public void onLoginSuccess(LoginContext ctx) {
                            log.info("[BOT] 登录成功 | botId={}", ctx.getBotId());
                            isRunning = true;
                        }
                        public void onLoginFailure(Throwable t) {
                            log.error("[BOT] 登录失败", t);
                            isRunning = false;
                        }
                    })
                    .onMessage(messages -> messages.forEach(msg -> {
                        try {
                            handleMessage(msg);
                        } catch (Exception e) {
                            log.error("[BOT] 消息回调异常", e);
                        }
                    }))
                    .build();

            log.info("请微信扫码登录：\n{}", client.executeLogin());
            LoginContext ctx = client.getLoginFuture().get();
            log.info("[BOT] 登录完成 | botId={}", ctx.getBotId());
            isRunning = true;
        } catch (Exception e) {
            log.error("[BOT] 启动机器人失败", e);
            isRunning = false;
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[SHUTDOWN] 开始优雅停机...");
        isRunning = false;
        if (client != null) {
            client.close();
        }
        log.info("[SHUTDOWN] 优雅停机完成");
    }

    // ═══════════════════════════════════════════════════════════════
    // BotInboundPort — 外部入口（平台无关）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onMessage(BotMessage msg) {
        botMetrics.recordMessage();
        if (!slashInterceptor.intercept(msg, this)) {
            agentLoop.orchestrate(msg, this);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ILink 消息回调 → BotMessage → SlashInterceptor → AgentLoop
    // ═══════════════════════════════════════════════════════════════

    private void handleMessage(WeixinMessage msg) {
        String userId = msg.getFrom_user_id();
        if (msg.getItem_list() == null) return;
        Set<String> voiceTexts = new HashSet<>();

        for (MessageItem item : msg.getItem_list()) {
            try {
                botMetrics.recordMessage();

                if (item.getVoice_item() != null) {
                    String voiceText = item.getVoice_item().getText();
                    if (voiceText != null && !voiceText.isBlank()) {
                        voiceTexts.add(voiceText.trim());
                    }
                    handleVoiceMessage(userId, item);

                } else if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    if (text != null && voiceTexts.contains(text.trim())) {
                        log.debug("[MSG] 跳过重复文本（已在语音中处理）| userId={}", userId);
                        continue;
                    }
                    saveUserMessage(userId, text, "TEXT");
                    //将用户文本封装成一个标准化的 BotMessage，上下文标记为 TEXT
                    BotMessage botMsg = new BotMessage(userId, text, null, null, null, RouteContext.TEXT);
                    if (!slashInterceptor.intercept(botMsg, this)) {
                        agentLoop.orchestrate(botMsg, this);
                    }

                } else if (item.getImage_item() != null) {
                    saveUserMessage(userId, "[图片消息]", "IMAGE");
                    handleImageMessage(userId, item);

                } else if (item.getFile_item() != null) {
                    saveUserMessage(userId, "[文件消息]", "FILE");
                    handleFileMessage(userId, item);
                }
            } catch (Exception e) {
                log.error("[MSG] 处理消息失败 | userId={} | errorType={}",
                        userId, e.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 图片消息：下载 CDN 图片 → 构建 BotMessage → AgentLoop。
     */
    private void handleImageMessage(String userId, MessageItem item) throws IOException {
        byte[] imageBytes = client.downloadImageFromMessageItem(item);
        if (imageBytes == null || imageBytes.length == 0) {
            sendText(userId, "图片下载失败");
            return;
        }
        BotMessage botMsg = new BotMessage(userId, null, imageBytes, null, null, RouteContext.TEXT);
        if (!slashInterceptor.intercept(botMsg, this)) {
            agentLoop.orchestrate(botMsg, this);
        }
    }

    /**
     * 语音消息：提取 ASR 文本 → RouteContext.VOICE → AgentLoop。
     */
    private void handleVoiceMessage(String userId, MessageItem item) {
        String text = item.getVoice_item().getText();
        if (text == null || text.isEmpty()) {
            sendText(userId, "语音识别失败，请重试");
            return;
        }

        log.info("[VOICE-IN] 语音识别 | userId={} | text=\"{}\"",
                userId, text.length() > 50 ? text.substring(0, 50) + "..." : text);

        saveUserMessage(userId, text, "VOICE");

        BotMessage botMsg = new BotMessage(userId, text, null, null, null, RouteContext.VOICE);
        if (!slashInterceptor.intercept(botMsg, this)) {
            agentLoop.orchestrate(botMsg, this);
        }
    }

    /**
     * 文件消息：大小校验 → 下载 → BotMessage → AgentLoop。
     */
    private void handleFileMessage(String userId, MessageItem item) {
        var fileItem = item.getFile_item();
        String fileName = fileItem.getFile_name();
        long fileLen = parseFileLen(fileItem.getLen());

        log.info("[FILE-IN] 收到文件 | userId={} | fileName={} | size={}bytes | md5={}",
                userId, fileName, fileLen, fileItem.getMd5());

        int maxBytes = botProperties.getFile().getMaxSizeMb() * 1024 * 1024;
        if (fileLen > maxBytes) {
            sendText(userId, String.format("文件过大（%.1f MB），最大支持 %d MB。请压缩后重新发送。",
                    fileLen / 1048576.0, botProperties.getFile().getMaxSizeMb()));
            return;
        }

        try {
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            if (fileBytes == null || fileBytes.length == 0) {
                sendText(userId, "文件下载失败，请稍后重试");
                return;
            }
            sendText(userId, String.format("收到文件「%s」（%.1f KB），正在分析…",
                    fileName, fileBytes.length / 1024.0));

            BotMessage botMsg = new BotMessage(userId, null, null, fileBytes, fileName, RouteContext.TEXT);
            if (!slashInterceptor.intercept(botMsg, this)) {
                agentLoop.orchestrate(botMsg, this);
            }
        } catch (Exception e) {
            log.error("[FILE-IN] 文件下载失败 | userId={} | fileName={}", userId, fileName, e);
            sendText(userId, "文件下载失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MessageSender 实现（发送 + 持久化 + 重试）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void sendText(String userId, String content) {
        saveBotMessage(userId, content, "TEXT");
        if (client != null && isRunning) {
            try {
                client.sendTextWithTyping(userId, content, 1500L);
            } catch (Exception e) {
                log.error("[SEND] 发送消息失败 | userId={}", userId, e);
            }
        }
    }

    @Override
    public void sendImage(String userId, byte[] imageBytes, String filename, String description) {
        saveBotMessage(userId, description != null ? description : "[图片]", "IMAGE");
        if (client == null || !isRunning) {
            log.warn("[SEND] 图片发送被跳过（client={}, isRunning={}）| userId={} | file={}",
                    client != null, isRunning, userId, filename);
            sendText(userId, "图片发送失败：系统未就绪，请稍后重试");
            return;
        }
        retrySender.sendWithRetry(userId,
                () -> { try { client.sendImage(userId, imageBytes, filename, description); } catch (IOException e) { throw new RuntimeException(e); } },
                "图片", filename,
                this::sendText);
    }

    @Override
    public void sendFile(String userId, byte[] fileBytes, String filename, String description) {
        saveBotMessage(userId, description != null ? description : "[文件]", "FILE");
        if (client == null || !isRunning) {
            log.warn("[SEND] 文件发送被跳过（client={}, isRunning={}）| userId={} | file={}",
                    client != null, isRunning, userId, filename);
            sendText(userId, "文件发送失败：系统未就绪，请稍后重试");
            return;
        }
        retrySender.sendWithRetry(userId,
                () -> { try { client.sendFile(userId, fileBytes, filename, description); } catch (IOException e) { throw new RuntimeException(e); } },
                "文件", filename,
                this::sendText);
    }

    // ═══════════════════════════════════════════════════════════════
    // 持久化辅助方法
    // ═══════════════════════════════════════════════════════════════

    private void saveUserMessage(String userId, String content, String msgType) {
        try {
            Long convId = getOrCreateConversation(userId, "TEXT");
            persistence.saveMessage(convId, userId, "USER", content, msgType.toLowerCase());
        } catch (Exception e) {
            log.warn("[DB] 保存用户消息失败 | userId={}", userId, e);
        }
    }

    private void saveBotMessage(String userId, String content, String msgType) {
        try {
            Long convId = activeConversation.get(userId);
            if (convId == null) {
                convId = getOrCreateConversation(userId, "TEXT");
            }
            persistence.saveMessage(convId, userId, "BOT", content, msgType.toLowerCase());
        } catch (Exception e) {
            log.warn("[DB] 保存 Bot 消息失败 | userId={}", userId, e);
        }
    }

    private Long getOrCreateConversation(String userId, String routeContext) {
        return activeConversation.computeIfAbsent(userId, uid -> {
            var conv = persistence.newConversation(userId, routeContext);
            return conv.getId();
        });
    }

    private long parseFileLen(String len) {
        try {
            return Long.parseLong(len);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
