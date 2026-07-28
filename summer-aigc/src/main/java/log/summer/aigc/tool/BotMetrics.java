package log.summer.aigc.tool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 机器人业务指标埋点
 * @author bbb
 * @since 2026-07-20
 */
@Slf4j
@Component
public class BotMetrics {

    private final MeterRegistry registry;

    // ===== TTS =====
    private Counter ttsSuccess;
    private Counter ttsFailure;
    private Timer ttsDuration;

    // ===== 图片生成 =====
    private Counter imageGenSuccess;
    private Counter imageGenFailure;
    private Timer imageGenDuration;

    // ===== 消息 =====
    private Counter messagesProcessed;

    // ===== 缓存 =====
    private final AtomicInteger pendingImageCount = new AtomicInteger(0);

    // ===== 运行统计 =====
    private final Instant startTime = Instant.now();
    private Counter errors;

    public BotMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        this.ttsSuccess = Counter.builder("bot.tts.total")
                .description("TTS request count")
                .tag("result", "success")
                .register(registry);
        this.ttsFailure = Counter.builder("bot.tts.total")
                .description("TTS request count")
                .tag("result", "failure")
                .register(registry);
        this.ttsDuration = Timer.builder("bot.tts.duration")
                .description("TTS request duration distribution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.imageGenSuccess = Counter.builder("bot.image.gen.total")
                .description("Image generation request count")
                .tag("result", "success")
                .register(registry);
        this.imageGenFailure = Counter.builder("bot.image.gen.total")
                .description("Image generation request count")
                .tag("result", "failure")
                .register(registry);
        this.imageGenDuration = Timer.builder("bot.image.gen.duration")
                .description("Image generation duration distribution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.messagesProcessed = Counter.builder("bot.messages.total")
                .description("Total messages processed")
                .register(registry);

        this.errors = Counter.builder("bot.errors.total")
                .description("Total processing errors")
                .register(registry);

        // Gauge: 从 AtomicInteger 实时读取缓存条目数
        Gauge.builder("bot.cache.pending-images", pendingImageCount, AtomicInteger::get)
                .description("Current pending image cache entries")
                .register(registry);

        log.info("[METRICS] Micrometer 指标已注册 | endpoints=/actuator/metrics");
    }

    /** 记录一次 TTS 请求 */
    public void recordTts(long durationMs, boolean success) {
        if (success) {
            ttsSuccess.increment();
        } else {
            ttsFailure.increment();
        }
        ttsDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录一次图片生成请求 */
    public void recordImageGen(long durationMs, boolean success) {
        if (success) {
            imageGenSuccess.increment();
        } else {
            imageGenFailure.increment();
        }
        imageGenDuration.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录一条消息被处理 */
    public void recordMessage() {
        messagesProcessed.increment();
    }


    /** 记录一次处理错误 */
    public void recordError() {
        errors.increment();
    }

    /** 获取运行统计快照 */
    public Stats getStats() {
        long uptimeSeconds = Duration.between(startTime, Instant.now()).toSeconds();
        long hours = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        String uptime = String.format("%d时%d分%d秒", hours, minutes, seconds);
        return new Stats(uptime, (long) messagesProcessed.count(), (long) errors.count());
    }

    /** 设置缓存条目数（由 ImageCacheManager 调用） */
    public void setPendingImageCount(int count) {
        pendingImageCount.set(count);
    }

    /** 缓存条目 +1 */
    public void incrementPendingImageCount() {
        pendingImageCount.incrementAndGet();
    }

    /** 缓存条目 -1 */
    public void decrementPendingImageCount() {
        pendingImageCount.decrementAndGet();
    }
}
