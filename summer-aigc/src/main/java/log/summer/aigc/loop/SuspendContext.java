package log.summer.aigc.loop;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wraps the state needed to suspend and later resume an agent conversation.
 * <p>
 * When a tool needs user input before proceeding (e.g., confirming an outline),
 * the agent loop persists a {@code SuspendContext} as JSON into the
 * {@code conversation.suspend_context} column. On the next user message, the
 * context is deserialized and used to restore the conversation state.
 *
 * <h3>Serialization</h3>
 * This record is designed to be serialized/deserialized by Jackson.
 * Use {@code new ObjectMapper().writeValueAsString(context)} for storage and
 * {@code objectMapper.readValue(json, SuspendContext.class)} for retrieval.
 *
 * @param toolName         the tool that requested suspension
 * @param suspendReason    human-readable reason for suspension (shown to the user)
 * @param conversationId   the conversation's unique identifier
 * @param messagesSnapshot snapshots of the conversation messages at the suspension point
 * @param createdAt        timestamp when the suspension was created
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuspendContext(
        String toolName,
        String suspendReason,
        String conversationId,
        List<MessageSnapshot> messagesSnapshot,
        LocalDateTime createdAt
) {

    /**
     * Create a new {@code SuspendContext} with the current time as {@link #createdAt}.
     *
     * @param toolName         the tool that requested suspension
     * @param suspendReason    human-readable reason
     * @param conversationId   the conversation ID
     * @param messagesSnapshot serialized message snapshots (use
     *                         {@link MessageSnapshot#fromMessages(List)} to create)
     * @return a new SuspendContext
     */
    public static SuspendContext of(String toolName, String suspendReason, String conversationId,
                                     List<MessageSnapshot> messagesSnapshot) {
        return new SuspendContext(toolName, suspendReason, conversationId, messagesSnapshot, LocalDateTime.now());
    }
}
