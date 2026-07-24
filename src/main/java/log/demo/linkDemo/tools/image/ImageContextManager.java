package log.demo.linkDemo.tools.image;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 图片上下文缓存 —— 按用户维护"上一张生成/编辑的图片"。
 * 将上一张图的 DashScope CDN URL 作为 refImage 传入，实现高度一致的迭代编辑。
 *
 * @author bbb
 * @since 2026-07-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageContextManager {

    private final BotProperties props;

    private long entryTtlMs;
    private int maxEntries;
    private int cleanupIntervalMinutes;

    private final Map<String, TimedEntry<String>> lastImageUrl = new ConcurrentHashMap<>();
    private final Map<String, TimedEntry<byte[]>> lastImageBytes = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init() {
        this.entryTtlMs = TimeUnit.MINUTES.toMillis(
                Math.max(1, props.getCache().getImageContextTtlMinutes()));
        this.maxEntries = Math.max(1, props.getCache().getMaxImageContextEntries());
        this.cleanupIntervalMinutes = Math.max(1, props.getCache().getCleanupIntervalMinutes());

        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "img-ctx-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::evictExpired,
                cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);
        log.info("[IMG-CTX] ImageContextManager 初始化 | ttl={}min | maxEntries={} | cleanupInterval={}min",
                entryTtlMs / 60000, maxEntries, cleanupIntervalMinutes);
    }

    public void save(String userId, String imageUrl, byte[] imageBytes) {
        if (userId == null) return;

        evictIfNeeded(lastImageUrl, maxEntries);
        evictIfNeeded(lastImageBytes, maxEntries);

        if (imageUrl != null && !imageUrl.isBlank()) {
            lastImageUrl.put(userId, new TimedEntry<>(imageUrl, entryTtlMs));
        }
        if (imageBytes != null && imageBytes.length > 0) {
            lastImageBytes.put(userId, new TimedEntry<>(imageBytes, entryTtlMs));
        }
        log.info("[IMG-CTX] 保存图片上下文 | userId={} | url={} | bytes={}",
                userId,
                imageUrl != null ? imageUrl.substring(0, Math.min(60, imageUrl.length())) + "..." : "null",
                imageBytes != null ? imageBytes.length + "bytes" : "null");
    }

    public Optional<String> getLastUrl(String userId) {
        if (userId == null) return Optional.empty();
        TimedEntry<String> entry = lastImageUrl.get(userId);
        if (entry == null) return Optional.empty();
        if (entry.isExpired()) {
            lastImageUrl.remove(userId);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.data);
    }

    public Optional<byte[]> getLastBytes(String userId) {
        if (userId == null) return Optional.empty();
        TimedEntry<byte[]> entry = lastImageBytes.get(userId);
        if (entry == null) return Optional.empty();
        if (entry.isExpired()) {
            lastImageBytes.remove(userId);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.data);
    }

    public Optional<String> getLastRefImage(String userId) {
        String url = getLastUrl(userId).orElse(null);
        if (url != null && !url.isBlank()) {
            return Optional.of(url);
        }
        return Optional.empty();
    }

    public void clear(String userId) {
        if (userId == null) return;
        lastImageUrl.remove(userId);
        lastImageBytes.remove(userId);
        log.info("[IMG-CTX] 清除图片上下文 | userId={}", userId);
    }

    void evictExpired() {
        int removed = evictExpiredFrom(lastImageUrl);
        removed += evictExpiredFrom(lastImageBytes);
        if (removed > 0) {
            log.info("[IMG-CTX] 淘汰 {} 条过期图片上下文 | urlEntries={} | bytesEntries={}",
                    removed, lastImageUrl.size(), lastImageBytes.size());
        }
    }

    private <T> int evictExpiredFrom(Map<String, TimedEntry<T>> map) {
        int removed = 0;
        Iterator<Map.Entry<String, TimedEntry<T>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TimedEntry<T>> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    private <T> void evictIfNeeded(Map<String, TimedEntry<T>> map, int max) {
        while (map.size() >= max) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, TimedEntry<T>> entry : map.entrySet()) {
                if (entry.getValue().createdAt < oldestTime) {
                    oldestTime = entry.getValue().createdAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                map.remove(oldestKey);
            } else {
                break;
            }
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[IMG-CTX] ImageContextManager 关闭 | urlEntries={} | bytesEntries={}",
                lastImageUrl.size(), lastImageBytes.size());
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        lastImageUrl.clear();
        lastImageBytes.clear();
    }

    private static class TimedEntry<T> {
        final T data;
        final long createdAt;
        final long ttlMs;

        TimedEntry(T data, long ttlMs) {
            this.data = data;
            this.createdAt = System.currentTimeMillis();
            this.ttlMs = ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > ttlMs;
        }
    }
}
