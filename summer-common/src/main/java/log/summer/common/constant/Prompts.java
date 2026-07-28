package log.summer.common.constant;

/**
 * Prompt template key constants.
 * Actual template files live in summer-common/src/main/resources/prompts/.
 */
public final class Prompts {
    private Prompts() {}

    /** System prompt for the main chat model (qwen-plus) */
    public static final String SYSTEM_PROMPT = "system";

    /** System prompt for file analysis */
    public static final String FILE_SYSTEM_PROMPT = "file-system";
}
