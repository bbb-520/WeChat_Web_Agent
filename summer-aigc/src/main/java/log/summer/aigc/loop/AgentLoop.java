package log.summer.aigc.loop;

import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatService;
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
 * Dual-loop agent orchestrator.
 *
 * <h3>Flow</h3>
 * <pre>
 * while (round &lt; maxIterations &amp;&amp; !timeout):
 *   THINK: LLM &rarr; ThinkResult
 *   if finalAnswer &rarr; send to user, exit
 *   if toolCalls &rarr; ACT: execute each tool, append results to memory
 *   repeat
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

    private static final int MAX_ITERATIONS = 10;
    private static final long TIMEOUT_SECONDS = 120;

    /**
     * Orchestrate a single user message through the think-act loop.
     *
     * @param msg    the normalized inbound message
     * @param sender the output channel back to the user
     */
    public void orchestrate(BotMessage msg, MessageSender sender) {
        String userId = msg.userId();

        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        // ── Add user message to memory ──
        if (msg.hasText()) {
            chatMemory.add(userId,
                    new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(userId,
                    new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(userId,
                    new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
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
                // Build message list from ChatMemory
                List<Message> messages = new ArrayList<>(
                        chatMemory.get(userId));

                var chatResponse = chatService.chatWithTools(
                        messages,
                        toolRegistry.getCallbacks());

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

                        // Build result text for the LLM
                        String resultText = result.success()
                                ? (result.data() != null ? result.data().toString()
                                        : "success")
                                : "ERROR: " + result.errorMessage();

                        // Append tool result to memory as a ToolResponseMessage.
                        // Spring AI 1.0.0: ToolResponseMessage wraps a list of
                        // ToolResponse(id, name, responseData) records.
                        String callId = UUID.randomUUID().toString();
                        var toolResponse = new ToolResponseMessage.ToolResponse(
                                callId, toolCall.name(), resultText);
                        chatMemory.add(userId,
                                new ToolResponseMessage(List.of(toolResponse)));

                        log.debug("[AGENT-LOOP] 工具结果 | userId={} | tool={} | success={}",
                                userId, toolCall.name(), result.success());
                    }
                    // Continue loop → next think round with tool results in context
                    continue;
                }

                // No tool calls and no final answer → safety break
                log.warn("[AGENT-LOOP] LLM 返回空响应 | userId={} | round={}", userId, round);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            // ── Max iterations exceeded (only if no final answer was already sent) ──
            if (!finalAnswerSent && round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId,
                        "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop", e);
        } finally {
            // Clear conversation memory so the next message starts fresh
            chatMemory.clear(userId);
        }
    }
}
