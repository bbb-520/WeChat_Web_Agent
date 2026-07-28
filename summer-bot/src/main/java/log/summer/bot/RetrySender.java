package log.summer.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Exponential-backoff retry utility for media sends (image/file).
 * Falls back to a text notification on final failure.
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
public class RetrySender {

    private static final int MAX_RETRIES = 2; // 3 total attempts (0, 1, 2)

    /**
     * Execute a send action with retry on failure.
     *
     * @param userId         the target user
     * @param sendAction     the actual send logic (may throw)
     * @param mediaType      human-readable media label, e.g. "图片" / "文件"
     * @param filename       the file name for logging
     * @param fallbackSender called on final failure to notify the user
     */
    public void sendWithRetry(String userId, Runnable sendAction,
                              String mediaType, String filename,
                              java.util.function.BiConsumer<String, String> fallbackSender) {
        long backoffMs = 1000;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                sendAction.run();
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[SEND] 发送{}失败，{}/{} 秒后重试 | userId={} | file={} | error={}",
                            mediaType, attempt + 1, MAX_RETRIES, userId, filename, e.getMessage());
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
                    backoffMs *= 2;
                } else {
                    log.error("[SEND] 发送{}失败（已重试{}次）| userId={} | file={}",
                            mediaType, MAX_RETRIES, userId, filename, e);
                    fallbackSender.accept(userId,
                            String.format("%s发送失败（已重试），请稍后再试", mediaType));
                }
            }
        }
    }
}
