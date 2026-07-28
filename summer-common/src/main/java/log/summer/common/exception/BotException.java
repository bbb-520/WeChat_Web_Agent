package log.summer.common.exception;

/**
 * 机器人应用统一异常基类。
 * 所有业务异常需继承此类，保留完整异常链。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class BotException extends RuntimeException {

    public BotException(String message) {
        super(message);
    }

    public BotException(String message, Throwable cause) {
        super(message, cause);
    }
}
