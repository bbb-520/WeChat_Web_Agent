package log.summer.aigc.tool.memory;

import log.summer.aigc.loop.ActResult;
import log.summer.aigc.tool.BotMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

/**
 * 服务器内存/运行时状态查询工具 —— 基于 JVM MBean 采集指标。
 *
 * <p>提供运行时内存、CPU、线程、GC 等指标的查询与格式化报告。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class MemoryStatusTool {

    @Autowired(required = false)
    private BotMetrics botMetrics;

    private static final int BAR_WIDTH = 20;
    private static final char BLOCK_FULL = '█';
    private static final char BLOCK_DENSE = '▓';
    private static final char BLOCK_SPARSE = '▒';
    private static final char BLOCK_EMPTY = '░';

    // ═══════════════════════════════════════════════════════════════
    // @Tool 方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询 bot 运行时状态（内存、CPU、线程、GC、JVM 运行时长等）。
     *
     * @return 运行时状态报告
     */
    @Tool(name = "memory_status", description = "查询bot运行时状态，包括JVM堆内存/非堆内存使用、系统物理内存、CPU使用率、线程数、GC统计、JVM运行时长等指标的可视化报告。")
    public ActResult memoryStatus() {
        try {
            MemorySnapshot snapshot = collectSnapshot();
            String report = generateReport(snapshot);
            return ActResult.success(report);
        } catch (Exception e) {
            log.error("[MEM-STATUS] 采集内存状态失败", e);
            return ActResult.failure("内存状态采集失败：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据采集
    // ═══════════════════════════════════════════════════════════════

    private MemorySnapshot collectSnapshot() {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();

        return new MemorySnapshot(
                System.currentTimeMillis(),
                heapUsage.getUsed(),
                heapUsage.getMax(),
                heapUsage.getCommitted(),
                nonHeapUsage.getUsed(),
                nonHeapUsage.getCommitted(),
                getTotalPhysicalMemory(),
                getFreePhysicalMemory(),
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
    // 报告生成
    // ═══════════════════════════════════════════════════════════════

    private String generateReport(MemorySnapshot s) {
        StringBuilder sb = new StringBuilder();

        sb.append("══════════ 服务器内存监控报告 ══════════\n");
        sb.append("⏱ 采集时间：").append(formatTimestamp(s.timestamp())).append("\n");
        sb.append("🕐 JVM 运行：").append(formatUptime(getJvmUptimeMs())).append("\n");

        // 堆内存
        double heapPct = heapUsagePercent(s);
        sb.append("\n━━━━━━━━━ 堆内存 (Heap) ━━━━━━━━━\n");
        if (heapPct >= 0) {
            sb.append(buildProgressBar(heapPct)).append("\n");
        }
        sb.append("已用：").append(formatBytes(s.heapUsed()));
        if (s.heapMax() > 0) {
            sb.append(" / 最大：").append(formatBytes(s.heapMax()));
        }
        sb.append("  |  提交：").append(formatBytes(s.heapCommitted())).append("\n");

        // 非堆内存
        sb.append("\n━━━━━━ 非堆内存 (Non-Heap) ━━━━━━\n");
        sb.append("已用：").append(formatBytes(s.nonHeapUsed()));
        sb.append("  |  提交：").append(formatBytes(s.nonHeapCommitted())).append("\n");

        // 系统内存
        sb.append("\n━━━━━━━━ 系统内存 (OS) ━━━━━━━━━\n");
        if (s.totalPhysical() > 0) {
            double sysPct = systemMemoryUsagePercent(s);
            if (sysPct >= 0) {
                sb.append(buildProgressBar(sysPct)).append("\n");
            }
            long used = s.totalPhysical() - s.freePhysical();
            sb.append("已用：").append(formatBytes(used));
            sb.append(" / 总量：").append(formatBytes(s.totalPhysical())).append("\n");
        } else {
            sb.append("（系统内存信息不可用）\n");
        }

        // CPU / GC / 线程
        sb.append("\n━━━━━━ CPU / GC / 线程 ━━━━━━━━\n");
        sb.append("进程 CPU：").append(formatPercent(s.processCpuLoad()));
        sb.append("  |  系统 CPU：").append(formatPercent(s.systemCpuLoad())).append("\n");
        sb.append("GC 次数：").append(formatNum(s.gcCount()));
        sb.append("  |  GC 耗时：").append(formatDurationMs(s.gcTimeMs()));
        sb.append("  |  线程：").append(s.threadCount()).append("\n");

        // 告警
        sb.append("\n━━━━━━━━━━ 告警 ━━━━━━━━━━━━\n");
        String alertInfo = detectAlerts(s);
        sb.append(alertInfo);

        sb.append("════════════════════════════════════════");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 告警检测（简化版）
    // ═══════════════════════════════════════════════════════════════

    private String detectAlerts(MemorySnapshot s) {
        StringBuilder sb = new StringBuilder();
        boolean hasAlert = false;

        double heapPct = heapUsagePercent(s);
        if (heapPct >= 0) {
            if (heapPct >= 95) {
                sb.append("🔴 [严重] 堆内存使用率 ").append(formatPercent(heapPct / 100))
                        .append("，超过 95%\n");
                hasAlert = true;
            } else if (heapPct >= 80) {
                sb.append("🟠 [警告] 堆内存使用率 ").append(formatPercent(heapPct / 100))
                        .append("，超过 80%\n");
                hasAlert = true;
            }
        }

        double sysPct = systemMemoryUsagePercent(s);
        if (sysPct >= 95) {
            sb.append("🔴 [严重] 系统内存使用率 ").append(formatPercent(sysPct / 100)).append("\n");
            hasAlert = true;
        } else if (sysPct >= 85) {
            sb.append("🟠 [警告] 系统内存使用率 ").append(formatPercent(sysPct / 100)).append("\n");
            hasAlert = true;
        }

        if (s.processCpuLoad() >= 0.9) {
            sb.append("🟠 [警告] 进程 CPU 使用率 ").append(formatPercent(s.processCpuLoad())).append("\n");
            hasAlert = true;
        }

        if (!hasAlert) {
            sb.append("🟢 系统运行正常，所有指标均在安全范围内\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        long abs = Math.abs(bytes);
        if (abs >= 1099511627776L) return String.format("%.1f TB", bytes / 1099511627776.0);
        if (abs >= 1073741824) return String.format("%.1f GB", bytes / 1073741824.0);
        if (abs >= 1048576) return String.format("%.1f MB", bytes / 1048576.0);
        if (abs >= 1024) return String.format("%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private String formatPercent(double ratio) {
        if (ratio < 0) return "N/A";
        return String.format("%.1f%%", ratio * 100.0);
    }

    private String buildProgressBar(double percentage) {
        int width = BAR_WIDTH;
        double pct = Math.max(0, Math.min(100, percentage));
        double filledUnits = pct * width / 100.0;
        int fullBlocks = (int) filledUnits;
        double remainder = filledUnits - fullBlocks;
        int emptyBlocks = width - fullBlocks - (remainder > 0 ? 1 : 0);

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < fullBlocks; i++) sb.append(BLOCK_FULL);
        if (remainder > 0) {
            if (remainder >= 0.75) sb.append(BLOCK_FULL);
            else if (remainder >= 0.5) sb.append(BLOCK_DENSE);
            else if (remainder >= 0.25) sb.append(BLOCK_SPARSE);
            else sb.append(BLOCK_EMPTY);
        }
        for (int i = 0; i < emptyBlocks; i++) sb.append(BLOCK_EMPTY);
        sb.append("] ").append(formatPercent(pct / 100.0));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 使用率计算
    // ═══════════════════════════════════════════════════════════════

    private double heapUsagePercent(MemorySnapshot s) {
        if (s.heapMax() <= 0) return -1.0;
        return s.heapUsed() * 100.0 / s.heapMax();
    }

    private double systemMemoryUsagePercent(MemorySnapshot s) {
        if (s.totalPhysical() <= 0) return -1.0;
        long used = s.totalPhysical() - s.freePhysical();
        return used * 100.0 / s.totalPhysical();
    }

    // ═══════════════════════════════════════════════════════════════
    // 系统指标获取
    // ═══════════════════════════════════════════════════════════════

    private com.sun.management.OperatingSystemMXBean getOsBean() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) return os;
        return null;
    }

    private long getTotalPhysicalMemory() {
        try { var os = getOsBean(); return os != null ? os.getTotalMemorySize() : -1L; }
        catch (Exception e) { return -1L; }
    }

    private long getFreePhysicalMemory() {
        try { var os = getOsBean(); return os != null ? os.getFreeMemorySize() : -1L; }
        catch (Exception e) { return -1L; }
    }

    private long getTotalSwapSpace() {
        try { var os = getOsBean(); return os != null ? os.getTotalSwapSpaceSize() : -1L; }
        catch (Exception e) { return -1L; }
    }

    private long getFreeSwapSpace() {
        try { var os = getOsBean(); return os != null ? os.getFreeSwapSpaceSize() : -1L; }
        catch (Exception e) { return -1L; }
    }

    private double getProcessCpuLoad() {
        try { var os = getOsBean(); return os != null ? os.getProcessCpuLoad() : -1.0; }
        catch (Exception e) { return -1.0; }
    }

    private double getSystemCpuLoad() {
        try { var os = getOsBean(); return os != null ? os.getSystemCpuLoad() : -1.0; }
        catch (Exception e) { return -1.0; }
    }

    private double clampCpu(double cpuLoad) {
        if (Double.isNaN(cpuLoad) || cpuLoad < 0) return 0;
        return Math.min(cpuLoad, 1.0);
    }

    private long getTotalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    private long getTotalGcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) total += time;
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════════════
    // 时间格式化
    // ═══════════════════════════════════════════════════════════════

    private String formatTimestamp(long timestampMs) {
        LocalDateTime ldt = Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String formatUptime(long ms) {
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

    private String formatDurationMs(long ms) {
        if (ms < 0) return "N/A";
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (minutes > 0) sb.append(minutes).append("分");
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    private long getJvmUptimeMs() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }

    private String formatNum(long num) {
        if (num < 1000) return String.valueOf(num);
        return String.format("%,d", num);
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据类型
    // ═══════════════════════════════════════════════════════════════

    private record MemorySnapshot(
            long timestamp,
            long heapUsed, long heapMax, long heapCommitted,
            long nonHeapUsed, long nonHeapCommitted,
            long totalPhysical, long freePhysical,
            long totalSwap, long freeSwap,
            double processCpuLoad, double systemCpuLoad,
            int threadCount, long gcCount, long gcTimeMs) {}
}
