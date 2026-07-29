package log.summer.aigc.context;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-request context for tool execution.
 *
 * <h3>Problem this solves</h3>
 * Spring AI's {@code @Tool}-annotated methods cannot easily receive the current
 * request's {@code userId} and {@code conversationId} because the LLM does not
 * know those values and we cannot expose them as tool parameters without
 * confusing the model (it would invent fake values, leaving real users without
 * a way to receive generated images, voice, or documents).
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>{@link AgentLoop} sets the real userId/conversationId into this
 *       holder <b>before</b> each tool call and clears it <b>after</b>.</li>
 *   <li>Tools read the values via {@link #getUserId()} / {@link #getConversationId()}.</li>
 *   <li>ThreadLocal is used because tools run on the same virtual thread
 *       initiated by the inbound message (one AgentLoop invocation per
 *       message, processed sequentially through the think-act loop).</li>
 *   <li>Defensive: every accessor returns a safe default if unset, so tools
 *       that are accidentally invoked outside AgentLoop (e.g. unit tests)
 *       still work.</li>
 * </ul>
 *
 * <h3>Why not pass through tool arguments</h3>
 * We considered auto-injecting {@code userId}/{@code conversationId} into
 * the tool's argument map, but the Spring AI {@code MethodToolCallback} will
 * then advertise them to the LLM as required parameters, which it cannot
 * fill correctly. Hiding them behind a holder is much cleaner.
 *
 * @author bbb
 * @since 2026-07-29
 */
@Slf4j
public final class UserContextHolder {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> CONVERSATION_ID = new ThreadLocal<>();

    private UserContextHolder() {
        // utility class
    }

    /**
     * Set the current userId. Called by {@code AgentLoop} before tool execution.
     */
    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    /**
     * Set the current conversationId. May be {@code null} if the user has
     * never sent a message in this session (no DB row yet).
     */
    public static void setConversationId(Long conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    /**
     * Get the current userId. Returns {@code "anonymous"} if unset
     * (better than NPE, and impossible to confuse with a real userId in logs).
     */
    public static String getUserId() {
        String uid = USER_ID.get();
        return (uid == null || uid.isBlank()) ? "anonymous" : uid;
    }

    /**
     * Get the current conversationId, or {@code 0L} if unset.
     * Tools that require a real conversationId should check
     * {@link #hasConversationId()} and fail gracefully.
     */
    public static Long getConversationId() {
        Long cid = CONVERSATION_ID.get();
        return cid != null ? cid : 0L;
    }

    public static boolean hasConversationId() {
        Long cid = CONVERSATION_ID.get();
        return cid != null && cid > 0;
    }

    /**
     * Clear both values. MUST be called in the finally block of AgentLoop
     * to avoid leaking user identity across requests on pooled threads.
     */
    public static void clear() {
        USER_ID.remove();
        CONVERSATION_ID.remove();
    }
}
