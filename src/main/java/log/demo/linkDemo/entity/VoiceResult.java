package log.demo.linkDemo.entity;

/**
 * TTS 语音合成结果 —— 跨服务传输实体。
 *
 * @param wavAudio   WAV 格式音频字节
 * @param durationMs 音频时长（毫秒）
 * @param requestId  DashScope API 请求 ID（用于追踪）
 */
public record VoiceResult(byte[] wavAudio, int durationMs, String requestId) {

    public boolean hasAudio() {
        return wavAudio != null && wavAudio.length > 0;
    }
}
