package log.summer.aigc.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 机器人业务配置属性。
 * 意图识别已迁移至 IntentClassifier（qwen-turbo LLM），不再使用正则。
 *
 * @author bbb
 * @since 2026-07-20
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    private Intent intent = new Intent();
    private Cache cache = new Cache();
    private FileConfig file = new FileConfig();
    private WeatherConfig weather = new WeatherConfig();

    /** 意图配置 —— 仅保留图片编辑系统提示词，意图分类由 IntentClassifier 处理 */
    @Data
    public static class Intent {
        /** 图片编辑 AI 系统提示词 */
        private String imageEditSystemPrompt;
    }

    @Data
    public static class Cache {
        //过期时间
        //用户上传图片后，这张图片最多在内存里保留 5 分钟。超过 5 分钟未收到编辑指令，图片自动失效
        private int pendingImageTtlMinutes = 5;

        //最大容量
        //整个服务最多同时缓存 50 张待编辑图片。
        private int maxPendingImages = 50;

        //清理间隔
        //后台定时任务每隔 2 分钟扫描一次缓存，物理删除所有已过期的条目。
        private int cleanupIntervalMinutes = 2;

        //图片上下文 TTL（分钟），DashScope CDN 约 1 小时有效
        private int imageContextTtlMinutes = 30;

        //图片上下文最大条目数
        private int maxImageContextEntries = 200;
    }

    @Data
    public static class FileConfig {
        /** 文件大小上限（MB），超过则拒绝处理 */
        private int maxSizeMb = 20;
        /** 文件分析系统提示词路径 */
        private String systemPromptPath = "classpath:prompts/file-system.txt";
    }

    @Data
    public static class WeatherConfig {
        /** 高德开放平台 API Key（Web服务） */
        private String apiKey;
        /** 天气查询 API 地址 */
        private String baseUrl = "https://restapi.amap.com/v3/weather/weatherInfo";
    }

    /**
     * 启动时校验关键配置并设置合理下限，避免非正数导致缓存永不淘汰或无限增长。
     *
     * 宽松绑定
     */
    @PostConstruct
    public void validateAndLog() {
        // ── 缓存配置校验 ──
        if (cache.pendingImageTtlMinutes < 1) {
            log.warn("[BOT-CONFIG] pendingImageTtlMinutes={} < 1，已调整为最小值 1 分钟",
                    cache.pendingImageTtlMinutes);
            cache.pendingImageTtlMinutes = 1;
        }
        if (cache.maxPendingImages < 1) {
            log.warn("[BOT-CONFIG] maxPendingImages={} < 1，已调整为最小值 1",
                    cache.maxPendingImages);
            cache.maxPendingImages = 1;
        }
        if (cache.cleanupIntervalMinutes < 1) {
            log.warn("[BOT-CONFIG] cleanupIntervalMinutes={} < 1，已调整为最小值 1 分钟",
                    cache.cleanupIntervalMinutes);
            cache.cleanupIntervalMinutes = 1;
        }

        // ── 文件配置校验 ──
        if (file.maxSizeMb < 1) {
            log.warn("[BOT-CONFIG] maxSizeMb={} < 1，已调整为最小值 1 MB", file.maxSizeMb);
            file.maxSizeMb = 1;
        }

        log.info("[BOT-CONFIG] 业务配置已加载（意图分类由 IntentClassifier qwen-turbo 处理）| "
                        + "pendingImageTtl={}min | maxPendingImages={} | cleanupInterval={}min | maxFileSize={}MB | "
                        + "weatherApiKey={}",
                cache.pendingImageTtlMinutes, cache.maxPendingImages, cache.cleanupIntervalMinutes,
                file.maxSizeMb,
                weather.apiKey != null && !weather.apiKey.isBlank() ? "***" : "NULL");
    }
}
