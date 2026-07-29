package log.summer.common.exception;

/**
 * 语音合成异常 —— TTS 调用失败、音频格式不支持、返回空数据等。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class VoiceSynthesisException extends BotException {

    public VoiceSynthesisException(String message) {
        super(message);
    }

    public VoiceSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
