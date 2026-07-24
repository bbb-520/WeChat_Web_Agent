package log.demo.linkDemo.tools.chat;

import log.demo.linkDemo.entity.Conversation;
import log.demo.linkDemo.entity.Message;
import log.demo.linkDemo.entity.UserMemory;
import log.demo.linkDemo.tools.Agent;
import log.demo.linkDemo.tools.AgentContext;
import log.demo.linkDemo.tools.Intent;
import log.demo.linkDemo.tools.image.ImageCacheManager;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.enums.RouteContext;
import log.demo.linkDemo.exception.AIServiceException;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import log.demo.linkDemo.rag.RAGRetrievalService;
import log.demo.linkDemo.service.IConversationService;
import log.demo.linkDemo.service.IMessageService;
import log.demo.linkDemo.service.IUserMemoryService;
import log.demo.linkDemo.tools.voice.TTSEngine;
import log.demo.linkDemo.tools.voice.TimbreSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 通用对话智能体 —— 通义千问（qwen-plus）+ 三级记忆体系。
 *
 * <h3>记忆体系</h3>
 * <ol>
 *   <li><b>短期记忆（ChatMemory）</b>：滑动窗口 20 条，由 Spring AI MessageChatMemoryAdvisor 管理</li>
 *   <li><b>会话记忆（DB）</b>：从 conversation/message 表加载最近 N 条历史消息注入 prompt</li>
 *   <li><b>长期记忆（UserMemory）</b>：向量化存储用户偏好/事实，跨会话检索</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAgent implements Agent {

    private final ChatService chatService;
    private final TTSEngine ttsEngine;
    private final TimbreSession timbreSession;
    private final ImageCacheManager imageCacheManager;
    private final RAGRetrievalService ragRetrievalService;
    private final IConversationService conversationService;
    private final IMessageService messageService;
    private final IUserMemoryService userMemoryService;

    /** 注入 prompt 的 DB 历史消息条数上限 */
    private static final int DB_HISTORY_LIMIT = 6;

    @Override public String name() { return "chat"; }
    @Override public Intent intent() { return Intent.CHAT; }

    @Override
    public boolean execute(AgentContext ctx) {
        imageCacheManager.removeSilently(ctx.userId());
        log.info("[CHAT] userId={} | context={} | textLen={}",
                ctx.userId(), ctx.routeContext(), ctx.text() != null ? ctx.text().length() : 0);

        boolean isVoice = ctx.routeContext() == RouteContext.VOICE;

        // ── 组装增强 user message：DB历史 + 长期记忆 + 原始消息 ──
        String augmentedText = buildAugmentedMessage(ctx.userId(), ctx.text());

        String response;
        try {
            if (!isVoice) {
                var docs = ragRetrievalService.retrieve(ctx.userId(), ctx.text());
                if (ragRetrievalService.hasRelevant(docs)) {
                    response = chatService.chatWithRAG(ctx.userId(), augmentedText, docs);
                } else {
                    response = chatService.chat(ctx.userId(), augmentedText);
                }
            } else {
                response = chatService.chat(ctx.userId(), augmentedText);
            }
        } catch (AIServiceException e) {
            log.error("[CHAT] AI 失败 | userId={} | operation={}", ctx.userId(), e.getOperation(), e);
            ctx.sender().sendText(ctx.userId(), "AI 回复失败：" + e.getMessage());
            return true;
        }

        // ── 异步保存长期记忆 ──
        Thread.startVirtualThread(() -> saveLongTermMemory(ctx.userId(), ctx.text(), response));

        // VOICE：仅语音；TEXT：仅文本
        if (isVoice) {
            ttsAndSend(ctx.userId(), response, ctx.sender());
        } else {
            ctx.sender().sendText(ctx.userId(), response);
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // 增强消息：DB 历史 + 长期记忆
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将 DB 中的最近历史消息和 UserMemory 长期记忆注入当前消息。
     */
    private String buildAugmentedMessage(String userId, String text) {
        StringBuilder sb = new StringBuilder();

        // 1) DB 会话历史（最近 N 条）
        String dbHistory = loadDbHistory(userId);
        if (!dbHistory.isEmpty()) {
            sb.append(dbHistory).append("\n");
        }

        // 2) UserMemory 长期记忆
        String longTermMemory = loadLongTermMemory(userId);
        if (!longTermMemory.isEmpty()) {
            sb.append("[用户已知信息]\n").append(longTermMemory).append("\n\n");
        }

        sb.append(text);
        String result = sb.toString();

        if (result.length() > text.length()) {
            log.debug("[CHAT] 增强消息 | userId={} | originalLen={} | augmentedLen={}",
                    userId, text.length(), result.length());
        }
        return result;
    }

    /**
     * 从 DB 加载当前活跃会话的最近 N 条消息并格式化为对话历史。
     */
    private String loadDbHistory(String userId) {
        try {
            Conversation conv = conversationService.getActiveConversation(userId);
            if (conv == null) return "";

            List<Message> messages = messageService.getMessages(conv.getId());
            if (messages == null || messages.isEmpty()) return "";

            // 取最近 N 条
            int start = Math.max(0, messages.size() - DB_HISTORY_LIMIT);
            List<Message> recent = messages.subList(start, messages.size());

            return recent.stream()
                    .map(m -> {
                        String role = "USER".equals(m.getMessageType()) ? "用户" : "助手";
                        String content = m.getTextContent() != null ? m.getTextContent() : "";
                        if (content.length() > 200) content = content.substring(0, 200) + "...";
                        return role + "：" + content;
                    })
                    .collect(Collectors.joining("\n", "[对话历史]\n", ""));
        } catch (Exception e) {
            log.debug("[CHAT] 加载 DB 历史失败 | userId={} | {}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 从 UserMemory 表加载用户长期记忆。
     */
    private String loadLongTermMemory(String userId) {
        try {
            List<UserMemory> memories = userMemoryService.lambdaQuery()
                    .eq(UserMemory::getUserId, userId)
                    .orderByDesc(UserMemory::getImportance)
                    .last("LIMIT 5")
                    .list();

            if (memories == null || memories.isEmpty()) return "";

            return memories.stream()
                    .map(m -> "- " + m.getContent())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.debug("[CHAT] 加载长期记忆失败 | userId={} | {}", userId, e.getMessage());
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 长期记忆保存
    // ═══════════════════════════════════════════════════════════════

    /**
     * 异步保存有意义的对话内容到 UserMemory。
     * 只对较长回复（>30字）生成记忆，避免保存简单应答。
     */
    private void saveLongTermMemory(String userId, String userText, String botResponse) {
        try {
            if (userText == null || userText.length() < 10) return;
            if (botResponse == null || botResponse.length() < 30) return;

            // 简单去重：检查是否已有相似内容
            long existingCount = userMemoryService.lambdaQuery()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getContent, userText)
                    .count();
            if (existingCount > 0) return;

            // 将用户消息作为长期记忆保存（importance 默认 0.5）
            UserMemory memory = new UserMemory();
            memory.setUserId(userId);
            memory.setMemoryType("chat");
            memory.setContent(userText);
            memory.setImportance(0.5f);
            memory.setCreatedAt(java.time.LocalDateTime.now());
            userMemoryService.save(memory);

            log.debug("[CHAT] 长期记忆已保存 | userId={} | content=\"{}\"",
                    userId, userText.length() > 40 ? userText.substring(0, 40) + "..." : userText);
        } catch (Exception e) {
            log.debug("[CHAT] 保存长期记忆失败 | userId={} | {}", userId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TTS
    // ═══════════════════════════════════════════════════════════════

    private void ttsAndSend(String userId, String text, log.demo.linkDemo.service.MessageSender sender) {
        try {
            VoiceResult result = ttsEngine.synthesize(text, timbreSession.getCurrentVoiceId(userId));
            if (result.hasAudio()) {
                sender.sendFile(userId, result.wavAudio(), "tts-" + System.currentTimeMillis() + ".wav", text);
            } else {
                sender.sendText(userId, text);
            }
        } catch (VoiceSynthesisException e) {
            log.warn("[CHAT] TTS 失败，降级为文本 | userId={}", userId, e);
            sender.sendText(userId, text);
        } catch (Exception e) {
            log.warn("[CHAT] TTS 发送失败 | userId={}", userId, e);
        }
    }
}
