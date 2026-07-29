package log.summer.aigc.loop;

import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 外循环（执行与感知）—— 纯工具执行。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>接收内循环发来的工具调用指令</li>
 *   <li>解析参数占位符（{@code ${message.image}} 等 → 实际数据）</li>
 *   <li>执行工具 → 返回 {@link ActResult}</li>
 *   <li>检测挂起信号 + 文件下发</li>
 *   <li>将执行结果以 {@code ToolResponseMessage} 回灌 ChatMemory</li>
 * </ol>
 *
 * <p>不负责：LLM 交互、记忆清理、安全阀 — 这些由内循环和编排器处理。</p>
 *
 * @author bbb
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActLoop {

    private final ToolRegistry toolRegistry;
    private final ArgumentResolver argumentResolver;

    /**
     * 执行单个工具调用。
     *
     * @param toolCall 内循环发来的工具调用（name + arguments）
     * @param msg      原始入站消息（用于解析二进制占位符）
     * @param userId   当前用户 ID
     * @param memory   当前 ChatMemory（执行结果回灌）
     * @param sender   消息发送通道（用于文件下发）
     * @return ActResult — 包含执行结果、挂起信号、文件数据等
     */
    public ActResult execute(
            ThinkResult.ToolCall toolCall,
            BotMessage msg,
            String userId,
            ChatMemory memory,
            MessageSender sender) {

        String toolName = toolCall.name();
        log.info("[OUTER-LOOP] 执行工具 | userId={} | tool={} | args={}",
                userId, toolName, toolCall.arguments());

        // 1. 解析参数占位符
        Map<String, Object> resolvedArgs;
        try {
            // 将 ${message.image} 等替换为实际字节
            resolvedArgs = argumentResolver.resolve(toolCall.arguments(), msg);
        } catch (ArgumentResolver.PlaceholderResolutionException e) {
            // 占位符解析失败 → 构造失败结果
            ActResult failure = ActResult.failure(e.getMessage());
            // 将失败信息写入 ChatMemory，让 LLM 感知
            appendToMemory(memory, userId, toolName, failure);
            return failure;
        }

        // 2. 通过 ToolRegistry 调用真实工具
        ActResult result = toolRegistry.execute(toolName, resolvedArgs);

        // 3. 处理文件下发
        if (result.success() && result.data() instanceof Map<?, ?> dataMap) {
            deliverFileIfPresent(dataMap, userId, sender);
        }

        // 4. 结果回灌 ChatMemory
        appendToMemory(memory, userId, toolName, result);

        log.debug("[OUTER-LOOP] 工具完成 | userId={} | tool={} | success={} | suspend={}",
                userId, toolName, result.success(), result.suspend());

        return result;
    }

    /**
     * 检测工具结果中的文件数据并下发给用户。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void deliverFileIfPresent(Map<?, ?> dataMap, String userId, MessageSender sender) {
        Object fileBytes = dataMap.get("fileBytes");
        Object fileName = dataMap.get("fileName");
        if (fileName instanceof String name) {
            byte[] bytes = resolveFileBytes(fileBytes);
            if (bytes != null && bytes.length > 0) {
                log.info("[OUTER-LOOP] 下发文件 | userId={} | fileName={} | size={}bytes",
                        userId, name, bytes.length);
                sender.sendFile(userId, bytes, name, "生成的文档");
            }
        }
    }

    /**
     * 将工具执行结果作为 ToolResponseMessage 追加到 ChatMemory。
     */
    private void appendToMemory(ChatMemory memory, String userId, String toolName, ActResult result) {
        String resultText = buildResultText(result);
        var toolResponse = new ToolResponseMessage.ToolResponse(
                UUID.randomUUID().toString(), toolName, resultText);
        memory.add(userId, new ToolResponseMessage(List.of(toolResponse)));
    }

    /**
     * 将 ActResult 转为 LLM 可读的文本摘要。
     */
    private String buildResultText(ActResult result) {
        if (!result.success()) {
            return "ERROR: " + (result.errorMessage() != null ? result.errorMessage() : "unknown");
        }
        if (result.data() instanceof Map<?, ?> dataMap) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<String, Object> llmView = new LinkedHashMap<>((Map) dataMap);
            llmView.remove("fileBytes"); // 二进制数据不传给 LLM
            return llmView.toString();
        }
        return result.data() != null ? result.data().toString() : "success";
    }

    /**
     * 解析文件字节数据（兼容 byte[] 和 base64 字符串两种格式）。
     */
    private byte[] resolveFileBytes(Object raw) {
        if (raw instanceof byte[] bytes) return bytes;
        if (raw instanceof String base64) {
            try {
                return java.util.Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                log.warn("[OUTER-LOOP] fileBytes 不是有效的 base64");
                return null;
            }
        }
        return null;
    }
}
