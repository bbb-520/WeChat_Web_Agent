package log.summer.common.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 音色枚举 —— 12 种 DashScope CosyVoice 内置音色。
 * <p>
 * 每种音色包含：编号、中文名、描述、API voice ID、用于自然语言匹配的关键词。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 *   Timbre t = Timbre.byNumber(3);                     // 按编号查找
 *   Timbre t = Timbre.matchKeyword("用萝莉音说你好");    // 按关键词匹配
 * </pre>
 *
 * @author bbb
 * @since 2026-07-21
 */
@Getter
public enum Timbre {

    LONG_XIAO_CHUN(1, "龙小春", "温柔知性女声，适合日常对话", "longxiaochun",
            "小春", "默认", "温柔", "女声"),
    LONG_XIAO_XIA(2, "龙小夏", "活泼元气少女音", "longxiaoxia",
            "小夏", "活泼", "元气", "少女", "萝莉"),
    LONG_YU_XIANG(3, "龙玉香", "清脆明亮女声，适合朗读", "longyuxiang",
            "玉香", "清脆", "朗读", "女声"),
    LONG_CHENG(4, "龙程", "沉稳磁性男声", "longcheng",
            "龙程", "沉稳", "磁性", "大叔", "男声"),
    LONG_SHU(5, "龙书", "知性儒雅男声", "longshu",
            "龙书", "知性", "儒雅", "书生", "男声"),
    LONG_SHAO(6, "龙少", "清亮少年音", "longshao",
            "龙少", "少年", "清亮", "正太", "男声", "男孩"),
    LONG_WAN(7, "龙婉", "温婉柔和女声", "longwan",
            "龙婉", "温婉", "柔和", "女声"),
    LONG_MEI(8, "龙梅", "甜美亲切女声", "longmei",
            "龙梅", "甜美", "亲切", "女声"),
    LONG_YE(9, "龙夜", "低沉深邃男声", "longye",
            "龙夜", "低沉", "深邃", "男声"),
    LONG_YUE(10, "龙月", "清冷御姐音", "longyue",
            "龙月", "清冷", "御姐", "女声"),
    LONG_XIANG(11, "龙翔", "阳光开朗男声", "longxiang",
            "龙翔", "阳光", "开朗", "男声"),
    LOONG_BELLA(12, "Bella", "优雅英文女声", "loongbella",
            "Bella", "英文", "优雅", "国际", "女声"),
    ;

    /** 用户可见编号 */
    private final int number;
    /** 中文名 */
    private final String displayName;
    /** 音色描述 */
    private final String description;
    /** DashScope API voice ID */
    private final String voiceId;
    /** 自然语言匹配关键词 */
    private final List<String> keywords;

    Timbre(int number, String displayName, String description,
           String voiceId, String... keywords) {
        this.number = number;
        this.displayName = displayName;
        this.description = description;
        this.voiceId = voiceId;
        this.keywords = Arrays.asList(keywords);
    }

    // 静态查询

    /** 按编号查找 */
    public static Optional<Timbre> byNumber(int number) {
        return Arrays.stream(values())
                .filter(t -> t.number == number)
                .findFirst();
    }

    /** 按 voiceId 查找 */
    public static Optional<Timbre> byVoiceId(String voiceId) {
        return Arrays.stream(values())
                .filter(t -> t.voiceId.equalsIgnoreCase(voiceId))
                .findFirst();
    }

    /**
     * 按关键词匹配 —— 在用户文本中搜索所有音色的关键词，
     * 返回第一个匹配到的音色。
     *
     * @param text 用户输入文本
     * @return 匹配到的音色，未匹配则 {@link Optional#empty()}
     */
    public static Optional<Timbre> matchKeyword(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String lower = text.toLowerCase();
        return Arrays.stream(values())
                .filter(t -> t.keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase())))
                .findFirst();
    }

    /**
     * 匹配音色切换意图 —— 检测文本是否包含切换音色的触发词。
     * <p>注意："换成"/"改成"与图片编辑关键词冲突，不在此处匹配，
     * 改为通过 {@link #matchKeyword(String)} 精确匹配音色关键词来判断。</p>
     */
    public static boolean isTimbreSwitchIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("音说") || text.contains("音讲")
                || text.contains("音回答") || text.contains("音回复")
                || text.contains("切换") || text.contains("音色")
                || text.contains("声音");  // "用男孩声音生成..."
    }

    /** 格式化的音色列表消息 */
    public static String formatList() {
        StringBuilder sb = new StringBuilder("🎤 可用音色列表：\n\n");
        for (Timbre t : values()) {
            sb.append(String.format("  %2d. %s —— %s\n", t.number, t.displayName, t.description));
        }
        sb.append("\n💡 使用方法：\n");
        sb.append("  /voice <编号> —— 切换音色\n");
        sb.append("  /voice list    —— 查看列表\n");
        sb.append("  直接说 \"用<关键词>音说…\" 也可切换\n");
        return sb.toString();
    }

    /** 默认音色 */
    public static Timbre defaultTimbre() {
        return LONG_XIAO_CHUN;
    }
}
