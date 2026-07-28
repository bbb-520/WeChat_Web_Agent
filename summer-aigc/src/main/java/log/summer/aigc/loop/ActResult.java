package log.summer.aigc.loop;

/**
 * Encapsulates the result of a tool execution.
 * Every @Tool method MUST return this type.
 *
 * @param success      true if the tool completed successfully
 * @param data         the tool's output data on success (nullable)
 * @param errorMessage human-readable error description on failure (nullable)
 */
public record ActResult(boolean success, Object data, String errorMessage) {

    public static ActResult success(Object data) {
        return new ActResult(true, data, null);
    }

    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage);
    }
}
