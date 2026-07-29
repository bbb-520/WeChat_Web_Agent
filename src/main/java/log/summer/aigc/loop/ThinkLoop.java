package log.summer.aigc.loop;

import log.summer.aigc.service.ChatService;
import log.summer.aigc.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 内循环（思考与决策）—— 纯 LLM 交互。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>从 ChatMemory 获取当前对话上下文</li>
 *   <li>将消息 + 工具清单发给 LLM（qwen-plus）</li>
 *   <li>解析 LLM 返回的 {@link ThinkResult}：最终答案 or 工具调用</li>
 * </ol>
 *
 * <p>不负责：工具执行、记忆管理、消息发送 — 这些由外循环和编排器处理。</p>
 *
 * @author bbb
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThinkLoop {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;

    /**
     * 执行一轮思考。
     *
     * @param userId 当前用户 ID（用于从 ChatMemory 获取上下文）
     * @param memory 当前会话的 ChatMemory
     * @return ThinkResult —— 包含最终答案文本和/或工具调用列表
     */
    public ThinkResult think(String userId, ChatMemory memory) {

        //从ChatMemory取出历史消息
        List<Message> messages = new ArrayList<>(memory.get(userId));
        //从ToolRegistry获取可用工具列表
        List<ToolCallback> tools = toolRegistry.getCallbacks();

        log.debug("[INNER-LOOP] 思考轮 | userId={} | messages={} | tools={}",
                userId, messages.size(), tools.size());

        //请求大模型推理
        var chatResponse = chatService.chatWithTools(messages, tools);
        ThinkResult result = ThinkResult.fromChatResponse(chatResponse);

        log.debug("[INNER-LOOP] 思考结果 | userId={} | hasAnswer={} | hasToolCalls={} | tools={}",
                userId, result.hasFinalAnswer(), result.hasToolCalls(),
                result.hasToolCalls() ? result.toolCalls().stream().map(ThinkResult.ToolCall::name).toList() : "[]");

        return result;
    }
}
