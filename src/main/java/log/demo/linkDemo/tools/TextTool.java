package log.demo.linkDemo.tools;

/**
 * 文本处理静态工具 —— 字符串截断、TTS 文本提取、音色前缀剥离。
 *
 * @author bbb
 * @since 2026-07-23
 */
public final class TextTool {

    private TextTool() {}

    /** 安全截断字符串，超长时追加 "..." */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /** 从意图文本中剥离 TTS 触发词，提取要朗读的内容 */
    public static String extractTtsText(String text) {
        String result = text
                .replaceFirst(".*?(语音(?:生成|输出|播放)|生成语音|朗读|播报)[，。！？、,.!?\\s]*", "")
                .replaceFirst(".*?用.{1,8}(音|声音|声)(说|讲|生成|朗读|念|回答|回复|输出|播放)[，。！？、,.!?\\s]*", "")
                .trim();
        if (!result.isEmpty()) result = result.replaceFirst("^[，。！？、,.!?\\s]+", "").trim();
        return result.isEmpty() ? text.trim() : result;
    }

    /** 剥离音色切换前缀，返回剩余文本 */
    public static String stripTimbrePrefix(String text) {
        String r = text.replaceFirst("^用.{1,8}(音|声音|声)(说|讲|回答|回复|生成|朗读)\\s*", "");
        r = r.replaceFirst("^(切换成|换成|改成).{1,6}音\\s*", "");
        return r.isEmpty() ? text : r;
    }
}
