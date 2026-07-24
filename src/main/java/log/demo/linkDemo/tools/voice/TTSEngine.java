package log.demo.linkDemo.tools.voice;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.enums.Timbre;
import log.demo.linkDemo.config.VoiceProperties;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import log.demo.linkDemo.tools.BotMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 语音管理器 —— TTS 合成 + 转码。
 *
 * @author bbb
 * @since 2026-07-20
 */
@Slf4j
@Component
public class TTSEngine {

    private final VoiceProperties props;
    private final BotMetrics metrics;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private SpeechSynthesizer synthesizer;
    private static final int MAX_TEXT_LENGTH = 1500;


    public TTSEngine(VoiceProperties props, BotMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        this.synthesizer = new SpeechSynthesizer();
        log.info("[VOICE-MGR] TTSEngine 初始化完成 | synthesizer={} | maxTextLength={}",
                synthesizer.getClass().getSimpleName(), MAX_TEXT_LENGTH);
    }

    // 公开 API

    /** 使用默认音色合成 */
    public VoiceResult synthesize(String text) {
        return synthesize(text, Timbre.defaultTimbre().getVoiceId());
    }

    /** 使用指定 voiceId 合成 */
    public VoiceResult synthesize(String text, String voiceId) {
        if (text == null || text.isBlank()) {
            throw new VoiceSynthesisException("合成文本不能为空");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            log.warn("[VOICE-MGR] 文本过长被截断 | original={} | max={}", text.length(), MAX_TEXT_LENGTH);
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        final String trimmed = text.trim();
        final SpeechSynthesisAudioFormat audioFormat = resolveFormat();

        Timbre.byVoiceId(voiceId).ifPresent(t ->
                log.info("[VOICE-MGR] 开始 TTS | voice={}({}) | textLen={} | preview=\"{}\"",
                        t.getDisplayName(), voiceId, trimmed.length(),
                        trimmed.length() > 50 ? trimmed.substring(0, 50) + "..." : trimmed));

        final long t0 = System.currentTimeMillis();
        final SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .apiKey(apiKey)
                .model(props.getModel())
                .text(trimmed)
                .sampleRate(props.getSampleRate())
                .format(audioFormat)
                .parameter("voice", voiceId)
                .build();

        return doSynthesize(param, audioFormat, t0);
    }

    private VoiceResult doSynthesize(SpeechSynthesisParam param,
                                      SpeechSynthesisAudioFormat fmt, long t0) {
        try {
            ByteBuffer audioBuffer = synthesizer.call(param);
            long elapsed = System.currentTimeMillis() - t0;
            String requestId = getRequestId();

            if (audioBuffer == null || !audioBuffer.hasRemaining()) {
                metrics.recordTts(elapsed, false);
                throw new VoiceSynthesisException(
                        String.format("语音合成返回空数据 (requestId=%s, elapsed=%dms)", requestId, elapsed));
            }

            byte[] audioBytes = new byte[audioBuffer.remaining()];
            audioBuffer.get(audioBytes);

            final byte[] wavBytes;
            final int durationMs;
            if (fmt == SpeechSynthesisAudioFormat.WAV) {
                wavBytes = audioBytes;
                durationMs = AudioTranscoder.calculateDurationFromWav(
                        audioBytes.length, props.getSampleRate(), 16, 1);
            } else {
                wavBytes = AudioTranscoder.pcmToWav(audioBytes, props.getSampleRate(), 16, 1);
                durationMs = AudioTranscoder.calculateDurationMs(
                        audioBytes.length, props.getSampleRate(), 16, 1);
            }

            metrics.recordTts(elapsed, true);
            log.info("[VOICE-MGR] ✓ TTS 合成完成 | rawSize={} | wavSize={} | duration={}ms | elapsed={}ms | requestId={}",
                    audioBytes.length, wavBytes.length, durationMs, elapsed, requestId);

            return new VoiceResult(wavBytes, durationMs, requestId);
        } catch (VoiceSynthesisException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            metrics.recordTts(elapsed, false);
            log.error("[VOICE-MGR] ✗ TTS 合成失败 | elapsed={}ms | error={}",
                    elapsed, e.getMessage(), e);
            throw new VoiceSynthesisException(
                    String.format("语音合成失败: %s", e.getMessage()), e);
        }
    }

    private SpeechSynthesisAudioFormat resolveFormat() {
        try {
            return SpeechSynthesisAudioFormat.valueOf(props.getFormat().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new VoiceSynthesisException("不支持的音频格式: " + props.getFormat(), e);
        }
    }

    private String getRequestId() {
        try {
            String id = synthesizer.getLastRequestId();
            return id != null ? id : "N/A";
        } catch (Exception ignored) {
            return "N/A";
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[VOICE-MGR] TTSEngine 关闭 | releasing synthesizer");
        this.synthesizer = null;
    }

}
