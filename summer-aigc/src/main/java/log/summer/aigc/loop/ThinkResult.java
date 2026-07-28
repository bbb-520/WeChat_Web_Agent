package log.summer.aigc.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.*;

/**
 * Encapsulates a single round of LLM thinking.
 * Parsed from Spring AI's {@link ChatResponse}.
 *
 * @param finalAnswer the text response to send to the user (nullable)
 * @param toolCalls   tools the LLM wants to invoke (nullable, empty list if none)
 */
public record ThinkResult(String finalAnswer, List<ToolCall> toolCalls) {

    public record ToolCall(String name, Map<String, Object> arguments) {}

    public boolean hasFinalAnswer() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Parse Spring AI ChatResponse into ThinkResult.
     * Extracts both text content and any function calls from the first generation.
     */
    public static ThinkResult fromChatResponse(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return new ThinkResult(null, List.of());
        }

        Generation gen = response.getResults().get(0);
        String text = gen.getOutput().getText();

        List<ToolCall> calls = new ArrayList<>();
        // Extract function calls from Spring AI's assistant message
        var assistantMsg = gen.getOutput();
        if (assistantMsg != null && assistantMsg.getToolCalls() != null) {
            for (var tc : assistantMsg.getToolCalls()) {
                Map<String, Object> args = parseArguments(tc.arguments());
                calls.add(new ToolCall(tc.name(), args));
            }
        }

        return new ThinkResult(
                (text != null && !text.isBlank()) ? text : null,
                calls
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
