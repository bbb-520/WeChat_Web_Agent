package log.demo.linkDemo.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 驱动意图分类器 —— 使用 {@code qwen-turbo}（轻量、快速）。
 *
 * <h3>v2.3 策略：确定性规则 + 单次 LLM 调用</h3>
 * <ol>
 *   <li><b>确定性规则（零延迟）</b>：/ 命令、文件/图片消息</li>
 *   <li><b>单次 LLM 分类</b>：其余全部交由 qwen-turbo，精简 prompt 降低 token 消耗</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    //用于意图分类的轻量 ChatClient
    @Qualifier("intentChatClient")
    private final ChatClient intentChatClient;
    //JSON 解析工具
    private static final Gson GSON = new Gson();

    /**
     * 精简 System Prompt —— 仅列出分类规则，要求输出 JSON。
     */
    private static final String PROMPT = """
            分类用户消息，仅输出JSON: {"intent":"类型"}
            类型:
            IMAGE_GEN=生成/画图片(排除代码/文档/流程图/架构图)
            IMAGE_EDIT=修改已有图片(排除修改代码/编辑文档)
            TTS=语音朗读
            VOICE_SWITCH=切换音色
            WEATHER=天气查询
            CHAT=以上都不匹配(含闲聊/代码/文档/翻译等)
            消息: \
            """;
    //意图字符串 → 枚举映射
    private static final Map<String, Intent> INTENT_MAP = Map.of(
            "COMMAND", Intent.COMMAND,
            "CHAT", Intent.CHAT,
            "IMAGE_GEN", Intent.IMAGE_GEN,
            "IMAGE_EDIT", Intent.IMAGE_EDIT,
            "TTS", Intent.TTS,
            "VOICE_SWITCH", Intent.VOICE_SWITCH,
            "WEATHER", Intent.WEATHER,
            "FILE", Intent.FILE
    );


    @PostConstruct
    public void init() {
        log.info("[INTENT-CLASSIFIER] v2.3 | model=qwen-turbo | single-LLM-call | short-prompt");
    }

    /**
     * 意图分类入口 —— 先规则，后 LLM
     */
    public Intent classify(AgentContext ctx) {
        // 1.确定性规则（零延迟）
        //1.1如果有文本且以 / 开头 → 直接返回 COMMAND，不调 AI
        if (ctx.hasText() && ctx.text().startsWith("/")) return Intent.COMMAND;
        //1.2如果包含文件 → 返回 FILE。
        if (ctx.hasFile()) return Intent.FILE;
        //1.3如果包含图片 → 返回 IMAGE_EDIT
        if (ctx.hasImage()) return Intent.IMAGE_EDIT;

        String text = ctx.text();
        //1.4如果文本为空 → 返回 CHAT
        if (text == null || text.isBlank()) return Intent.CHAT;

        // 单次 LLM 调用
        return llmClassify(text);
    }

    /**
     * 单次 LLM 分类 —— 精简 prompt + JSON 解析 + 文本兜底。
     * 失败直接降级 CHAT，不再发起第二次 LLM 调用。
     */
    private Intent llmClassify(String text) {

        //截断长文本
        String truncated = text.length() > 50 ? text.substring(0, 50) + "..." : text;

        try {
            //调用轻量级 LLM（qwen-turbo）对用户消息进行意图分类
            //大模型通过text内容与PROMPT提示词进行意图分析并以JSON的形式生成
            String raw = intentChatClient.prompt()
                    .system(PROMPT)
                    .user(text)
                    .call()
                    .content();

            //若调用过程中抛出异常，直接降级为 CHAT。
            if (raw == null) {
                log.debug("[INTENT] LLM 返回 null → CHAT | text=\"{}\"", truncated);
                return Intent.CHAT;
            }

            //清洗得到的结果
            String cleaned = raw.trim();

            // 优先 JSON 解析
            Intent parsed = parseJson(cleaned);
            if (parsed != null) {
                log.debug("[INTENT] JSON: \"{}\" → {}", truncated, parsed);
                return parsed;
            }

            // JSON 失败 → 从同一响应中提取关键词（不发起第二次调用）
            String upper = cleaned.toUpperCase();   //转化成大写
            for (String key : INTENT_MAP.keySet()) {
                if (upper.contains(key)) {
                    Intent intent = INTENT_MAP.get(key);
                    log.debug("[INTENT] text-fallback: \"{}\" → {}", truncated, intent);
                    return intent;
                }
            }

            log.debug("[INTENT] 未识别: \"{}\" raw=\"{}\" → CHAT", truncated,
                    cleaned.length() > 40 ? cleaned.substring(0, 40) + "..." : cleaned);

        } catch (Exception e) {
            log.warn("[INTENT] LLM 异常 → CHAT | text=\"{}\" | {}", truncated, e.getMessage());
        }

        return Intent.CHAT;
    }

    /**
     * 解析 JSON 响应，去除可能的 markdown 代码块包裹。
     */
    private Intent parseJson(String raw) {
        if (raw.isBlank()) return null;
        try {
            //1.去除 ```json ... ``` 包裹
            String json = raw;
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start >= 0 && end > start) json = json.substring(start, end).trim();
                else json = json.replaceAll("```json\\s*|```", "").trim();
            }
            //2.解析 JSON
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            //3.提取intent字段
            if (obj.has("intent")) {
                String name = obj.get("intent").getAsString().trim().toUpperCase();
                //4.通过映射表返回 Intent 枚举
                Intent intent = INTENT_MAP.get(name);
                return intent; // null if unknown → caller falls through to text-fallback
            }
        } catch (Exception ignored) {
            // JSON 解析失败 → 调用者会尝试文本兜底
        }
        return null;
    }
}
