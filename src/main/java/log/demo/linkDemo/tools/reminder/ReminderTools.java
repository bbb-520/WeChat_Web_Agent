package log.demo.linkDemo.tools.reminder;

import log.demo.linkDemo.service.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备忘录提醒工具类 —— 时间解析 + 提醒存储 + 定时触发 + 回调。
 *
 * <p>非 Spring 管理的纯逻辑工具，通过静态方法操作。提醒以内存 {@link ConcurrentHashMap}
 * 存储，非持久化（服务重启后丢失）。</p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li><b>时间解析：</b>相对时间（X分钟后/小时后）、绝对时间（HH:mm / 明天 HH:mm）</li>
 *   <li><b>提醒管理：</b>创建、取消、列表查看</li>
 *   <li><b>自动触发：</b>{@link ScheduledExecutorService} 每秒检查到期提醒</li>
 *   <li><b>回调通知：</b>通过 {@link MessageSender} 向用户发送提醒消息</li>
 * </ul>
 *
 * @author fjt / bbb
 * @since 2026-07-23
 */
public final class ReminderTools {

    private ReminderTools() { /* 工具类不可实例化 */ }

    private static final Logger log = LoggerFactory.getLogger(ReminderTools.class);

    // ═══════════════════════════════════════════════════════════════
    // 时间解析正则
    // ═══════════════════════════════════════════════════════════════

