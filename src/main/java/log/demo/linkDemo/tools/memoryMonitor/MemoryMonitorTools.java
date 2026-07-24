package log.demo.linkDemo.tools.memoryMonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务器内存监控工具类 —— 纯逻辑，无状态，不依赖 Spring。
 *
 * <p>基于 {@code java.lang.management.ManagementFactory} 采集 JVM 及系统级内存、
 * CPU、GC、线程指标，并通过 Unicode 块字符进度条和 Emoji 指示器进行图形化展示。</p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>数据采集：堆内存、非堆内存、系统物理内存、交换空间、CPU、GC、线程</li>
 *   <li>图形展示：Unicode 块字符（█▓▒░）进度条，4 级颗粒度</li>
 *   <li>单位适配：B → KB → MB → GB → TB 自动检测，保留一位小数</li>
 *   <li>智能告警：内存超阈值、异常增长、GC 频率过高、线程暴涨等</li>
 *   <li>报告生成：完整报告、对比报告、简版报告三种格式</li>
 * </ul>
 *
 * @author fjt
 * @since 2026-07-23
 */
public final class MemoryMonitorTools {

    private MemoryMonitorTools() { /* 工具类不可实例化 */ }

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitorTools.class);

    // ═══════════════════════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════════════════════

    /** 进度条默认宽度（字符数） */
    public static final int BAR_WIDTH = 20;

    /** 系统内存 / CPU 获取不到时的占位值 */
    private static final long UNKNOWN_MEMORY = -1L;
    private static final double UNKNOWN_CPU = -1.0;

    /** Unicode 块字符 —— 填满 / 3/4 / 1/2 / 1/4 / 空 */
    private static final char BLOCK_FULL = '█';
    private static final char BLOCK_DENSE = '▓';
    private static final char BLOCK_SPARSE = '▒';
    private static final char BLOCK_EMPTY = '░';

    /** 默认告警阈值 */
    public static final Threshold DEFAULT_THRESHOLD = new Threshold();

    // ═══════════════════════════════════════════════════════════════
    // 嵌套类型
    // ═══════════════════════════════════════════════════════════════

    /**
     * JVM 内存快照 —— 一次采集获取的所有内存与系统指标。
     *
     * @param timestamp         采集时间戳（毫秒）
     * @param heapUsed          堆内存已使用（字节）
     * @param heapMax           堆内存最大值（字节），无限制时为 -1
     * @param heapCommitted     堆内存已提交（字节）
     * @param nonHeapUsed       非堆内存已使用（字节）
     * @param nonHeapCommitted  非堆内存已提交（字节）
     * @param totalPhysical     系统物理内存总量（字节），不可用时返回 -1
     * @param freePhysical      系统空闲物理内存（字节），不可用时返回 -1
     * @param totalSwap         交换空间总量（字节），不可用时返回 -1
     * @param freeSwap          空闲交换空间（字节），不可用时返回 -1
     * @param processCpuLoad    进程 CPU 使用率（0.0~1.0），不可用时返回 -1
     * @param systemCpuLoad     系统 CPU 使用率（0.0~1.0），不可用时返回 -1
     * @param threadCount       当前活跃线程数
     * @param gcCount           累计 GC 次数（所有收集器总和）
     * @param gcTimeMs          累计 GC 耗时（毫秒，所有收集器总和）
     */
    public record MemorySnapshot(
            long timestamp,
            long heapUsed,
            long heapMax,
            long heapCommitted,
            long nonHeapUsed,
            long nonHeapCommitted,
            long totalPhysical,
            long freePhysical,
            long totalSwap,
            long freeSwap,
            double processCpuLoad,
            double systemCpuLoad,
            int threadCount,
            long gcCount,
            long gcTimeMs
    ) {}

    /**
     * 内存告警等级 —— 对应不同的 Emoji 指示器。
     *
     * <ul>
     *   <li>{@link #NORMAL}   🟢 正常</li>
     *   <li>{@link #WARNING}  🟡 关注</li>
     *   <li>{@link #HIGH}     🟠 警告</li>
     *   <li>{@link #CRITICAL} 🔴 严重</li>
     * </ul>
     */
    public enum AlertLevel {
        NORMAL("正常", "🟢"),
        WARNING("关注", "🟡"),
        HIGH("警告", "🟠"),
        CRITICAL("严重", "🔴");

        private final String label;
        private final String icon;

        AlertLevel(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() { return label; }
        public String icon() { return icon; }

        /**
         * 根据堆内存使用百分比计算告警等级。
         * <ul>
         *   <li>&lt; 60% → NORMAL</li>
         *   <li>60~80% → WARNING</li>
         *   <li>80~95% → HIGH</li>
         *   <li>&gt; 95% → CRITICAL</li>
         * </ul>
         */
        public static AlertLevel fromUsage(double usagePercent) {
            if (usagePercent < 0) return NORMAL;
            if (usagePercent >= 95) return CRITICAL;
            if (usagePercent >= 80) return HIGH;
            if (usagePercent >= 60) return WARNING;
            return NORMAL;
        }

        /**
         * 根据增长百分比计算告警等级。
         * <ul>
         *   <li>&lt; 5% → NORMAL</li>
         *   <li>5~15% → WARNING</li>
         *   <li>15~30% → HIGH</li>
         *   <li>&gt; 30% → CRITICAL</li>
         * </ul>
         */
        public static AlertLevel fromGrowthRate(double growthPercent) {
            if (growthPercent < 0) return NORMAL;
            if (growthPercent >= 30) return CRITICAL;
            if (growthPercent >= 15) return HIGH;
            if (growthPercent >= 5) return WARNING;
            return NORMAL;
        }
    }

    /**
     * 单条内存告警。
     *
     * @param level        告警等级
     * @param category     告警分类（堆内存 / 系统内存 / GC / 线程）
     * @param message      告警详情
     * @param currentValue 当前值（已格式化）
     * @param threshold    阈值（已格式化），无阈值时为空字符串
     */
    public record MemoryAlert(
            AlertLevel level,
            String category,
            String message,
            String currentValue,
            String threshold
    ) {}

    /**
     * 可配置的内存告警阈值。
     *
     * @param heapWarnPercent      堆内存使用率关注阈值（默认 60%）
     * @param heapHighPercent      堆内存使用率警告阈值（默认 80%）
     * @param heapCriticalPercent  堆内存使用率严重阈值（默认 95%）
     * @param systemHighPercent    系统内存使用率警告阈值（默认 85%）
     * @param systemCriticalPercent 系统内存使用率严重阈值（默认 95%）
     * @param growthRatePercent    两次快照间增长超过此比例触发告警（默认 15%）
     * @param gcRatePerMinute      每分钟 GC 次数超过此值触发告警（默认 10）
     */
    public record Threshold(
            double heapWarnPercent,
            double heapHighPercent,
            double heapCriticalPercent,
            double systemHighPercent,
            double systemCriticalPercent,
            double growthRatePercent,
            int gcRatePerMinute
    ) {
        /** 默认阈值配置 */
        public Threshold() {
            this(60.0, 80.0, 95.0, 85.0, 95.0, 15.0, 10);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据采集
    // ═══════════════════════════════════════════════════════════════

    /**
     * 采集当前时刻的完整 JVM 及系统内存快照。
     *
     * <p>内部使用 {@link MemoryMXBean}、{@link OperatingSystemMXBean}
     * （优先转型 {@code com.sun.management.OperatingSystemMXBean}）、
     * {@link ThreadMXBean}、{@link GarbageCollectorMXBean}。</p>
     *
     * @return 内存快照
     */
    public static MemorySnapshot collectSnapshot() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();

        long totalPhysical = getTotalPhysicalMemory();
        long freePhysical = getFreePhysicalMemory();

        return new MemorySnapshot(
                System.currentTimeMillis(),
                heapUsage.getUsed(),
                heapUsage.getMax(),
                heapUsage.getCommitted(),
                nonHeapUsage.getUsed(),
                nonHeapUsage.getCommitted(),
                totalPhysical,
                freePhysical,
                getTotalSwapSpace(),
                getFreeSwapSpace(),
                clampCpu(getProcessCpuLoad()),
                clampCpu(getSystemCpuLoad()),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                getTotalGcCount(),
                getTotalGcTimeMs()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // 格式化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将字节值自动转换为合适的单位（B / KB / MB / GB / TB），保留一位小数。
     *
     * @param bytes 字节数
     * @return 格式化字符串，如 "420.5 MB"；负数返回 "N/A"
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        long abs = Math.abs(bytes);

        return switch (0) {
            default -> {
                if (abs >= 1099511627776L) // 1 TB = 1024^4
                    yield String.format("%.1f TB", bytes / 1099511627776.0);
                if (abs >= 1073741824)     // 1 GB
                    yield String.format("%.1f GB", bytes / 1073741824.0);
                if (abs >= 1048576)        // 1 MB
                    yield String.format("%.1f MB", bytes / 1048576.0);
                if (abs >= 1024)           // 1 KB
                    yield String.format("%.1f KB", bytes / 1024.0);
                yield bytes + " B";
            }
        };
    }

    /**
     * 格式化 "已用 / 总量" 形式的内存字符串。
     *
     * @param used  已使用字节数
     * @param total 总量字节数
     * @return 如 "420.5 MB / 580.0 MB (72.5%)"
     */
    public static String formatBytesWithTotal(long used, long total) {
        String usedStr = formatBytes(used);
        if (total <= 0) return usedStr;
        double pct = used * 100.0 / total;
        return usedStr + " / " + formatBytes(total) + " (" + formatPercent(pct / 100.0) + ")";
    }

    /**
     * 将 0.0~1.0 的比例格式化为百分比字符串。
     *
     * @param ratio 比例值（如 0.725 表示 72.5%）
     * @return 如 "72.5%"；负数返回 "N/A"
     */
    public static String formatPercent(double ratio) {
        if (ratio < 0) return "N/A";
        return String.format("%.1f%%", ratio * 100.0);
    }

    /**
     * 使用 Unicode 块字符生成文本进度条（默认宽度 {@value #BAR_WIDTH}）。
     *
     * <p>示例输出（72.5%，宽 20）：{@code [████████▓░░░░░░░░░] 72.5%}</p>
     *
     * @param percentage 百分比（0~100）
     * @return 进度条字符串
     */
    public static String buildProgressBar(double percentage) {
        return buildProgressBar(percentage, BAR_WIDTH);
    }

    /**
     * 使用 Unicode 块字符生成文本进度条。
     *
     * <p>4 级颗粒度填充：█（满）、▓（3/4）、▒（1/2）、░（空）。
     * 20 字符宽等价于 80 级区分度。</p>
     *
     * @param percentage 百分比（0~100），越界自动钳位
     * @param width      进度条宽度（字符数），最小 5
     * @return 进度条字符串，如 {@code "[████▓░░░░░] 72.5%"}
     */
    public static String buildProgressBar(double percentage, int width) {
        if (width < 5) width = 5;
        double pct = Math.max(0, Math.min(100, percentage));

        double filledUnits = pct * width / 100.0;
        int fullBlocks = (int) filledUnits;
        double remainder = filledUnits - fullBlocks;
        int emptyBlocks = width - fullBlocks - (remainder > 0 ? 1 : 0);

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < fullBlocks; i++) {
            sb.append(BLOCK_FULL);
        }
        if (remainder > 0) {
            if (remainder >= 0.75) sb.append(BLOCK_FULL);
            else if (remainder >= 0.5) sb.append(BLOCK_DENSE);
            else if (remainder >= 0.25) sb.append(BLOCK_SPARSE);
            else sb.append(BLOCK_EMPTY);
        }
        for (int i = 0; i < emptyBlocks; i++) {
            sb.append(BLOCK_EMPTY);
        }
        sb.append("] ").append(formatPercent(pct / 100.0));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 使用率计算
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计算堆内存使用百分比。
     *
     * @param snapshot 内存快照
     * @return 百分比（0~100），heapMax &lt;= 0 时返回 -1.0
     */
    public static double heapUsagePercent(MemorySnapshot snapshot) {
        if (snapshot.heapMax() <= 0) return -1.0;
        return snapshot.heapUsed() * 100.0 / snapshot.heapMax();
    }

    /**
     * 计算系统物理内存使用百分比。
     *
     * @param snapshot 内存快照
     * @return 百分比（0~100），totalPhysical &lt;= 0 时返回 -1.0
     */
    public static double systemMemoryUsagePercent(MemorySnapshot snapshot) {
        if (snapshot.totalPhysical() <= 0) return -1.0;
        long used = snapshot.totalPhysical() - snapshot.freePhysical();
        return used * 100.0 / snapshot.totalPhysical();
    }

    // ═══════════════════════════════════════════════════════════════
    // 告警检测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用默认阈值检测当前快照的单点告警。
     *
     * @param snapshot 内存快照
     * @return 告警列表，无告警时为空
     */
    public static List<MemoryAlert> detectAlerts(MemorySnapshot snapshot) {
        return detectAlerts(snapshot, DEFAULT_THRESHOLD);
    }

    /**
     * 使用自定义阈值检测当前快照的单点告警。
     *
     * @param snapshot  内存快照
     * @param threshold 告警阈值
     * @return 告警列表，无告警时为空
     */
    public static List<MemoryAlert> detectAlerts(MemorySnapshot snapshot, Threshold threshold) {
        List<MemoryAlert> alerts = new ArrayList<>();

        // 堆内存使用率
        double heapPct = heapUsagePercent(snapshot);
        if (heapPct >= 0) {
            AlertLevel heapLevel = AlertLevel.fromUsage(heapPct);
            if (heapLevel != AlertLevel.NORMAL) {
                alerts.add(new MemoryAlert(
                        heapLevel,
                        "堆内存",
                        "堆内存使用率 " + formatPercent(heapPct / 100) + "，超过关注阈值 " + formatPercent(threshold.heapWarnPercent() / 100),
                        formatPercent(heapPct / 100),
                        formatPercent(threshold.heapWarnPercent() / 100)
                ));
            }
        }

        // 系统内存使用率
        double sysPct = systemMemoryUsagePercent(snapshot);
        if (sysPct >= 0) {
            if (sysPct >= threshold.systemCriticalPercent()) {
                alerts.add(new MemoryAlert(
                        AlertLevel.CRITICAL,
                        "系统内存",
                        "系统内存使用率 " + formatPercent(sysPct / 100) + "，超过严重阈值",
                        formatPercent(sysPct / 100),
                        formatPercent(threshold.systemCriticalPercent() / 100)
                ));
            } else if (sysPct >= threshold.systemHighPercent()) {
                alerts.add(new MemoryAlert(
                        AlertLevel.HIGH,
                        "系统内存",
                        "系统内存使用率 " + formatPercent(sysPct / 100) + "，超过警告阈值",
                        formatPercent(sysPct / 100),
                        formatPercent(threshold.systemHighPercent() / 100)
                ));
            }
        }

        // 进程 CPU 使用率
        if (snapshot.processCpuLoad() >= 0.9) {
            alerts.add(new MemoryAlert(
                    AlertLevel.HIGH,
                    "CPU",
                    "进程 CPU 使用率 " + formatPercent(snapshot.processCpuLoad()) + "，持续高负载",
                    formatPercent(snapshot.processCpuLoad()),
                    "90.0%"
            ));
        }

        return alerts;
    }

    /**
     * 使用默认阈值对比两次快照，检测异常增长。
     *
     * @param previous 上一次快照
     * @param current  当前快照
     * @return 告警列表，无异常时为空
     */
    public static List<MemoryAlert> compareSnapshots(MemorySnapshot previous, MemorySnapshot current) {
        return compareSnapshots(previous, current, DEFAULT_THRESHOLD);
    }

    /**
     * 使用自定义阈值对比两次快照，检测异常增长。
     *
     * <p>检测项：堆内存增长、GC 频率、线程数变化。</p>
     *
     * @param previous  上一次快照
     * @param current   当前快照
     * @param threshold 告警阈值
     * @return 告警列表，无异常时为空
     */
    public static List<MemoryAlert> compareSnapshots(MemorySnapshot previous, MemorySnapshot current,
                                                      Threshold threshold) {
        List<MemoryAlert> alerts = new ArrayList<>();

        // 时间有效性检查
        long intervalMs = current.timestamp() - previous.timestamp();
        if (intervalMs <= 0) {
            log.debug("[MEM] compareSnapshots: 时间倒序或无变化，跳过 | prev={} curr={}",
                    previous.timestamp(), current.timestamp());
            return alerts;
        }
        double intervalMin = intervalMs / 60000.0;

        // 堆内存增长
        long heapDelta = current.heapUsed() - previous.heapUsed();
        if (previous.heapMax() > 0 && heapDelta > 0) {
            double growthPct = heapDelta * 100.0 / previous.heapMax();
            AlertLevel growthLevel = AlertLevel.fromGrowthRate(growthPct);
            if (growthLevel != AlertLevel.NORMAL) {
                alerts.add(new MemoryAlert(
                        growthLevel,
                        "堆内存增长",
                        "堆内存增长 " + formatPercent(growthPct / 100) + "（" + formatBytes(heapDelta) + "），"
                                + "超过增长阈值 " + formatPercent(threshold.growthRatePercent() / 100),
                        formatPercent(growthPct / 100),
                        formatPercent(threshold.growthRatePercent() / 100)
                ));
            }
        }

        // GC 频率
        long gcDelta = current.gcCount() - previous.gcCount();
        if (gcDelta > 0 && intervalMin > 0) {
            double gcRate = gcDelta / intervalMin;
            if (gcRate > threshold.gcRatePerMinute()) {
                alerts.add(new MemoryAlert(
                        AlertLevel.WARNING,
                        "GC 频率",
                        "GC 频率 " + String.format("%.1f", gcRate) + " 次/分钟，超过阈值 "
                                + threshold.gcRatePerMinute() + " 次/分钟",
                        String.format("%.1f 次/分", gcRate),
                        threshold.gcRatePerMinute() + " 次/分"
                ));
            }
        }

        // 线程数大幅增长
        int threadDelta = current.threadCount() - previous.threadCount();
        if (threadDelta > 20) {
            alerts.add(new MemoryAlert(
                    AlertLevel.WARNING,
                    "线程",
                    "线程数在 " + String.format("%.1f", intervalMin) + " 分钟内增长 " + threadDelta + " 个（"
                            + previous.threadCount() + " → " + current.threadCount() + "）",
                    String.valueOf(current.threadCount()),
                    "增长 <= 20"
            ));
        }

        return alerts;
    }

    // ═══════════════════════════════════════════════════════════════
    // 报告生成
    // ═══════════════════════════════════════════════════════════════

    /**
     * 生成完整的单快照格式化报告。
     *
     * @param snapshot 内存快照
     * @return 格式化后的多行报告文本
     */
    public static String generateReport(MemorySnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append("══════════ 服务器内存监控报告 ══════════\n");
        sb.append("⏱ 采集时间：").append(formatTimestamp(snapshot.timestamp())).append("\n");
        sb.append("🕐 JVM 运行：").append(formatUptime(getJvmUptimeMs())).append("\n");

        // 堆内存
        double heapPct = heapUsagePercent(snapshot);
        sb.append("\n━━━━━━━━━ 堆内存 (Heap) ━━━━━━━━━\n");
        if (heapPct >= 0) {
            sb.append(buildProgressBar(heapPct)).append("\n");
        }
        sb.append("已用：").append(formatBytes(snapshot.heapUsed()));
        if (snapshot.heapMax() > 0) {
            sb.append(" / 最大：").append(formatBytes(snapshot.heapMax()));
        }
        sb.append("  |  提交：").append(formatBytes(snapshot.heapCommitted())).append("\n");

        // 非堆内存
        sb.append("\n━━━━━━ 非堆内存 (Non-Heap) ━━━━━━\n");
        sb.append("已用：").append(formatBytes(snapshot.nonHeapUsed()));
        sb.append("  |  提交：").append(formatBytes(snapshot.nonHeapCommitted())).append("\n");

        // 系统内存
        sb.append("\n━━━━━━━━ 系统内存 (OS) ━━━━━━━━━\n");
        if (snapshot.totalPhysical() > 0) {
            double sysPct = systemMemoryUsagePercent(snapshot);
            if (sysPct >= 0) {
                sb.append(buildProgressBar(sysPct)).append("\n");
            }
            long used = snapshot.totalPhysical() - snapshot.freePhysical();
            sb.append("已用：").append(formatBytes(used));
            sb.append(" / 总量：").append(formatBytes(snapshot.totalPhysical())).append("\n");
        } else {
            sb.append("（系统内存信息不可用）\n");
        }
        if (snapshot.totalSwap() > 0) {
            long swapUsed = snapshot.totalSwap() - snapshot.freeSwap();
            sb.append("交换：").append(formatBytes(swapUsed));
            sb.append(" / ").append(formatBytes(snapshot.totalSwap())).append("\n");
        }

        // CPU / GC / 线程
        sb.append("\n━━━━━━ CPU / GC / 线程 ━━━━━━━━\n");
        sb.append("进程 CPU：").append(formatPercent(snapshot.processCpuLoad()));
        sb.append("  |  系统 CPU：").append(formatPercent(snapshot.systemCpuLoad())).append("\n");
        sb.append("GC 次数：").append(formatNum(snapshot.gcCount()));
        sb.append("  |  GC 耗时：").append(formatDurationMs(snapshot.gcTimeMs()));
        sb.append("  |  线程：").append(snapshot.threadCount()).append("\n");

        // 告警
        List<MemoryAlert> alerts = detectAlerts(snapshot);
        sb.append("\n━━━━━━━━━━ 告警 ━━━━━━━━━━━━\n");
        if (alerts.isEmpty()) {
            sb.append("🟢 系统运行正常，所有指标均在安全范围内\n");
        } else {
            for (MemoryAlert alert : alerts) {
                sb.append(alert.level().icon()).append(" [").append(alert.level().label())
                        .append("] ").append(alert.message()).append("\n");
            }
        }

        sb.append("════════════════════════════════════════");
        return sb.toString();
    }

    /**
     * 生成两次快照的对比变化报告。
     *
     * @param previous 上一次快照
     * @param current  当前快照
     * @return 对比变化报告
     */
    public static String generateReport(MemorySnapshot previous, MemorySnapshot current) {
        StringBuilder sb = new StringBuilder();

        long intervalMs = current.timestamp() - previous.timestamp();

        sb.append("══════════ 内存变化对比报告 ══════════\n");
        sb.append("⏱ 对比间隔：").append(formatInterval(intervalMs)).append("\n");

        // 堆内存变化
        double prevHeapPct = heapUsagePercent(previous);
        double currHeapPct = heapUsagePercent(current);

        sb.append("\n━━━━━━ 堆内存变化 ━━━━━━━━\n");
        if (prevHeapPct >= 0 && currHeapPct >= 0) {
            sb.append("上次：").append(buildProgressBar(prevHeapPct)).append(" (").append(formatBytes(previous.heapUsed())).append(")\n");
            sb.append("本次：").append(buildProgressBar(currHeapPct)).append(" (").append(formatBytes(current.heapUsed())).append(")\n");
        } else {
            sb.append("上次已用：").append(formatBytes(previous.heapUsed())).append("\n");
            sb.append("本次已用：").append(formatBytes(current.heapUsed())).append("\n");
        }

        long heapDelta = current.heapUsed() - previous.heapUsed();
        double heapGrowthPct = previous.heapMax() > 0
                ? heapDelta * 100.0 / previous.heapMax() : 0;
        String deltaSymbol = heapDelta >= 0 ? "+" : "";
        sb.append("变化：").append(deltaSymbol).append(formatBytes(heapDelta));
        if (previous.heapMax() > 0) {
            sb.append(" (").append(deltaSymbol).append(String.format("%.1f%%", heapGrowthPct)).append(")");
        }
        sb.append("\n");

        // 非堆变化
        long nonHeapDelta = current.nonHeapUsed() - previous.nonHeapUsed();
        String nonHeapSymbol = nonHeapDelta >= 0 ? "+" : "";
        sb.append("\n━━━━━━ 非堆内存变化 ━━━━━━━━\n");
        sb.append("上次：").append(formatBytes(previous.nonHeapUsed())).append("\n");
        sb.append("本次：").append(formatBytes(current.nonHeapUsed())).append("\n");
        sb.append("变化：").append(nonHeapSymbol).append(formatBytes(nonHeapDelta)).append("\n");

        // GC / 线程变化
        sb.append("\n━━━━━━ GC / 线程变化 ━━━━━━━━\n");
        long gcDelta = current.gcCount() - previous.gcCount();
        long gcTimeDelta = current.gcTimeMs() - previous.gcTimeMs();
        int threadDelta = current.threadCount() - previous.threadCount();
        String threadSymbol = threadDelta >= 0 ? "+" : "";

        sb.append("GC 次数：").append(formatNum(previous.gcCount())).append(" → ")
                .append(formatNum(current.gcCount()));
        if (gcDelta > 0) sb.append(" (+").append(gcDelta).append(")");
        sb.append("\n");
        sb.append("GC 耗时：").append(formatDurationMs(previous.gcTimeMs())).append(" → ")
                .append(formatDurationMs(current.gcTimeMs()));
        if (gcTimeDelta > 0) sb.append(" (+").append(formatDurationMs(gcTimeDelta)).append(")");
        sb.append("\n");
        sb.append("线程：").append(previous.threadCount()).append(" → ")
                .append(current.threadCount());
        if (threadDelta != 0) sb.append(" (").append(threadSymbol).append(threadDelta).append(")");
        sb.append("\n");

        // 告警
        List<MemoryAlert> alerts = detectAlerts(current);
        alerts.addAll(compareSnapshots(previous, current));
        sb.append("\n━━━━━━━━━━ 告警 ━━━━━━━━━━━━\n");
        if (alerts.isEmpty()) {
            sb.append("🟢 未检测到异常，内存使用稳定\n");
        } else {
            for (MemoryAlert alert : alerts) {
                sb.append(alert.level().icon()).append(" [").append(alert.level().label())
                        .append("] ").append(alert.message()).append("\n");
            }
        }

        sb.append("════════════════════════════════════════");
        return sb.toString();
    }

    /**
     * 生成简版单行报告，适合快速查看。
     *
     * @param snapshot 内存快照
     * @return 单行格式的内存状态摘要
     */
    public static String generateSimpleReport(MemorySnapshot snapshot) {
        double heapPct = heapUsagePercent(snapshot);
        double sysPct = systemMemoryUsagePercent(snapshot);

        StringBuilder sb = new StringBuilder();
        sb.append("🖥 堆：" );
        if (heapPct >= 0) {
            sb.append(buildProgressBar(heapPct, 10));
        } else {
            sb.append(formatBytes(snapshot.heapUsed()));
        }

        sb.append(" | 系统：");
        if (sysPct >= 0 && snapshot.totalPhysical() > 0) {
            sb.append(buildProgressBar(sysPct, 10));
        } else {
            sb.append("N/A");
        }

        sb.append(" | CPU：").append(formatPercent(snapshot.processCpuLoad()));
        sb.append(" | 线程：").append(snapshot.threadCount());

        // 如果存在告警，追加告警数量
        List<MemoryAlert> alerts = detectAlerts(snapshot);
        if (!alerts.isEmpty()) {
            long criticalCount = alerts.stream()
                    .filter(a -> a.level() == AlertLevel.CRITICAL).count();
            long highCount = alerts.stream()
                    .filter(a -> a.level() == AlertLevel.HIGH).count();
            if (criticalCount > 0) {
                sb.append(" | 🔴×").append(criticalCount);
            }
            if (highCount > 0) {
                sb.append(" | 🟠×").append(highCount);
            }
        }

        return sb.toString();
    }

    /**
     * 返回使用帮助文本。
     */
    public static String helpText() {
        return """
                内存监控命令：
                /memory         —— 查看完整内存监控报告（图形化进度条 + 告警）
                /memory diff    —— 与上次快照对比，检测异常增长
                /memory simple  —— 快速查看简版内存状态
                /memory history —— 查看历史趋势（最近 10 次快照）""";
    }

    // ═══════════════════════════════════════════════════════════════
    // 快照历史趋势跟踪
    // ═══════════════════════════════════════════════════════════════

    /** 最多保留的历史快照数 */
    private static final int MAX_HISTORY = 20;

    /** 环形历史快照队列 */
    private static final java.util.concurrent.ConcurrentLinkedDeque<MemorySnapshot> history =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /**
     * 记录一次快照到历史队列，超过 {@link #MAX_HISTORY} 时移除最旧条目。
     */
    public static void recordSnapshot(MemorySnapshot snapshot) {
        history.addLast(snapshot);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
    }

    /**
     * 获取历史快照列表（按时间升序）。
     */
    public static List<MemorySnapshot> getHistory() {
        return List.copyOf(history);
    }

    /**
     * 获取最近一次快照，无历史时返回 null。
     */
    public static MemorySnapshot getLatest() {
        return history.peekLast();
    }

    /**
     * 清除所有历史快照。
     */
    public static void clearHistory() {
        history.clear();
    }

    /**
     * 生成历史趋势简要报告（最近 N 次采样）。
     */
    public static String generateHistoryReport() {
        List<MemorySnapshot> snapshots = getHistory();
        if (snapshots.size() < 2) {
            return "历史数据不足（需要至少 2 次采样），请使用 /memory 采集更多数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("══════════ 内存历史趋势 ══════════\n");
        sb.append("共 ").append(snapshots.size()).append(" 次采样\n\n");

        // 表头
        sb.append("时间        堆使用%  堆已用     系统内存%  CPU%   线程\n");
        sb.append("──────────  ──────  ─────────  ────────  ─────  ────\n");

        DateTimeFormatter tf = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (int i = Math.max(0, snapshots.size() - 10); i < snapshots.size(); i++) {
            MemorySnapshot s = snapshots.get(i);
            String time = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(s.timestamp()), ZoneId.systemDefault())
                    .format(tf);
            double heapPct = heapUsagePercent(s);
            double sysPct = systemMemoryUsagePercent(s);

            sb.append(String.format("%s  %5s  %9s  %7s  %5s  %4d%n",
                    time,
                    heapPct >= 0 ? String.format("%.1f%%", heapPct) : "N/A",
                    formatBytes(s.heapUsed()).replace(" ", ""),
                    sysPct >= 0 ? String.format("%.1f%%", sysPct) : "N/A",
                    formatPercent(s.processCpuLoad()).replace(" ", ""),
                    s.threadCount()));
        }

        // 趋势箭头
        if (snapshots.size() >= 2) {
            MemorySnapshot first = snapshots.getFirst();
            MemorySnapshot last = snapshots.getLast();
            long heapDelta = last.heapUsed() - first.heapUsed();
            int threadDelta = last.threadCount() - first.threadCount();

            sb.append("\n━━━━━━━━ 变化趋势 ━━━━━━━━\n");
            String heapTrend = heapDelta > 5_000_000 ? "📈 增长" : heapDelta < -5_000_000 ? "📉 下降" : "➡ 稳定";
            sb.append("堆内存：").append(heapTrend).append("（")
                    .append(heapDelta >= 0 ? "+" : "").append(formatBytes(heapDelta)).append("）\n");
            sb.append("线程：").append(first.threadCount()).append(" → ")
                    .append(last.threadCount()).append("（")
                    .append(threadDelta >= 0 ? "+" : "").append(threadDelta).append("）\n");
        }

        sb.append("════════════════════════════════════════");
        return sb.toString();
    }

    /**
     * 获取 JVM 运行时长（毫秒）。
     */
    public static long getJvmUptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部辅助 —— 系统内存 / CPU（安全转型）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 尝试将标准 {@link OperatingSystemMXBean} 转型为
     * {@code com.sun.management.OperatingSystemMXBean}，
     * 转型失败返回 {@code null}。
     */
    private static com.sun.management.OperatingSystemMXBean getOsBean() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            return os;
        }
        log.debug("[MEM] OperatingSystemMXBean 无法转型到 com.sun.management，"
                + "系统内存/CPU 指标不可用 | class={}", bean.getClass().getName());
        return null;
    }

    private static long getTotalPhysicalMemory() {
        try {
            var os = getOsBean();
            return os != null ? os.getTotalMemorySize() : UNKNOWN_MEMORY;
        } catch (Exception e) {
            log.debug("[MEM] 获取物理内存总量失败: {}", e.toString());
            return UNKNOWN_MEMORY;
        }
    }

    private static long getFreePhysicalMemory() {
        try {
            var os = getOsBean();
            return os != null ? os.getFreeMemorySize() : UNKNOWN_MEMORY;
        } catch (Exception e) {
            log.debug("[MEM] 获取空闲物理内存失败: {}", e.toString());
            return UNKNOWN_MEMORY;
        }
    }

    private static long getTotalSwapSpace() {
        try {
            var os = getOsBean();
            return os != null ? os.getTotalSwapSpaceSize() : UNKNOWN_MEMORY;
        } catch (Exception e) {
            log.debug("[MEM] 获取交换空间总量失败: {}", e.toString());
            return UNKNOWN_MEMORY;
        }
    }

    private static long getFreeSwapSpace() {
        try {
            var os = getOsBean();
            return os != null ? os.getFreeSwapSpaceSize() : UNKNOWN_MEMORY;
        } catch (Exception e) {
            log.debug("[MEM] 获取空闲交换空间失败: {}", e.toString());
            return UNKNOWN_MEMORY;
        }
    }

    private static double getProcessCpuLoad() {
        try {
            var os = getOsBean();
            return os != null ? os.getProcessCpuLoad() : UNKNOWN_CPU;
        } catch (Exception e) {
            log.debug("[MEM] 获取进程 CPU 使用率失败: {}", e.toString());
            return UNKNOWN_CPU;
        }
    }

    private static double getSystemCpuLoad() {
        try {
            var os = getOsBean();
            return os != null ? os.getSystemCpuLoad() : UNKNOWN_CPU;
        } catch (Exception e) {
            log.debug("[MEM] 获取系统 CPU 使用率失败: {}", e.toString());
            return UNKNOWN_CPU;
        }
    }

    /**
     * CPU 使用率钳位到 [0, 1] 范围，将 NaN/负值归一化为 0。
     */
    private static double clampCpu(double cpuLoad) {
        if (Double.isNaN(cpuLoad) || cpuLoad < 0) return 0;
        return Math.min(cpuLoad, 1.0);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部辅助 —— GC
    // ═══════════════════════════════════════════════════════════════

    private static long getTotalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    private static long getTotalGcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) total += time;
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部辅助 —— 时间格式化
    // ═══════════════════════════════════════════════════════════════

    private static String formatTimestamp(long timestampMs) {
        LocalDateTime ldt = Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 将毫秒时长格式化为 "X天X时X分" 的可读字符串。
     */
    public static String formatUptime(long ms) {
        if (ms < 0) return "N/A";
        long totalSeconds = ms / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天");
        if (hours > 0) sb.append(hours).append("时");
        sb.append(minutes).append("分");
        return sb.toString();
    }

    /**
     * 将毫秒时长格式化为 "X分X秒"（用于对比间隔）。
     */
    public static String formatInterval(long ms) {
        if (ms <= 0) return "0秒";
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (minutes > 0) sb.append(minutes).append("分");
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    /**
     * 将毫秒时长格式化为易读格式。
     */
    public static String formatDurationMs(long ms) {
        if (ms < 0) return "N/A";
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        return formatInterval(ms);
    }

    /**
     * 数字格式化（千位以上加逗号分隔）。
     */
    private static String formatNum(long num) {
        if (num < 1000) return String.valueOf(num);
        return String.format("%,d", num);
    }
}