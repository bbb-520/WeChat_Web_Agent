package log.summer.common.exception;

/**
 * 图片生成异常 —— 文生图/图片编辑链路中任一环节失败时抛出。
 * 保留原始异常链，便于排查是 AI 理解、API 调用还是下载失败。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class ImageGenerationException extends BotException {

    private final String stage;  // "ai-describe", "generate", "download"

    public ImageGenerationException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public ImageGenerationException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
