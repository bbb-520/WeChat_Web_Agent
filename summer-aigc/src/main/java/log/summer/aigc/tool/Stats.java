package log.summer.aigc.tool;

/**
 * Snapshot of runtime statistics.
 */
public record Stats(String uptime, long messageCount, long errorCount) {}
