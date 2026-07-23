package log.demo.linkDemo.exception;

/**
 * 文件识别异常 —— 文件类型检测、内容提取或 AI 分析失败时抛出。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class FileRecognitionException extends BotException {

    private final String stage;  // "detect", "extract", "analyze"

    public FileRecognitionException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    public FileRecognitionException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public String getStage() {
        return stage;
    }
}
