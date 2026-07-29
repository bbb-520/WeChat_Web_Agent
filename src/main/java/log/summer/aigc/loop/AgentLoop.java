package log.summer.aigc.loop;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatPersistenceService;
import log.summer.aigc.service.ChatService;
import log.summer.aigc.session.SessionStateManager;
import log.summer.aigc.tool.ToolRegistry;
import log.summer.aigc.config.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dual-loop agent orchestrator with suspend/resume support.
 *
 * <h3>Flow</h3>
 * <pre>
 * 1. Check if user has a SUSPENDED conversation &rarr; resume path
 * 2. Otherwise, start fresh:
 *    while (round &lt; maxIterations &amp;&amp; !timeout):
 *      THINK: LLM &rarr; ThinkResult
 *      if finalAnswer &rarr; send to user, exit
 *      if toolCalls &rarr; ACT: execute each tool, append results to memory
 *      if tool returned suspend &rarr; persist state, exit (don't clear memory)
 *      repeat
 * </pre>
 *
 * <p>Spring AI is used only for single-call interactions.
 * This class owns the iteration, memory, and termination logic.</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final ArgumentResolver argumentResolver;
    private final ChatMemory chatMemory;
    private final GlobalExceptionHandler exceptionHandler;
    private final SessionStateManager sessionStateManager;
    private final ChatPersistenceService chatPersistenceService;

    private static final int MAX_ITERATIONS = 10;
    private static final long TIMEOUT_SECONDS = 300;

    /**
     * Orchestrate a single user message through the think-act loop.
     *
     * @param msg    the normalized inbound message
     * @param sender the output channel back to the user
     */
    public void orchestrate(BotMessage msg, MessageSender sender) {
        String userId = msg.userId();
        boolean suspended = false;

        // ── BUG FIX: bind request-scoped user/conversation into ThreadLocal
        //    so that @Tool methods can read the real values without asking
        //    the LLM to invent them. ──
        UserContextHolder.setUserId(userId);
        // Look up (or no-op) the active conversation id for this user.
        Long activeConvId = resolveActiveConvId(userId);
        UserContextHolder.setConversationId(activeConvId);

        // ── Resume check: does user have a suspended session? ──
        Conversation suspendedConv = sessionStateManager.getSuspendedByUser(userId);
        if (suspendedConv != null) {
            log.info("[AGENT-LOOP] 检测到挂起会话 | userId={} | convId={} | reason={}",
                    userId, suspendedConv.getId(), suspendedConv.getSuspendReason());
            // Override conversation id with the suspended one (it's the
            // authoritative ID for the resume round).
            UserContextHolder.setConversationId(suspendedConv.getId());
            try {
                resumeOrchestrate(msg, sender, suspendedConv);
            } finally {
                UserContextHolder.clear();
            }
            return;
        }

        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        // ── Add user message to memory ──
        if (msg.hasText()) {
            chatMemory.add(userId, new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(userId, new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(userId, new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }

        try {
            while (round < MAX_ITERATIONS) {
                // ── Safety valve: timeout ──
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 超时 | userId={} | rounds={}", userId, round);
                    break;
                }

                round++;
                log.debug("[AGENT-LOOP] 第 {} 轮思考 | userId={}", round, userId);

                // ── THINK ──
                List<Message> messages = new ArrayList<>(chatMemory.get(userId));

                var chatResponse = chatService.chatWithTools(
                        messages, toolRegistry.getCallbacks());

                ThinkResult think = ThinkResult.fromChatResponse(chatResponse);

                // ── Final answer (no tool calls) → exit ──
                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                    log.debug("[AGENT-LOOP] 最终答案 | userId={} | rounds={}", userId, round);
                    break;
                }

                // ── Final answer + tool calls (LLM can return both) ──
                if (think.hasFinalAnswer() && think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                // ── ACT: execute tool calls ──
                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        log.info("[AGENT-LOOP] 调用工具 | userId={} | tool={} | args={}",
                                userId, toolCall.name(), toolCall.arguments());

                        ActResult result;
                        try {
                            Map<String, Object> resolvedArgs =
                                    argumentResolver.resolve(toolCall.arguments(), msg);
                            result = toolRegistry.execute(toolCall.name(), resolvedArgs);
                        } catch (ArgumentResolver.PlaceholderResolutionException e) {
                            result = ActResult.failure(e.getMessage());
                        }

                        // ── NEW: Check for suspend signal ──
                        if (result.suspend()) {
                            log.info("[AGENT-LOOP] 工具请求挂起 | userId={} | tool={} | reason={}",
                                    userId, toolCall.name(), result.suspendReason());

                            // Send the tool's data (e.g. outline) and suspend reason to user
                            if (result.data() != null) {
                                sender.sendText(userId, result.data().toString());
                            }
                            if (result.suspendReason() != null) {
                                sender.sendText(userId, result.suspendReason());
                            }

                            // Persist suspend state via SessionStateManager
                            // BUG FIX: a tool may have just created a new
                            // conversation row (e.g. createOutline) so we
                            // re-query instead of trusting the value we
                            // resolved at the start of the loop.
                            Conversation activeConv = chatPersistenceService.getActiveConversation(userId);
                            long convId = activeConv != null ? activeConv.getId() : 0L;
                            UserContextHolder.setConversationId(convId);

                            sessionStateManager.suspend(
                                    userId, convId, toolCall.name(),
                                    result.suspendReason(), chatMemory);

                            suspended = true;
                            break; // exit tool-call loop
                        }

                        // ── Normal result handling ──
                        String resultText;
                        if (result.success() && result.data() instanceof Map<?, ?> dataMap) {
                            // Check if the tool produced a file for delivery.
                            // BUG FIX: after JSON round-trip through ToolRegistry,
                            // byte[] is serialized as base64 String. Handle both.
                            Object fileBytes = dataMap.get("fileBytes");
                            Object fileName = dataMap.get("fileName");
                            if (fileName instanceof String name) {
                                byte[] bytes = resolveFileBytes(fileBytes);
                                if (bytes != null && bytes.length > 0) {
                                    log.info("[AGENT-LOOP] 下发文件 | userId={} | fileName={} | size={}bytes",
                                            userId, name, bytes.length);
                                    sender.sendFile(userId, bytes, name, "生成的文档");
                                }
                            }

                            // Build a text summary for the LLM (exclude binary data)
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Map<String, Object> llmView = new java.util.LinkedHashMap<>((Map) dataMap);
                            llmView.remove("fileBytes");
                            resultText = llmView.toString();
                        } else {
                            resultText = result.success()
                                    ? (result.data() != null ? result.data().toString() : "success")
                                    : "ERROR: " + result.errorMessage();
                        }

                        String callId = UUID.randomUUID().toString();
                        var toolResponse = new ToolResponseMessage.ToolResponse(
                                callId, toolCall.name(), resultText);
                        chatMemory.add(userId,
                                new ToolResponseMessage(List.of(toolResponse)));

                        log.debug("[AGENT-LOOP] 工具结果 | userId={} | tool={} | success={}",
                                userId, toolCall.name(), result.success());
                    }

                    // If we suspended, break out of the main while loop
                    if (suspended) {
                        break;
                    }
                    // Continue loop → next think round with tool results in context
                    continue;
                }

                // No tool calls and no final answer → safety break
                log.warn("[AGENT-LOOP] LLM 返回空响应 | userId={} | round={}", userId, round);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            // ── Max iterations exceeded ──
            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId,
                        "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop", e);
        } finally {
            // Clear conversation memory ONLY if not suspended
            if (!suspended) {
                chatMemory.clear(userId);
                sessionStateManager.evictCache(userId);
            }
            // BUG FIX: always release ThreadLocal so a virtual thread
            // recycled by the JVM doesn't leak user identity into the next
            // request that happens to land on it.
            UserContextHolder.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Resume path
    // ═══════════════════════════════════════════════════════════

    /**
     * Resume a previously suspended AgentLoop session.
     * Restores ChatMemory from the persisted state, appends the new user
     * message, and continues the think-act loop from where it left off.
     */
    private void resumeOrchestrate(BotMessage msg, MessageSender sender,
                                   Conversation suspendedConv) {
        String userId = msg.userId();
        boolean suspended = false;

        // Override the conversation id with the suspended one (it is the
        // authoritative ID for the resume round).
        UserContextHolder.setConversationId(suspendedConv.getId());

        // ── Restore ChatMemory from suspend context ──
        chatMemory.clear(userId); // ensure clean slate
        sessionStateManager.restoreMessages(suspendedConv, chatMemory);

        // ── Append the new user message ──
        if (msg.hasText()) {
            chatMemory.add(userId, new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(userId, new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(userId, new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }

        // ── Clear the suspend state (we're resuming) ──
        sessionStateManager.clearSuspend(suspendedConv.getId());

        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        try {
            while (round < MAX_ITERATIONS) {
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 恢复后超时 | userId={} | rounds={}", userId, round);
                    break;
                }

                round++;
                log.debug("[AGENT-LOOP] 恢复后第 {} 轮思考 | userId={}", round, userId);

                List<Message> messages = new ArrayList<>(chatMemory.get(userId));

                var chatResponse = chatService.chatWithTools(
                        messages, toolRegistry.getCallbacks());

                ThinkResult think = ThinkResult.fromChatResponse(chatResponse);

                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                    break;
                }

                if (think.hasFinalAnswer() && think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        log.info("[AGENT-LOOP] 恢复后调用工具 | userId={} | tool={}",
                                userId, toolCall.name());

                        ActResult result;
                        try {
                            Map<String, Object> resolvedArgs =
                                    argumentResolver.resolve(toolCall.arguments(), msg);
                            result = toolRegistry.execute(toolCall.name(), resolvedArgs);
                        } catch (ArgumentResolver.PlaceholderResolutionException e) {
                            result = ActResult.failure(e.getMessage());
                        }

                        // Check for another suspend (nested confirmation)
                        if (result.suspend()) {
                            log.info("[AGENT-LOOP] 工具再次请求挂起 | userId={} | tool={}",
                                    userId, toolCall.name());

                            if (result.data() != null) {
                                sender.sendText(userId, result.data().toString());
                            }
                            if (result.suspendReason() != null) {
                                sender.sendText(userId, result.suspendReason());
                            }

                            // BUG FIX: re-resolve convId in case the tool just
                            // created a new conversation row.
                            Conversation activeConvResume =
                                    chatPersistenceService.getActiveConversation(userId);
                            long resumeConvId = activeConvResume != null
                                    ? activeConvResume.getId()
                                    : suspendedConv.getId();
                            UserContextHolder.setConversationId(resumeConvId);

                            sessionStateManager.suspend(
                                    userId, resumeConvId, toolCall.name(),
                                    result.suspendReason(), chatMemory);

                            suspended = true;
                            break;
                        }

                        // ── Normal result handling ──
                        String resultText;
                        if (result.success() && result.data() instanceof Map<?, ?> dataMap) {
                            // Check if the tool produced a file for delivery.
                            // BUG FIX: after JSON round-trip through ToolRegistry,
                            // byte[] is serialized as base64 String. Handle both.
                            Object fileBytes = dataMap.get("fileBytes");
                            Object fileName = dataMap.get("fileName");
                            if (fileName instanceof String name) {
                                byte[] bytes = resolveFileBytes(fileBytes);
                                if (bytes != null && bytes.length > 0) {
                                    log.info("[AGENT-LOOP] 下发文件 | userId={} | fileName={} | size={}bytes",
                                            userId, name, bytes.length);
                                    sender.sendFile(userId, bytes, name, "生成的文档");
                                }
                            }

                            // Build a text summary for the LLM (exclude binary data)
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Map<String, Object> llmView = new java.util.LinkedHashMap<>((Map) dataMap);
                            llmView.remove("fileBytes");
                            resultText = llmView.toString();
                        } else {
                            resultText = result.success()
                                    ? (result.data() != null ? result.data().toString() : "success")
                                    : "ERROR: " + result.errorMessage();
                        }

                        String callId = UUID.randomUUID().toString();
                        var toolResponse = new ToolResponseMessage.ToolResponse(
                                callId, toolCall.name(), resultText);
                        chatMemory.add(userId,
                                new ToolResponseMessage(List.of(toolResponse)));
                    }

                    if (suspended) break;
                    continue;
                }

                log.warn("[AGENT-LOOP] 恢复后 LLM 返回空响应 | userId={}", userId);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 恢复后达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId, "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 恢复后循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop-resume", e);
        } finally {
            if (!suspended) {
                chatMemory.clear(userId);
                sessionStateManager.evictCache(userId);
            }
            // BUG FIX: same as in orchestrate — release the ThreadLocal.
            UserContextHolder.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Resolve file bytes from an ActResult data map value.
     *
     * <p>When a tool puts {@code byte[]} into {@link ActResult#data()},
     * the value travels through JSON serialization (Spring AI
     * {@code MethodToolCallback}) and deserialization
     * ({@link ToolRegistry#execute}). Jackson serializes {@code byte[]}
     * as a base64-encoded {@code String}, so after the round-trip the
     * value is <b>not</b> a {@code byte[]} anymore — it is a
     * {@code String}.</p>
     *
     * <p>This method handles both representations so file delivery works
     * regardless of whether the value survived the JSON round-trip.</p>
     *
     * @param raw the value from the data map (may be {@code byte[]} or
     *            base64 {@code String})
     * @return decoded bytes, or {@code null} if unrecognised / empty
     */
    private byte[] resolveFileBytes(Object raw) {
        if (raw instanceof byte[] bytes) {
            return bytes;
        }
        if (raw instanceof String base64) {
            try {
                return java.util.Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                log.warn("[AGENT-LOOP] fileBytes 不是有效的 base64 字符串 | len={}", base64.length());
                return null;
            }
        }
        return null;
    }

    /**
     * Resolve the active conversation id for a user, swallowing any DB
     * errors so the AgentLoop is never blocked by persistence issues.
     */
    private Long resolveActiveConvId(String userId) {
        try {
            Conversation c = chatPersistenceService.getActiveConversation(userId);
            return c != null ? c.getId() : null;
        } catch (Exception e) {
            log.warn("[AGENT-LOOP] 解析活跃会话失败（忽略，继续） | userId={} | {}",
                    userId, e.getMessage());
            return null;
        }
    }
}
