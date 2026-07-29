package log.summer.aigc.tool.voice;

import jakarta.annotation.PostConstruct;
import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.entity.VoiceResult;
import log.summer.common.enums.Timbre;
import log.summer.common.exception.VoiceSynthesisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * TTS 语音合成工具 —— CosyVoice + 音色切换。
 *
 * <p>提供两个工具方法：
 * <ul>
 *   <li>{@code tts} — 文字转语音，音频文件自动下发用户</li>
 *   <li>{@code voice_switch} — 切换音色（按关键词或编号）</li>
 * </ul>
 *
 * <h3>BUG FIX (2026-07-29)</h3>
 * Previously hard-coded {@code userId = "default"} for both the
 * {@code TimbreSession} lookup and the {@code MessageSender.sendFile}
 * call, which meant the synthesised audio was never delivered to the
 * real user and the voice preference was shared globally. The real
 * {@code userId} is now read from {@link UserContextHolder} which
 * {@code AgentLoop} populates before invoking any tool.
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TtsTool {

    private final TTSEngine ttsEngine;
    private final TimbreSession timbreSession;
    private final MessageSender messageSender;

    @PostConstruct
    void init() {
        log.info("[TTS-TOOL] ✅ 已初始化 | deps: TTSEngine={}, TimbreSession={}, MessageSender={}",
                ttsEngine != null, timbreSession != null, messageSender != null);
    }

    @Tool(name = "tts", description = "将文字转为语音朗读，当用户说'用语音朗读/生成语音/播报/朗读/读一下'时调用。" +
            "语音文件会自动下发给用户，无需额外处理。")
    public ActResult tts(
            @ToolParam(required = true, description = "需要转为语音朗读的文本内容") String text) {

        if (text == null || text.isBlank()) {
            return ActResult.failure("请提供需要朗读的文本内容");
        }

        try {
            String userId = UserContextHolder.getUserId();

            // 提取纯 TTS 文本，去掉触发词
            String ttsText = extractTtsText(text);
            if (ttsText.isBlank()) {
                return ActResult.failure("未检测到需要朗读的文本，请在指令后提供内容，例如：「朗读 你好世界」");
            }

            String voiceId = timbreSession.getCurrentVoiceId(userId);
            log.info("[TTS] 开始合成 | userId={} | voiceId={} | textLen={}", userId, voiceId, ttsText.length());

            VoiceResult result = ttsEngine.synthesize(ttsText, voiceId);

            if (result.hasAudio()) {
                messageSender.sendFile(userId, result.wavAudio(),
                        "tts-" + System.currentTimeMillis() + ".wav", ttsText);
                log.info("[TTS] 合成完成并已发送 | userId={} | duration={}ms",
                        userId, result.durationMs());
                return ActResult.success("语音已发送（" + result.durationMs() + "ms）");
            }
            return ActResult.failure("语音生成失败：服务返回空数据，请稍后重试");
        } catch (VoiceSynthesisException e) {
            log.error("[TTS] 合成失败 | text={}", text, e);
            return ActResult.failure("语音生成失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[TTS] 合成异常 | text={}", text, e);
            return ActResult.failure("语音生成异常：" + e.getMessage());
        }
    }

    @Tool(name = "voice_switch", description = "切换语音合成音色。当用户说'换音色/切换声音/萝莉音/男声/女声'时调用。" +
            "支持12种音色：龙小春、龙小夏、龙玉香、龙程、龙书、龙少、龙婉、龙梅、龙夜、龙月、龙翔、Bella")
    public ActResult switchVoice(
            @ToolParam(required = true, description = "音色关键词或编号，例如 '男声'、'女声'、'萝莉'、'1'、'Bella' 等") String voice) {

        if (voice == null || voice.isBlank()) {
            return ActResult.failure("请指定要切换的音色，例如「男声」「女声」「萝莉」");
        }

        try {
            String userId = UserContextHolder.getUserId();

            // 按关键词匹配
            Timbre t = Timbre.matchKeyword(voice).orElse(null);
            if (t != null) {
                timbreSession.switchTo(userId, t);
                log.info("[TTS] 音色切换 | userId={} | voice={}", userId, t.getDisplayName());
                return ActResult.success("已切换到音色：" + t.getDisplayName() + " —— " + t.getDescription());
            }

            // 尝试按编号切换
            try {
                int number = Integer.parseInt(voice.trim());
                Timbre byNumber = Timbre.byNumber(number).orElse(null);
                if (byNumber != null) {
                    timbreSession.switchTo(userId, byNumber);
                    log.info("[TTS] 音色切换（按编号）| userId={} | number={} | voice={}",
                            userId, number, byNumber.getDisplayName());
                    return ActResult.success("已切换到音色：" + byNumber.getDisplayName() + " —— " + byNumber.getDescription());
                }
            } catch (NumberFormatException ignored) {
            }

            return ActResult.failure("未找到匹配的音色：「" + voice
                    + "」。可用音色：1.龙小春 2.龙小夏 3.龙玉香 4.龙程 5.龙书 "
                    + "6.龙少 7.龙婉 8.龙梅 9.龙夜 10.龙月 11.龙翔 12.Bella");
        } catch (Exception e) {
            log.error("[TTS] 切换音色失败 | voice={}", voice, e);
            return ActResult.failure("切换音色失败：" + e.getMessage());
        }
    }

    /**
     * 从意图文本中剥离 TTS 触发词，提取要朗读的内容。
     */
    private String extractTtsText(String text) {
        if (text == null) return "";
        return text.replaceAll("^(用语音|语音生成|语音合成|朗读|播报|说|生成语音|合成语音)\\s*", "").trim();
    }
}
