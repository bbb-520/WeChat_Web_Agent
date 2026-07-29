package log.summer.aigc.loop;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.chat.messages.*;

import java.util.*;

/**
 * Lightweight DTO for serializing Spring AI {@link Message} objects to/from JSON.
 * <p>
 * Spring AI {@code Message} implementations are NOT directly Jackson-serializable.
 * This record bridges the gap so that conversation state can be persisted — for
 * example, serialized into the {@code conversation.suspend_context} JSON column.
 *
 * <h3>Message types handled</h3>
 * <ul>
 *   <li>{@link UserMessage} → type {@code USER}, text, metadata</li>
 *   <li>{@link AssistantMessage} → type {@code ASSISTANT}, text, metadata, toolCalls</li>
 *   <li>{@link SystemMessage} → type {@code SYSTEM}, text, metadata</li>
 *   <li>{@link ToolResponseMessage} → type {@code TOOL}, text, metadata, toolResponses</li>
 * </ul>
 *
 * @param messageType    the Spring AI message type ({@link MessageType})
 * @param text           the message text content
 * @param metadata       message metadata (nullable; empty map stored as null)
 * @param toolCalls      tool calls for {@code AssistantMessage} (nullable)
 * @param toolResponses  tool responses for {@code ToolResponseMessage} (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageSnapshot(
        MessageType messageType,
        String text,
        Map<String, Object> metadata,
        List<ToolCallSnapshot> toolCalls,
        List<ToolResponseSnapshot> toolResponses
) {

    /**
     * Serializable snapshot of an {@link AssistantMessage.ToolCall}.
     *
     * @param id        the tool call identifier
     * @param type      the tool call type (typically {@code "function"})
     * @param name      the invoked tool name
     * @param arguments JSON string of tool arguments
     */
    public record ToolCallSnapshot(String id, String type, String name, String arguments) {}

    /**
     * Serializable snapshot of a {@link ToolResponseMessage.ToolResponse}.
     *
     * @param id           the tool call identifier this response is for
     * @param name         the tool name
     * @param responseData the result payload
     */
    public record ToolResponseSnapshot(String id, String name, String responseData) {}

    // ──────────────────────────────────────────────────────────────
    //  Factory: Spring AI Message → MessageSnapshot
    // ──────────────────────────────────────────────────────────────

    /**
     * Convert a single Spring AI {@link Message} into a {@link MessageSnapshot}.
     *
     * @param message the Spring AI message (must not be null)
     * @return a new snapshot
     */
    public static MessageSnapshot from(Message message) {
        Objects.requireNonNull(message, "message must not be null");

        List<ToolCallSnapshot> toolCalls = null;
        List<ToolResponseSnapshot> toolResponses = null;

        if (message instanceof AssistantMessage am) {
            if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                toolCalls = am.getToolCalls().stream()
                        .map(tc -> new ToolCallSnapshot(tc.id(), tc.type(), tc.name(), tc.arguments()))
                        .toList();
            }
        } else if (message instanceof ToolResponseMessage trm) {
            if (trm.getResponses() != null && !trm.getResponses().isEmpty()) {
                toolResponses = trm.getResponses().stream()
                        .map(tr -> new ToolResponseSnapshot(tr.id(), tr.name(), tr.responseData()))
                        .toList();
            }
        }

        Map<String, Object> meta = (message.getMetadata() != null && !message.getMetadata().isEmpty())
                ? new HashMap<>(message.getMetadata())
                : null;

        return new MessageSnapshot(
                message.getMessageType(),
                message.getText(),
                meta,
                toolCalls,
                toolResponses
        );
    }

    /**
     * Convert a list of Spring AI {@link Message}s into a list of {@link MessageSnapshot}s.
     *
     * @param messages the Spring AI messages (null-safe)
     * @return an immutable list of snapshots (empty if input is null or empty)
     */
    public static List<MessageSnapshot> fromMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        return messages.stream()
                .map(MessageSnapshot::from)
                .toList();
    }

    // ──────────────────────────────────────────────────────────────
    //  Reconstruct: MessageSnapshot → Spring AI Message
    // ──────────────────────────────────────────────────────────────

    /**
     * Reconstruct a Spring AI {@link Message} from this snapshot.
     * <p>
     * The returned instance will be one of {@link UserMessage},
     * {@link AssistantMessage}, {@link SystemMessage}, or
     * {@link ToolResponseMessage} depending on {@link #messageType()}.
     *
     * @return a new Spring AI message instance (never null)
     */
    public Message toSpringAiMessage() {
        return switch (messageType) {
            case USER -> new UserMessage(text != null ? text : "");
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> calls = Collections.emptyList();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    calls = toolCalls.stream()
                            .map(tc -> new AssistantMessage.ToolCall(
                                    tc.id(),
                                    tc.type() != null ? tc.type() : "function",
                                    tc.name(),
                                    tc.arguments()
                            ))
                            .toList();
                }
                yield new AssistantMessage(
                        text != null ? text : "",
                        metadata != null ? metadata : Map.of(),
                        calls
                );
            }
            case SYSTEM -> new SystemMessage(text != null ? text : "");
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = Collections.emptyList();
                if (toolResponses != null && !toolResponses.isEmpty()) {
                    responses = toolResponses.stream()
                            .map(tr -> new ToolResponseMessage.ToolResponse(
                                    tr.id(),
                                    tr.name(),
                                    tr.responseData()
                            ))
                            .toList();
                }
                yield new ToolResponseMessage(
                        responses,
                        metadata != null ? metadata : Map.of()
                );
            }
        };
    }
}