    private static final Pattern MINUTES_PAT = Pattern.compile(
            "(\\d+)\\s*(分钟|分|min|m)(?:后|之后)?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS_PAT = Pattern.compile(
            "(\\d+)\\s*(小时|时|hour|h)(?:后|之后)?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECONDS_PAT = Pattern.compile(
            "(\\d+)\\s*(秒钟|秒|second|s)(?:后|之后)?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PAT = Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*(.+)");
    private static final Pattern TOMORROW_TIME_PAT = Pattern.compile(
            "明天\\s*(\\d{1,2}):(\\d{2})\\s*(.+)");
    private static final Pattern DATE_TIME_PAT = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})\\s*(.+)");

    // ═══════════════════════════════════════════════════════════════
    // 提醒存储
    // ═══════════════════════════════════════════════════════════════

    private static final ConcurrentHashMap<String, List<ReminderTask>> reminders = new ConcurrentHashMap<>();
    private static final AtomicInteger idCounter = new AtomicInteger(0);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private static ScheduledExecutorService scheduler;
    private static volatile MessageSender messageSender;
    private static volatile boolean started = false;

    // ═══════════════════════════════════════════════════════════════
    // 数据类型
    // ═══════════════════════════════════════════════════════════════

    /** 解析后的提醒时间与内容 */
    public record ParsedTime(Instant triggerTime, String message) {}

    /** 已创建的提醒任务 */
    public record ReminderTask(
            int id,
            String userId,
            Instant triggerTime,
            String message,
            Instant createdAt
    ) {
        public boolean isDue() { return !Instant.now().isBefore(triggerTime); }

        public String triggerTimeFormatted() {
            LocalDateTime ldt = triggerTime.atZone(ZoneId.systemDefault()).toLocalDateTime();
            return ldt.format(TIME_FMT);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 确保调度器已启动（幂等，首次调用时初始化）。
     */
    private static synchronized void ensureStarted() {
        if (started) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reminder-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(ReminderTools::checkDueReminders,
                1, 1, TimeUnit.SECONDS);
        started = true;
        log.info("[REMINDER] 提醒调度器已启动（lazy init）| interval=1s");
    }

    /**
     * 设置消息发送器（由 ILinkBotService 在启动后注入）。
     */
    public static void setSender(MessageSender sender) {
        messageSender = sender;
    }

    /** 关闭提醒后台调度器。 */
    public static synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { boolean ignored = scheduler.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int total = reminders.values().stream().mapToInt(List::size).sum();
        reminders.clear();
        started = false;
        log.info("[REMINDER] 提醒调度器已关闭 | cleared={}", total);
    }

    // ═══════════════════════════════════════════════════════════════
    // 时间解析
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从用户文本中解析提醒时间与内容。
     *
     * <p>支持格式：</p>
     * <ul>
     *   <li>X分钟后/小时后/秒后 + 内容</li>
     *   <li>HH:mm + 内容（今天，已过则设为明天）</li>
     *   <li>明天 HH:mm + 内容</li>
     *   <li>M月d日 HH:mm + 内容</li>
     * </ul>
     *
     * @param text 用户输入
     * @return 解析结果，无法识别返回 null
     */
    public static ParsedTime parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();

        // X分钟后
        Matcher m = MINUTES_PAT.matcher(trimmed);
        if (m.matches()) {
            int mins = Integer.parseInt(m.group(1));
            return new ParsedTime(
                    Instant.now().plusSeconds(mins * 60L), m.group(3).trim());
        }

        // X小时后
        m = HOURS_PAT.matcher(trimmed);
        if (m.matches()) {
            int hours = Integer.parseInt(m.group(1));
            return new ParsedTime(
                    Instant.now().plusSeconds(hours * 3600L), m.group(3).trim());
        }

        // X秒后
        m = SECONDS_PAT.matcher(trimmed);
        if (m.matches()) {
            int seconds = Integer.parseInt(m.group(1));
            return new ParsedTime(
                    Instant.now().plusSeconds(seconds), m.group(3).trim());
        }

        // M月d日 HH:mm
        m = DATE_TIME_PAT.matcher(trimmed);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int hour = Integer.parseInt(m.group(3));
            int minute = Integer.parseInt(m.group(4));
            int nowYear = LocalDateTime.now().getYear();
            LocalDateTime target = LocalDateTime.of(nowYear, month, day, hour, minute, 0, 0);
            return new ParsedTime(
                    target.atZone(ZoneId.systemDefault()).toInstant(), m.group(5).trim());
        }

        // 明天 HH:mm
        m = TOMORROW_TIME_PAT.matcher(trimmed);
        if (m.matches()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = Integer.parseInt(m.group(2));
            LocalDateTime tomorrow = LocalDateTime.now().plusDays(1)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            return new ParsedTime(
                    tomorrow.atZone(ZoneId.systemDefault()).toInstant(), m.group(3).trim());
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
            return new ParsedTime(trigger, m.group(3).trim());
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // 提醒管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建提醒。
     *
     * @param userId      用户 ID
     * @param triggerTime 触发时间
     * @param message     提醒内容
     * @return 创建的提醒任务
     */
    public static ReminderTask createReminder(String userId, Instant triggerTime, String message) {
        ensureStarted();
        int id = idCounter.incrementAndGet();
        ReminderTask task = new ReminderTask(id, userId, triggerTime, message, Instant.now());
        reminders.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(task);
        log.info("[REMINDER] 创建提醒 | id={} userId={} trigger={} msg=\"{}\"",
                id, userId, formatTriggerTime(triggerTime),
                message.length() > 30 ? message.substring(0, 30) + "..." : message);
        return task;
    }

    /**
     * 创建提醒（从文本解析）。
     *
     * @param userId 用户 ID
     * @param text   用户输入（含时间表达式和提醒内容）
     * @return 创建成功返回提醒任务，解析失败返回 null
     */
    public static ReminderTask createReminderFromText(String userId, String text) {
        ParsedTime parsed = parseTime(text);
        if (parsed == null) return null;
        return createReminder(userId, parsed.triggerTime(), parsed.message());
    }

    /**
     * 取消指定 ID 的提醒。
     *
     * @param userId 用户 ID
     * @param taskId 提醒 ID
     * @return 是否找到并取消
     */
    public static boolean cancelReminder(String userId, int taskId) {
        List<ReminderTask> userTasks = reminders.get(userId);
        if (userTasks == null) return false;
        boolean removed = userTasks.removeIf(t -> t.id() == taskId);
        if (removed) {
            if (userTasks.isEmpty()) reminders.remove(userId);
            log.info("[REMINDER] 取消提醒 | userId={} taskId={}", userId, taskId);
        }
        return removed;
    }

    /** 获取用户的所有进行中提醒（已过期的不返回）。 */
    public static List<ReminderTask> listReminders(String userId) {
        List<ReminderTask> userTasks = reminders.get(userId);
        if (userTasks == null || userTasks.isEmpty()) return List.of();
        Instant now = Instant.now();
        return userTasks.stream()
                .filter(t -> !now.isBefore(t.triggerTime())) // 只返回未触发的
                .toList();
    }

    /** 获取用户所有提醒（含已过期的）。 */
    public static List<ReminderTask> listAllReminders(String userId) {
        List<ReminderTask> userTasks = reminders.get(userId);
        return userTasks == null ? List.of() : List.copyOf(userTasks);
    }

    /** 取消用户所有提醒。 */
    public static int cancelAll(String userId) {
        List<ReminderTask> removed = reminders.remove(userId);
        int count = removed != null ? removed.size() : 0;
        if (count > 0) log.info("[REMINDER] 清除所有提醒 | userId={} count={}", userId, count);
        return count;
    }

    // ═══════════════════════════════════════════════════════════════
    // 格式化
    // ═══════════════════════════════════════════════════════════════

    /** 格式化触发时间 */
    public static String formatTriggerTime(Instant time) {
        if (time == null) return "";
        LocalDateTime ldt = time.atZone(ZoneId.systemDefault()).toLocalDateTime();
        return ldt.format(TIME_FMT);
    }

    /** 计算距触发时间的剩余时长 */
    public static String formatTimeRemaining(Instant triggerTime) {
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

    /** 生成提醒列表文本 */
    public static String formatReminderList(String userId) {
        List<ReminderTask> userTasks = reminders.get(userId);
        if (userTasks == null || userTasks.isEmpty()) {
            return "📝 你还没有设置提醒\n"
                    + "用法：/remind 30分钟后 开会\n"
                    + "     /remind 明天 08:00 起床";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📝 你的提醒列表\n").append("────────────────\n");
        Instant now = Instant.now();
        int activeCount = 0;
        for (ReminderTask t : userTasks) {
            boolean due = !now.isBefore(t.triggerTime());
            if (due) {
                sb.append("✅ [已触发] ");
            } else {
                sb.append("⏳ [").append(formatTimeRemaining(t.triggerTime())).append("后] ");
                activeCount++;
            }
            sb.append("ID:").append(t.id()).append(" — ");
            sb.append(t.triggerTimeFormatted()).append("\n");
            sb.append("   ").append(t.message()).append("\n");
        }
        sb.append("────────────────\n");
        sb.append("共 ").append(userTasks.size()).append(" 个提醒");
        if (activeCount > 0) sb.append("（进行中 ").append(activeCount).append(" 个）");
        sb.append("\n取消提醒：/remind cancel <ID>");
        return sb.toString();
    }

    /** 帮助文本 */
    public static String helpText() {
        return """
                📝 提醒功能 —— 帮助
                ────────────────
                /remind <时间> <内容>  创建提醒
                /remind list          查看提醒列表
                /remind cancel <ID>   取消指定提醒
                /remind cancel all    取消所有提醒
                ────────────────
                📌 支持的时间格式：
                • 30分钟后 开会
                • 2小时后 打电话
                • 15:30 午休结束
                • 明天 08:00 起床
                • 7月25日 14:00 生日""";
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部：到期检查
    // ═══════════════════════════════════════════════════════════════

    private static void checkDueReminders() {
        if (messageSender == null) return;
        Instant now = Instant.now();

        for (Map.Entry<String, List<ReminderTask>> entry : reminders.entrySet()) {
            String userId = entry.getKey();
            List<ReminderTask> tasks = entry.getValue();
            List<ReminderTask> due = new ArrayList<>();

            for (ReminderTask task : tasks) {
                if (task.isDue()) due.add(task);
            }

            for (ReminderTask task : due) {
                try {
                    String msg = "⏰ 提醒时间到！\n"
                            + "────────────────\n"
                            + task.message() + "\n"
                            + "────────────────\n"
                            + "设置时间：" + task.triggerTimeFormatted();
                    messageSender.sendText(userId, msg);

                    // 已触发的提醒从列表中移除
                    tasks.remove(task);
                    log.info("[REMINDER] 触发提醒 | id={} userId={} msg=\"{}\"",
                            task.id(), userId,
                            task.message().length() > 30
                                    ? task.message().substring(0, 30) + "..." : task.message());
                } catch (Exception e) {
                    log.warn("[REMINDER] 发送提醒失败 | id={} userId={} | {}",
                            task.id(), userId, e.getMessage());
                }
            }

            // 清理空列表
            if (tasks.isEmpty()) {
                reminders.remove(userId);
            }
        }
    }
}
