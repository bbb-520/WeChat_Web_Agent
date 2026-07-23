package log.demo.linkDemo.exception;

/**
 * AI 服务调用异常 —— 多模态分析、文本对话、文档分析等 AI 接口失败时抛出。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class AIServiceException extends BotException {

    private final String operation;  // "chat", "analyzeImage", "analyzeDocument", etc.

    public AIServiceException(String operation, String message) {
        super(message);
        this.operation = operation;
    }

    public AIServiceException(String operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}
