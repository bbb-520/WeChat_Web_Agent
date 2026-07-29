package log.summer.aigc.tool.reminder;

import jakarta.annotation.PreDestroy;
import log.summer.aigc.loop.ActResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备忘录提醒工具 —— 时间解析 + 提醒存储 + 定时触发。
 *
 * <p>提醒以内存存储，非持久化（服务重启后丢失）。定时触发时记录日志。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class ReminderTool {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
    private static final AtomicInteger idCounter = new AtomicInteger(0);

    private final CopyOnWriteArrayList<ReminderTask> reminders = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean started = false;

    // ═══════════════════════════════════════════════════════════════
    // @Tool 方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置提醒。
     *
     * @param time    时间表达式，支持格式：X分钟后/小时后/秒后、HH:mm、明天 HH:mm、M月d日 HH:mm
     * @param message 提醒内容
     * @return 设置结果
     */
    @Tool(name = "reminder_set", description = "设置提醒。time 支持格式：X分钟后/小时后/秒后 + 内容、HH:mm + 内容、明天 HH:mm + 内容、M月d日 HH:mm + 内容。message 为提醒内容。")
    public ActResult setReminder(
            @ToolParam(description = "时间表达式，例如 '30分钟后'、'2小时后'、'15:30'、'明天 08:00'、'7月25日 14:00'") String time,
            @ToolParam(description = "提醒内容") String message) {

        try {
            if (time == null || time.isBlank()) {
                return ActResult.failure("请提供时间表达式，例如：30分钟后、15:30、明天 08:00");
            }
            if (message == null || message.isBlank()) {
                return ActResult.failure("请提供提醒内容");
            }

            Instant triggerTime = parseTriggerTime(time.trim());
            if (triggerTime == null) {
                return ActResult.failure("无法解析时间表达式「" + time + "」。支持格式：X分钟后、X小时后、HH:mm、明天 HH:mm、M月d日 HH:mm");
            }

            String content = message.trim();
            ensureStarted();

            int id = idCounter.incrementAndGet();
            ReminderTask task = new ReminderTask(id, triggerTime, content);
            reminders.add(task);

            log.info("[REMINDER] 创建提醒 | id={} | trigger={} | msg=\"{}\"",
                    id, formatTriggerTime(triggerTime),
                    content.length() > 30 ? content.substring(0, 30) + "..." : content);

            String remaining = formatTimeRemaining(triggerTime);
            return ActResult.success("⏰ 提醒已设置！\n"
                    + "────────────────\n"
                    + "内容：" + content + "\n"
                    + "触发时间：" + formatTriggerTime(triggerTime) + "\n"
                    + "剩余时间：" + remaining + "\n"
                    + "────────────────\n"
                    + "提醒 ID：" + id);
        } catch (Exception e) {
            log.error("[REMINDER] 设置提醒失败 | time={} | message={}", time, message, e);
            return ActResult.failure("设置提醒失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    private synchronized void ensureStarted() {
        if (started) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reminder-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkDueReminders, 1, 1, TimeUnit.SECONDS);
        started = true;
        log.info("[REMINDER] 提醒调度器已启动 | interval=1s");
    }

    @PreDestroy
    void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int total = reminders.size();
        reminders.clear();
        started = false;
        log.info("[REMINDER] 提醒调度器已关闭 | cleared={}", total);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部：到期检查
    // ═══════════════════════════════════════════════════════════════

    private void checkDueReminders() {
        Instant now = Instant.now();
        Iterator<ReminderTask> it = reminders.iterator();
        while (it.hasNext()) {
            ReminderTask task = it.next();
            if (!now.isBefore(task.triggerTime())) {
                log.info("[REMINDER] ⏰ 提醒触发 | id={} | msg=\"{}\"",
                        task.id(),
                        task.message().length() > 30
                                ? task.message().substring(0, 30) + "..." : task.message());
                it.remove();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatTriggerTime(Instant time) {
        if (time == null) return "";
        LocalDateTime ldt = time.atZone(ZoneId.systemDefault()).toLocalDateTime();
        return ldt.format(TIME_FMT);
    }

    private String formatTimeRemaining(Instant triggerTime) {
        Duration d = Duration.between(Instant.now(), triggerTime);
        if (d.isNegative()) return "已过期";
        long totalSeconds = d.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%d时%d分%d秒", hours, minutes, seconds);
        if (minutes > 0) return String.format("%d分%d秒", minutes, seconds);
        return seconds + "秒";
    }

    // ═══════════════════════════════════════════════════════════════
    // 时间解析
    // ═══════════════════════════════════════════════════════════════

    private static final Pattern MINUTES_PAT = Pattern.compile(
            "(\\d+)\\s*(分钟|分|min|m)(?:后|之后)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS_PAT = Pattern.compile(
            "(\\d+)\\s*(小时|时|hour|h)(?:后|之后)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECONDS_PAT = Pattern.compile(
            "(\\d+)\\s*(秒钟|秒|second|s)(?:后|之后)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PAT = Pattern.compile(
            "(\\d{1,2}):(\\d{2})");
    private static final Pattern TOMORROW_TIME_PAT = Pattern.compile(
            "明天\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern DATE_TIME_PAT = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})");

    /**
     * 从文本中解析触发时间。
     */
    private static Instant parseTriggerTime(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();

        // X分钟后
        Matcher m = MINUTES_PAT.matcher(trimmed);
        if (m.matches()) return Instant.now().plusSeconds(Integer.parseInt(m.group(1)) * 60L);

        // X小时后
        m = HOURS_PAT.matcher(trimmed);
        if (m.matches()) return Instant.now().plusSeconds(Integer.parseInt(m.group(1)) * 3600L);

        // X秒后
        m = SECONDS_PAT.matcher(trimmed);
        if (m.matches()) return Instant.now().plusSeconds(Integer.parseInt(m.group(1)));

        // M月d日 HH:mm
        m = DATE_TIME_PAT.matcher(trimmed);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int hour = Integer.parseInt(m.group(3));
            int minute = Integer.parseInt(m.group(4));
            LocalDateTime target = LocalDateTime.of(LocalDateTime.now().getYear(), month, day, hour, minute, 0, 0);
            return target.atZone(ZoneId.systemDefault()).toInstant();
        }

        // 明天 HH:mm
        m = TOMORROW_TIME_PAT.matcher(trimmed);
        if (m.matches()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            LocalDateTime tomorrow = LocalDateTime.now().plusDays(1)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            return tomorrow.atZone(ZoneId.systemDefault()).toInstant();
        }

        // 今天 HH:mm
        m = TIME_PAT.matcher(trimmed);
        if (m.matches()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            LocalDateTime today = LocalDateTime.now()
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            Instant trigger = today.atZone(ZoneId.systemDefault()).toInstant();
            if (!trigger.isAfter(Instant.now())) {
                trigger = today.plusDays(1).atZone(ZoneId.systemDefault()).toInstant();
            }
            return trigger;
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据类型
    // ═══════════════════════════════════════════════════════════════

    private record ReminderTask(int id, Instant triggerTime, String message) {}
}
