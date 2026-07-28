package log.summer.aigc.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.loop.MessageSnapshot;
import log.summer.aigc.loop.SuspendContext;
import log.summer.aigc.service.IConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AgentLoop session state: suspend, persist, cache, and restore.
 *
 * <h3>Two-tier storage</h3>
 * <ol>
 *   <li><b>In-memory cache</b> ({@code inMemoryCache}) — fast path for same-JVM resumes</li>
 *   <li><b>DB</b> ({@code conversation.suspend_context} JSON) — cold path for restart survival</li>
 * </ol>
 *
 * <p>On suspend: serialize messages to DB + cache. On resume: check cache first,
 * fall back to DB deserialization.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStateManager {

    private final IConversationService conversationService;
    private final ObjectMapper objectMapper;

    /**
     * In-memory message cache: userId → serialized message snapshots.
     * Evicted on clearSuspend or JVM restart (DB is the source of truth then).
     */
    private final Map<String, SuspendContext> inMemoryCache = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════
    // Suspend
    // ═══════════════════════════════════════════════════════════

    /**
     * Suspend the current AgentLoop session, persisting full state for later resume.
     *
     * @param userId       the user whose session is suspended
     * @param convId       the DB conversation ID
     * @param toolName     the @Tool method that requested suspension
     * @param reason       human-readable reason, sent to user
     * @param chatMemory   current ChatMemory to snapshot
     */
    public void suspend(String userId, Long convId, String toolName,
                        String reason, ChatMemory chatMemory) {
        List<Message> messages = new ArrayList<>(chatMemory.get(userId));
        List<MessageSnapshot> snapshots = MessageSnapshot.fromMessages(messages);

        SuspendContext ctx = SuspendContext.of(toolName, reason, String.valueOf(convId), snapshots);

        // Serialize to JSON and persist
        String ctxJson;
        try {
            ctxJson = objectMapper.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            log.error("[SESSION] 序列化 SuspendContext 失败 | userId={}", userId, e);
            throw new RuntimeException("Failed to serialize suspend context", e);
        }

        conversationService.updateSuspend(convId, ctxJson, reason);

        // Cache in memory for fast resume
        inMemoryCache.put(userId, ctx);

        log.info("[SESSION] 会话已挂起 | userId={} | tool={} | reason={} | msgCount={}",
                userId, toolName, reason, snapshots.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Query
    // ═══════════════════════════════════════════════════════════

    /** Check if a user has an active suspended session. */
    public boolean isSuspended(String userId) {
        return getSuspendedByUser(userId) != null;
    }

    /** Get the suspended conversation for a user (DB query). */
    public Conversation getSuspendedByUser(String userId) {
        return conversationService.getSuspendedByUser(userId);
    }

    // ═══════════════════════════════════════════════════════════
    // Restore
    // ═══════════════════════════════════════════════════════════

    /**
     * Restore the conversation history into ChatMemory from the suspended state.
     * Tries in-memory cache first (fast), then falls back to DB deserialization (cold).
     *
     * @param conv       the suspended Conversation entity
     * @param chatMemory the ChatMemory to hydrate
     */
    public void restoreMessages(Conversation conv, ChatMemory chatMemory) {
        String userId = conv.getUserId();
        SuspendContext ctx = inMemoryCache.get(userId);

        if (ctx == null && conv.getSuspendContext() != null) {
            // Cold path: deserialize from DB
            try {
                ctx = objectMapper.readValue(conv.getSuspendContext(), SuspendContext.class);
                log.info("[SESSION] 从 DB 恢复会话 | userId={} | msgCount={}",
                        userId, ctx.messagesSnapshot().size());
            } catch (JsonProcessingException e) {
                log.error("[SESSION] 反序列化 SuspendContext 失败 | userId={} | convId={}",
                        userId, conv.getId(), e);
                return;
            }
        }

        if (ctx == null) {
            log.warn("[SESSION] 无挂起上下文可恢复 | userId={}", userId);
            return;
        }

        // Re-hydrate ChatMemory from snapshots
        for (MessageSnapshot snapshot : ctx.messagesSnapshot()) {
            chatMemory.add(userId, snapshot.toSpringAiMessage());
        }

        log.info("[SESSION] ChatMemory 恢复完成 | userId={} | msgCount={}",
                userId, ctx.messagesSnapshot().size());
    }

    // ═══════════════════════════════════════════════════════════
    // Clear
    // ═══════════════════════════════════════════════════════════

    /**
     * Clear the suspend state — conversation is now active again.
     * Called when the user responds and the loop resumes.
     */
    public void clearSuspend(Long convId) {
        conversationService.clearSuspend(convId);
        // Also evict from in-memory cache (userId isn't directly available here,
        // but the next suspend will overwrite anyway)
        log.info("[SESSION] 挂起状态已清除 | convId={}", convId);
    }

    /**
     * Clear in-memory cache for a user. Called when conversation ends normally.
     */
    public void evictCache(String userId) {
        inMemoryCache.remove(userId);
    }
}
