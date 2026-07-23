package log.demo.linkDemo.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音合成（TTS）配置属性
 *
 * @author bbb
 * @since 2026-07-20
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "spring.ai.dashscope.voice.tts")
public class VoiceProperties {

    // 语音合成模型
    private String model = "cosyvoice-v1";

    // 音色
    private String voice = "longxiaochun";

    // 采样率
    // 指定音频的采样频率（Hz），即每秒采集多少个声音样本点
    private int sampleRate = 16000;

    // 输出音频格式
    private String format = "wav";

    /**
     * 配置加载后校验并设置合理下限，防止无效值导致运行时错误。
     */
    @PostConstruct
    public void validateAndLog() {
        // ── 采样率校验 ──
        if (sampleRate < 8000) {
            log.warn("[TTS-CONFIG] sampleRate={} < 8000，已调整为最小值 8000 Hz", sampleRate);
            sampleRate = 8000;
        }
        if (sampleRate > 48000) {
            log.warn("[TTS-CONFIG] sampleRate={} > 48000，已调整为最大值 48000 Hz", sampleRate);
            sampleRate = 48000;
        }

        // ── 模型名非空校验 ──
        if (model == null || model.isBlank()) {
            log.warn("[TTS-CONFIG] model 为空，已调整为默认值 cosyvoice-v1");
            model = "cosyvoice-v1";
        }

        // ── 格式标准化 ──
        if (format == null || format.isBlank()) {
            log.warn("[TTS-CONFIG] format 为空，已调整为默认值 wav");
            format = "wav";
        } else {
            format = format.toLowerCase();
        }

        // ── 音色非空校验 ──
        if (voice == null || voice.isBlank()) {
            log.warn("[TTS-CONFIG] voice 为空，已调整为默认值 longxiaochun");
            voice = "longxiaochun";
        }

        log.info("[TTS-CONFIG] 语音配置已校验 | model={} | voice={} | sampleRate={} | format={}",
                model, voice, sampleRate, format);
    }
}