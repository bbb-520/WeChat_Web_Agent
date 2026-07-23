package log.demo.linkDemo.agent.image;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.config.BotProperties;
import log.demo.linkDemo.agent.BotMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 暂存用户上传的待编辑图片的内存缓存管理器。
 *
 * <h3>会话隔离（v2.0）</h3>
 * <p>每个编辑流程分配唯一 {@code sessionId}，缓存以 {@code userId + ":" + sessionId}
 * 为复合键。同一用户并发上传多张图片时互不覆盖，各自对应独立的编辑会话。</p>
 *
 * @author bbb
 * @since 2026-07-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCacheManager {

    private final BotProperties props;
    private final BotMetrics metrics;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeSession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init() {
        int interval = Math.max(1, props.getCache().getCleanupIntervalMinutes());
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "img-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::evictExpired, interval, interval, TimeUnit.MINUTES);
        log.info("[CACHE] ImageCacheManager 初始化(v2-session) | ttl={}min | maxEntries={} | cleanupInterval={}min",
                props.getCache().getPendingImageTtlMinutes(),
                props.getCache().getMaxPendingImages(),
                interval);
    }

    public String newSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String compoundKey(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    public String put(String userId, byte[] imageBytes) {
        if (userId == null || imageBytes == null) return null;

        String sessionId = newSessionId();
        String key = compoundKey(userId, sessionId);

        int max = props.getCache().getMaxPendingImages();
        while (cache.size() >= max) {
            evictOldest();
        }

        long expiry = System.currentTimeMillis()
                + TimeUnit.MINUTES.toMillis(props.getCache().getPendingImageTtlMinutes());
        cache.put(key, new CacheEntry(imageBytes, expiry));
        activeSession.put(userId, sessionId);
        metrics.incrementPendingImageCount();
        log.debug("[CACHE] 存入图片 | userId={} | sessionId={} | size={}bytes | totalEntries={} | ttl={}",
                userId, sessionId, imageBytes.length, cache.size(), expiry);
        return sessionId;
    }

    public String getActiveSessionId(String userId) {
        return activeSession.get(userId);
    }

    public boolean containsKey(String userId) {
        String sessionId = activeSession.get(userId);
        if (sessionId == null) return false;
        return containsKey(userId, sessionId);
    }

    public boolean containsKey(String userId, String sessionId) {
        CacheEntry entry = cache.get(compoundKey(userId, sessionId));
        if (entry == null) return false;
        if (entry.isExpired()) {
            remove(userId, sessionId);
            return false;
        }
        return true;
    }

    public byte[] peek(String userId) {
        String sessionId = activeSession.get(userId);
        if (sessionId == null) return null;
        return peek(userId, sessionId);
    }

    public byte[] peek(String userId, String sessionId) {
        CacheEntry entry = cache.get(compoundKey(userId, sessionId));
        if (entry == null) return null;
        if (entry.isExpired()) {
            remove(userId, sessionId);
            return null;
        }
        return entry.imageData;
    }

    public byte[] remove(String userId) {
        String sessionId = activeSession.remove(userId);
        if (sessionId == null) return null;
        return remove(userId, sessionId);
    }

    public byte[] remove(String userId, String sessionId) {
        CacheEntry entry = cache.remove(compoundKey(userId, sessionId));
        if (entry == null) return null;
        metrics.decrementPendingImageCount();
        if (entry.isExpired()) {
            log.debug("[CACHE] 读取时发现过期条目 | userId={} | sessionId={}", userId, sessionId);
            return null;
        }
        log.debug("[CACHE] 取出图片 | userId={} | sessionId={} | size={}bytes | remainingEntries={}",
                userId, sessionId, entry.imageData.length, cache.size());
        return entry.imageData;
    }

    public void removeSilently(String userId) {
        String sessionId = activeSession.remove(userId);
        if (sessionId != null) {
            removeSilently(userId, sessionId);
        }
    }

    public void removeSilently(String userId, String sessionId) {
        CacheEntry removed = cache.remove(compoundKey(userId, sessionId));
        if (removed != null) {
            metrics.decrementPendingImageCount();
            log.debug("[CACHE] 删除图片 | userId={} | sessionId={} | remainingEntries={}",
                    userId, sessionId, cache.size());
        }
    }

    public int size() {
        return cache.size();
    }

    public void lock(String userId) {
        ReentrantLock lock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        lock.lock();
    }

    public void unlock(String userId) {
        ReentrantLock lock = userLocks.get(userId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    void evictExpired() {
        int removed = 0;
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                metrics.decrementPendingImageCount();
                String key = entry.getKey();
                int colonIdx = key.indexOf(':');
                if (colonIdx > 0) {
                    String userId = key.substring(0, colonIdx);
                    String sessionId = key.substring(colonIdx + 1);
                    activeSession.remove(userId, sessionId);
                }
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[CACHE] 淘汰 {} 条过期图片 | remainingEntries={}", removed, cache.size());
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().createdAt < oldestTime) {
                oldestTime = entry.getValue().createdAt;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
            metrics.decrementPendingImageCount();
            int colonIdx = oldestKey.indexOf(':');
            if (colonIdx > 0) {
                String userId = oldestKey.substring(0, colonIdx);
                String sessionId = oldestKey.substring(colonIdx + 1);
                activeSession.remove(userId, sessionId);
            }
            log.debug("[CACHE] 容量保护淘汰 | key={}", oldestKey);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("[CACHE] ImageCacheManager 关闭 | finalEntries={}", cache.size());
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
        cache.clear();
        activeSession.clear();
        userLocks.clear();
    }

    private static class CacheEntry {
        final byte[] imageData;
        final long expiryTime;
        final long createdAt;

        CacheEntry(byte[] imageData, long expiryTime) {
            this.imageData = imageData;
            this.expiryTime = expiryTime;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
