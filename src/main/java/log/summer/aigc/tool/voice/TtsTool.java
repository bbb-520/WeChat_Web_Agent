package log.summer.aigc.tool.voice;

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

    @Tool(name = "tts", description = "将文字转为语音朗读，当用户说'用语音朗读/生成语音/播报/朗读'时调用")
    public ActResult tts(
            @ToolParam(description = "需要转为语音的文本内容") String text) {
        return synthesize(text);
    }

    @Tool(name = "tts_synthesize", description = "将文字转为语音朗读")
    public ActResult synthesize(
            @ToolParam(description = "需要转为语音的文本内容") String text) {

        if (text == null || text.isBlank()) {
            return ActResult.failure("合成文本不能为空");
        }

        try {
            // BUG FIX: read the real user id from the per-request context.
            String userId = UserContextHolder.getUserId();

            // 提取纯 TTS 文本，去掉触发词
            String ttsText = extractTtsText(text);
            String voiceId = timbreSession.getCurrentVoiceId(userId);
            VoiceResult result = ttsEngine.synthesize(ttsText, voiceId);

            if (result.hasAudio()) {
                // BUG FIX: deliver the audio to the real user, not "default".
                messageSender.sendFile(userId, result.wavAudio(),
                        "tts-" + System.currentTimeMillis() + ".wav", ttsText);
                return ActResult.success("语音已发送");
            }
            return ActResult.failure("语音生成失败：返回空数据");
        } catch (VoiceSynthesisException e) {
            log.error("[TTS] 合成失败 | text={}", text, e);
            return ActResult.failure("语音生成失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[TTS] 合成异常 | text={}", text, e);
            return ActResult.failure("语音生成异常：" + e.getMessage());
        }
    }

    @Tool(name = "voice_switch", description = "切换语音合成使用的音色")
    public ActResult switchVoice(
            @ToolParam(description = "音色关键词，例如 '男声'、'女声'、'活泼'、'萝莉'、'深沉'、'温柔' 等") String voice) {

        if (voice == null || voice.isBlank()) {
            return ActResult.failure("请指定要切换的音色，例如「男声」「女声」「萝莉」");
        }

        try {
            // BUG FIX: per-user voice preferences.
            String userId = UserContextHolder.getUserId();

            Timbre t = Timbre.matchKeyword(voice).orElse(null);
            if (t != null) {
                timbreSession.switchTo(userId, t);
                return ActResult.success("已切换到音色：" + t.getDisplayName() + " —— " + t.getDescription());
            }

            // 尝试按编号切换
            try {
                int number = Integer.parseInt(voice.trim());
                Timbre byNumber = Timbre.byNumber(number).orElse(null);
                if (byNumber != null) {
                    timbreSession.switchTo(userId, byNumber);
                    return ActResult.success("已切换到音色：" + byNumber.getDisplayName() + " —— " + byNumber.getDescription());
                }
            } catch (NumberFormatException ignored) {
            }

            return ActResult.failure("未找到匹配的音色：「" + voice + "」。可用音色：1.龙小春 2.龙小夏 3.龙玉香 4.龙程 5.龙书 6.龙少 7.龙婉 8.龙梅 9.龙夜 10.龙月 11.龙翔 12.Bella");
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
