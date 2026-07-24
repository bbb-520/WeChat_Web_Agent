package log.demo.linkDemo.tools;

import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.tools.image.ImageCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 驱动意图分类器 —— 使用 {@code qwen-turbo}（轻量、快速、低成本）。
 *
 * <h3>v2.1 策略：LLM 优先 + 正则短路</h3>
 * <ol>
 *   <li><b>确定性规则（零延迟）</b>：仅处理格式高度固定、无歧义的指令
 *       （/ 命令、文件/图片消息、明确的天气查询）</li>
 *   <li><b>严格正则短路（带校验）</b>：仅在正则命中 <em>且</em> 通过校验
 *       （黑名单检查、上下文图片存在性检查）时才短路到快速路径</li>
 *   <li><b>LLM 分类（默认路径）</b>：其余所有请求交由 qwen-turbo 进行意图判断</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class IntentClassifier {

    @Qualifier("intentChatClient")
    private final ChatClient intentChatClient;
    private final ImageCacheManager imageCacheManager;

    // ═══════════════════════════════════════════════════════════════
    // 确定性快速路径（格式高度固定，无歧义）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 天气正则 —— 仅匹配明确的天气关键词，且前面不带"画/生成/绘制"等绘画前缀。
     * 排除："画一张天气图"、"生成天气预报图片"等。
     */
    private static final Pattern WEATHER_RE = Pattern.compile(
            "^(?!.*(画|生成|绘制|做图|画图|画一张|生成一张|做一张|来一张))"
                    + ".*(?:天气预报|天气查询|天气|温度|气温|多少度|几度|"
                    + "热不热|冷不冷|会不会下雨|有没有雨|刮风|雾霾|空气质量|"
                    + "紫外线|穿衣指数|防晒).*");

    /**
     * IMAGE_GEN 正则 —— v2.1 严格化：仅匹配含明确图片对象词的生成请求。
     * 必须包含"生成/画/绘制/做/来" + 量词 + 图片类名词。
     * 不再匹配孤立的"生成"、"画"、"绘制"等动词，避免误抓"生成代码"/"画流程图"等。
     */
    private static final Pattern IMAGE_GEN_RE = Pattern.compile(
            "(?:画|生成|绘制|做|来|帮我画|帮我生成|帮我做|帮我绘制)"
                    + "(?:一?[张个幅]|一下)"
                    + "(?:图|图片|照片|插画|头像|壁纸|logo|图标|海报|表情包|漫画|"
                    + "油画|水彩|素描|简笔画|二次元|风景|人物|动物|猫咪|狗|猫)");

    /**
     * IMAGE_EDIT 正则 —— v2.1 严格化：仅匹配含明确图片上下文的编辑动作。
     * 必须包含"这张图/那张图/图片/这张照片/那个"等明确指代待编辑图片的词。
     * 排除孤立的"修改"、"编辑"、"调整"等（这些在日常对话中太常见）。
     */
    private static final Pattern IMAGE_EDIT_RE = Pattern.compile(
            "(?:把|将|给)?"
                    + "(?:这张图|那张图|这张图片|那张图片|这张照片|那张照片|"
                    + "这个图|那个图|这个图片|那个图片|图片|照片|图)"
                    + "(?:.{0,10})"
                    + "(?:修改|改成|换成|编辑|调整|替换|添加|删除|去掉|去除|增加|"
                    + "加上|加个|换个|重绘|重新生成|改一下|修一下|P一下|P图|去水印|"
                    + "变清晰|变大|变小|裁剪|旋转|翻转|调色|加滤镜|去背景|抠图)");

    /**
     * TTS 正则 —— 仅匹配明确的语音合成请求。
     */
    private static final Pattern TTS_RE = Pattern.compile(
            "^(?:用语音|语音生成|语音合成|语音播报|朗读|播报|帮我朗读|帮我读|读一下|念一下)"
                    + "|(?:用.{1,6}(?:音|声音|声)(?:说|生成|朗读|念|读))");

    /**
     * 音色切换正则 —— 仅匹配明确的音色切换请求。
     */
    private static final Pattern VOICE_SWITCH_RE = Pattern.compile(
            "^(?:切换(?:成|到|为)?.{0,5}(?:音|声音|声)\\s*$)"
                    + "|^(?:换成.{0,5}(?:音|声音|声)\\s*$)"
                    + "|^(?:萝莉|御姐|男声|女声|少年|大叔|萝莉音|御姐音|正太音)\\s*$");

    // ═══════════════════════════════════════════════════════════════
    // 黑名单 —— 匹配以下模式的消息强制走 LLM，防止正则误判
    // ═══════════════════════════════════════════════════════════════

    /**
     * 图片生成黑名单：匹配这些模式的消息即使命中了 IMAGE_GEN_RE 也要回退到 LLM。
     * 典型场景："生成完整的 ILinkBotService 示例代码"、"画流程图"、"生成测试用例"等。
     */
    private static final Set<Pattern> IMAGE_GEN_BLACKLIST = Set.of(
            Pattern.compile(".*(?:代码|示例|服务|接口|类|方法|函数|测试|用例|文档|"
                    + "流程图|架构图|思维导图|UML|ER图|时序图|用例图|类图|"
                    + "部署图|拓扑图|网络图|组织结构图|甘特图|"
                    + "配置|SQL|脚本|命令|算法|数据结构|设计模式).*"),
            Pattern.compile(".*(?:生成|写|创建|实现|开发|编写).{0,10}(?:代码|服务|接口|类|项目|模块).*"),
            Pattern.compile(".*(?:帮我写|帮我生成|帮我创建).{0,10}(?:代码|程序|脚本|函数).*"),
            Pattern.compile(".*(?:demo|Demo|DEMO|示例代码|样例|模板|脚手架).*"),
            Pattern.compile(".*\\b(?:code|java|python|golang|rust|sql|api|sdk)\\b.*",
                    Pattern.CASE_INSENSITIVE));

    /**
     * 图片编辑黑名单：匹配这些模式的消息即使命中了 IMAGE_EDIT_RE 也要回退到 LLM。
     * 典型场景："修改代码"、"编辑文档"、"调整参数"等。
     */
    private static final Set<Pattern> IMAGE_EDIT_BLACKLIST = Set.of(
            Pattern.compile(".*(?:修改|编辑|调整|删除|添加|增加|替换).{0,10}"
                    + "(?:代码|文档|文件|配置|参数|设置|权限|密码|账号|数据|"
                    + "表格|Excel|Word|PPT|文本|文章|笔记|记录|计划|日程).*"),
            Pattern.compile(".*(?:代码|程序|脚本|配置|数据库|表|字段|索引).*"));

    // ═══════════════════════════════════════════════════════════════

    private static final String CLASSIFY_PROMPT = """
            你是一个意图分类器。将用户消息分类为以下类别之一，只输出类别名，不要解释。

            类别：
            - COMMAND：以 / 开头的命令
            - IMAGE_GEN：要求生成/画/创建图片（注意：生成代码/文档/流程图/架构图/思维导图不算IMAGE_GEN）
            - IMAGE_EDIT：要求修改/编辑/调整已有图片
            - TTS：要求用语音朗读/播报/说出文本
            - VOICE_SWITCH：要求切换音色/声音
            - WEATHER：查询天气/温度/空气质量
            - CHAT：以上都不匹配的通用对话（包括代码生成、文档编写、技术支持等）

            用户消息：\
            """;

    private static final Map<String, Intent> INTENT_MAP = Map.ofEntries(
            Map.entry("COMMAND", Intent.COMMAND),
            Map.entry("CHAT", Intent.CHAT),
            Map.entry("IMAGE_GEN", Intent.IMAGE_GEN),
            Map.entry("IMAGE_EDIT", Intent.IMAGE_EDIT),
            Map.entry("TTS", Intent.TTS),
            Map.entry("VOICE_SWITCH", Intent.VOICE_SWITCH),
            Map.entry("WEATHER", Intent.WEATHER),
            Map.entry("FILE", Intent.FILE)
    );

    public IntentClassifier(
            @Qualifier("intentChatClient") ChatClient intentChatClient,
            ImageCacheManager imageCacheManager) {
        this.intentChatClient = intentChatClient;
        this.imageCacheManager = imageCacheManager;
    }

    @PostConstruct
    public void init() {
        log.info("[INTENT-CLASSIFIER] v2.1 已初始化 | model=qwen-turbo | strategy=LLM-first+strict-regex-shortcut"
                + " | blacklistGen={} | blacklistEdit={}",
                IMAGE_GEN_BLACKLIST.size(), IMAGE_EDIT_BLACKLIST.size());
    }

    /**
     * v2.1 分类策略：确定性规则 → 严格正则（带校验）→ LLM 默认路径。
     *
     * <p>默认将所有请求先交由 LLM 进行意图判断，仅在识别出明确的、简单的意图时，
     * 才"短路"到快速执行路径。</p>
     */
    public Intent classify(AgentContext ctx) {
        // ── 层 1：确定性规则（格式高度固定，100% 可靠） ──
        if (ctx.hasText() && ctx.text().startsWith("/")) return Intent.COMMAND;
        if (ctx.hasFile()) return Intent.FILE;
        if (ctx.hasImage()) return Intent.IMAGE_EDIT;

        String text = ctx.text();
        if (text == null || text.isBlank()) return Intent.CHAT;

        // ── 层 2：严格正则 + 校验（短路到快速路径） ──
        Intent fast = strictRegexClassify(ctx);
        if (fast != Intent.CHAT) {
            log.info("[INTENT-CLASSIFIER] regex shortcut: \"{}\" → {}",
                    text.length() > 40 ? text.substring(0, 40) + "..." : text, fast);
            return fast;
        }

        // ── 层 3：LLM 默认路径 ──
        return llmClassify(text);
    }

    /**
     * 严格正则分类 —— 仅在高度确信时返回非 CHAT 意图。
     *
     * <h3>校验规则</h3>
     * <ol>
     *   <li><b>黑名单检查：</b>命中黑名单 → 回退 LLM</li>
     *   <li><b>IMAGE_EDIT 上下文校验：</b>无待编辑图片 → 回退 LLM</li>
     *   <li><b>WEATHER 绘画前缀排除：</b>含"画/生成"前缀 → 回退 LLM</li>
     * </ol>
     */
    private Intent strictRegexClassify(AgentContext ctx) {
        String text = ctx.text();

        // ── IMAGE_GEN：严格匹配 + 黑名单 ──
        if (IMAGE_GEN_RE.matcher(text).find()) {
            if (isBlacklisted(text, IMAGE_GEN_BLACKLIST)) {
                log.info("[INTENT-CLASSIFIER] IMAGE_GEN regex 命中但触发黑名单 → 回退 LLM | text=\"{}\"",
                        text.length() > 50 ? text.substring(0, 50) + "..." : text);
                return llmClassify(text);
            }
            return Intent.IMAGE_GEN;
        }

        // ── IMAGE_EDIT：严格匹配 + 黑名单 + 上下文图片校验 ──
        if (IMAGE_EDIT_RE.matcher(text).find()) {
            if (isBlacklisted(text, IMAGE_EDIT_BLACKLIST)) {
                log.info("[INTENT-CLASSIFIER] IMAGE_EDIT regex 命中但触发黑名单 → 回退 LLM | text=\"{}\"",
                        text.length() > 50 ? text.substring(0, 50) + "..." : text);
                return llmClassify(text);
            }
            // 关键校验：必须有待编辑的上下文图片
            if (!imageCacheManager.containsKey(ctx.userId())) {
                log.info("[INTENT-CLASSIFIER] IMAGE_EDIT regex 命中但无上下文图片 → 回退 LLM | userId={} | text=\"{}\"",
                        ctx.userId(),
                        text.length() > 50 ? text.substring(0, 50) + "..." : text);
                return llmClassify(text);
            }
            return Intent.IMAGE_EDIT;
        }

        // ── TTS：严格匹配（前置锚点 ^ 已确保精确度） ──
        if (TTS_RE.matcher(text).find()) return Intent.TTS;

        // ── VOICE_SWITCH：严格匹配（前置锚点 ^ 已确保精确度） ──
        if (VOICE_SWITCH_RE.matcher(text).find()) return Intent.VOICE_SWITCH;

        // ── WEATHER：关键词匹配（排除绘画前缀） ──
        if (WEATHER_RE.matcher(text).find()) return Intent.WEATHER;

        return Intent.CHAT;
    }

    /**
     * 检查文本是否匹配任一黑名单模式。
     */
    private boolean isBlacklisted(String text, Set<Pattern> blacklist) {
        for (Pattern p : blacklist) {
            if (p.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM 分类 —— 处理模糊/自然语言意图（默认路径）。
     */
    private Intent llmClassify(String text) {
        try {
            String result = intentChatClient.prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(text)
                    .call()
                    .content();

            if (result != null) {
                String cleaned = result.trim().toUpperCase();
                // 提取第一个匹配的意图词（容错：模型可能输出多余文本）
                for (String key : INTENT_MAP.keySet()) {
                    if (cleaned.contains(key)) {
                        Intent intent = INTENT_MAP.get(key);
                        log.debug("[INTENT-CLASSIFIER] LLM: \"{}\" → {}", text.length() > 40
                                ? text.substring(0, 40) + "..." : text, intent);
                        return intent;
                    }
                }
                log.debug("[INTENT-CLASSIFIER] LLM 返回未知标签: \"{}\"，回退 CHAT", cleaned);
            }
        } catch (Exception e) {
            log.warn("[INTENT-CLASSIFIER] LLM 调用失败，回退 CHAT | text=\"{}\" | error={}",
                    text.length() > 30 ? text.substring(0, 30) + "..." : text, e.getMessage());
        }
        return Intent.CHAT;
    }
}
