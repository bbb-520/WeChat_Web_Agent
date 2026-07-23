package log.demo.linkDemo.agent.voice;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.enums.Timbre;
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
 * 音色会话管理器 —— 按用户维护当前音色。
 *
 * <p>同一会话内切换后保持生效，直至再次切换或会话过期清除。</p>
 * <p>内置 TTL 淘汰：用户音色状态 2 小时无活动自动清除。</p>
 *
 * @author bbb
 * @since 2026-07-21
 */
@Slf4j
@Component
public class TimbreSession {

    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000;
    private static final int CLEANUP_INTERVAL_MINUTES = 10;

    private final Map<String, TimedEntry<String>> userVoiceMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void init() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "timbre-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::evictExpired,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("[TIMBRE] TimbreSession 初始化 | sessionTtl={}h | cleanupInterval={}min",
                SESSION_TTL_MS / 3600000, CLEANUP_INTERVAL_MINUTES);
    }

    public Timbre switchTo(String userId, Timbre timbre) {
        userVoiceMap.put(userId, new TimedEntry<>(timbre.getVoiceId(), SESSION_TTL_MS));
        log.info("[TIMBRE] 音色切换 | userId={} | timbre={}({})", userId, timbre.getDisplayName(), timbre.getVoiceId());
        return timbre;
    }

    public String getCurrentVoiceId(String userId) {
        TimedEntry<String> entry = userVoiceMap.get(userId);
        if (entry != null && !entry.isExpired()) return entry.data;
        return Timbre.defaultTimbre().getVoiceId();
    }

    public Optional<Timbre> getCurrentTimbre(String userId) {
        TimedEntry<String> entry = userVoiceMap.get(userId);
        if (entry == null || entry.isExpired()) return Optional.empty();
        return Timbre.byVoiceId(entry.data);
    }

    public String getStatusText(String userId) {
        Timbre t = getCurrentTimbre(userId).orElse(Timbre.defaultTimbre());
        return t.getDisplayName() + " —— " + t.getDescription();
    }

    public void clear(String userId) {
        userVoiceMap.remove(userId);
    }

    void evictExpired() {
        int removed = evictExpiredFrom(userVoiceMap);
        if (removed > 0) {
            log.info("[TIMBRE] 淘汰 {} 条过期音色会话 | remaining={}", removed, userVoiceMap.size());
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

    @PreDestroy
    public void destroy() {
        log.info("[TIMBRE] TimbreSession 关闭 | userSessions={}", userVoiceMap.size());
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
        userVoiceMap.clear();
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
