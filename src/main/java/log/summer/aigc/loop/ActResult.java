package log.summer.aigc.loop;

/**
 * Encapsulates the result of a tool execution.
 * Every @Tool method MUST return this type.
 *
 * <h3>Suspend protocol</h3>
 * When a tool needs user input before continuing (e.g., outline confirmation),
 * it returns {@link #suspend(Object, String)}. AgentLoop detects the suspend
 * flag, persists the session state, and waits for the next user message.
 *
 * @param success               true if the tool completed successfully
 * @param data                  the tool's output data on success (nullable)
 * @param errorMessage          human-readable error description on failure (nullable)
 * @param suspend               true if the loop should pause and wait for user input
 * @param suspendReason         human-readable reason for suspension (shown to user)
 * @param requiresConfirmation  true if the tool needs explicit user yes/no/modify before proceeding
 */
public record ActResult(
        boolean success,
        Object data,
        String errorMessage,
        boolean suspend,
        String suspendReason,
        boolean requiresConfirmation) {

    /** Normal successful completion — loop continues. */
    public static ActResult success(Object data) {
        return new ActResult(true, data, null, false, null, false);
    }

    /** Tool execution failed — loop continues, error fed back to LLM. */
    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage, false, null, false);
    }

    /**
     * Suspend the loop, presenting data to the user and waiting for their response.
     * The loop persists state to DB (survives restart) and exits without clearing ChatMemory.
     */
    public static ActResult suspend(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }

    /**
     * Alias for {@link #suspend(Object, String)} — semantically clearer
     * when the sole purpose is confirmation rather than data presentation.
     */
    public static ActResult confirmRequired(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }
}
