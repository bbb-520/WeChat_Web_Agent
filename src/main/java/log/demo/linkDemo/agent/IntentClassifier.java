package log.demo.linkDemo.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * LLM 驱动意图分类器 —— 使用 {@code qwen-turbo}（轻量、快速、低成本）。
 *
 * <h3>双层策略</h3>
 * <ol>
 *   <li><b>快速路径（正则）</b>：确定性规则直接匹配，无需 LLM 调用</li>
 *   <li><b>LLM 分类</b>：qwen-turbo 处理模糊/自然语言意图</li>
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

    // ── 快速路径正则（零延迟，100% 可靠） ──
    private static final Pattern WEATHER_RE = Pattern.compile(
            "(天气|温度|气温|热不热|冷不冷|会不会下雨|有没有雨|多少度|几度|刮风|雾霾|空气质量|天气预报)");
    private static final Pattern IMAGE_GEN_RE = Pattern.compile(
            "(画|生成|绘制|做图|画图|画一张|生成一张|做一张|来一张)"
                    + "(一?[张个幅]?.{0,20}|$)");
    // 只保留明确的双字编辑动作词，排除单字（改/换/变/加）防止误判
    // "改变/变化/加油/更加..." 不应触发图片编辑路由
    private static final Pattern IMAGE_EDIT_RE = Pattern.compile(
            "(修改|改成|换成|编辑|调整|替换|添加|删除|去掉|去除|增加|加上|加个|换个|重绘|重新生成)"
                    + ".{1,15}");
    private static final Pattern TTS_RE = Pattern.compile(
            "(用语音|语音生成|语音合成|朗读|播报|用.{1,6}(音|声)(说|生成|朗读))");
    private static final Pattern VOICE_SWITCH_RE = Pattern.compile(
            "(用.{1,6}(音|声音|声)\\s*$)|(切换.{0,5}音)|(换成.{0,5}音)|(萝莉|御姐|男声|女声|少年)");

    private static final String CLASSIFY_PROMPT = """
            你是一个意图分类器。将用户消息分类为以下类别之一，只输出类别名，不要解释。

            类别：
            - COMMAND：以 / 开头的命令
            - IMAGE_GEN：要求生成/画/创建图片
            - IMAGE_EDIT：要求修改/编辑/调整已有图片
            - TTS：要求用语音朗读/播报/说出文本
            - VOICE_SWITCH：要求切换音色/声音
            - WEATHER：查询天气/温度/空气质量
            - CHAT：以上都不匹配的通用对话

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
            @Qualifier("intentChatClient") ChatClient intentChatClient) {
        this.intentChatClient = intentChatClient;
    }

    @PostConstruct
    public void init() {
        log.info("[INTENT-CLASSIFIER] 已初始化 | model=qwen-turbo | strategy=regex+LLM");
    }

    /**
     * 双层分类：快速正则 → LLM 回退。
     */
    public Intent classify(AgentContext ctx) {
        // ── 确定性快速路径 ──
        if (ctx.hasText() && ctx.text().startsWith("/")) return Intent.COMMAND;
        if (ctx.hasFile()) return Intent.FILE;
        if (ctx.hasImage()) return Intent.IMAGE_EDIT;

        String text = ctx.text();
        if (text == null || text.isBlank()) return Intent.CHAT;

        // ── 正则快速路径 ──
        Intent fast = regexClassify(text);
        if (fast != Intent.CHAT) {
            log.info("[INTENT-CLASSIFIER] regex fast-path: \"{}\" → {}",
                    text.length() > 40 ? text.substring(0, 40) + "..." : text, fast);
            return fast;
        }

        // ── LLM 回退 ──
        return llmClassify(text);
    }

    /**
     * 正则快速分类 —— 处理明确的关键词意图。
     * <p>
     * <b>顺序重要：</b>前缀信号（IMAGE_GEN/IMAGE_EDIT/TTS）优先于
     * 关键词信号（WEATHER），避免"画一张天气图"被误判为天气查询。
     */
    private Intent regexClassify(String text) {
        // 前缀信号优先（强信号）
        if (IMAGE_GEN_RE.matcher(text).find()) return Intent.IMAGE_GEN;
        if (IMAGE_EDIT_RE.matcher(text).find()) return Intent.IMAGE_EDIT;
        if (TTS_RE.matcher(text).find()) return Intent.TTS;
        if (VOICE_SWITCH_RE.matcher(text).find()) return Intent.VOICE_SWITCH;
        // 关键词信号（弱信号，放最后）
        if (WEATHER_RE.matcher(text).find()) return Intent.WEATHER;
        return Intent.CHAT;
    }

    /**
     * LLM 分类 —— 处理模糊/自然语言意图。
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
