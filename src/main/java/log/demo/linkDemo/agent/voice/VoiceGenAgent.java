package log.demo.linkDemo.agent.voice;

import log.demo.linkDemo.agent.Agent;
import log.demo.linkDemo.agent.AgentContext;
import log.demo.linkDemo.agent.Intent;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.enums.Timbre;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语音生成智能体 —— CosyVoice TTS + 12 种音色切换。
 * 模型: cosyvoice-v1，意图 = {@link Intent#TTS} / {@link Intent#VOICE_SWITCH}。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceGenAgent implements Agent {

    private final TTSEngine ttsEngine;
    private final TimbreSession timbreSession;

    @Override public String name() { return "voice-gen"; }
    @Override public Intent intent() { return Intent.TTS; }

    @Override
    public boolean execute(AgentContext ctx) {
        String text = ctx.text();
        if (text == null || text.isBlank()) return false;

        // 音色切换意图
        if (Timbre.isTimbreSwitchIntent(text)) {
            Timbre t = Timbre.matchKeyword(text).orElse(null);
            if (t != null) {
                timbreSession.switchTo(ctx.userId(), t);
                ctx.sender().sendText(ctx.userId(), "✅ 已切换到音色：" + t.getDisplayName());
            }
            // 如果还有剩余文本（如 "用男声说你好"），继续处理
            String remaining = stripTimbrePrefix(text);
            if (remaining != null && !remaining.isBlank()) {
                ctx.setText(remaining);
                return ttsAndSend(ctx);
            }
            return true;
        }

        // TTS 意图
        return ttsAndSend(ctx);
    }

    private boolean ttsAndSend(AgentContext ctx) {
        try {
            String ttsText = extractTtsText(ctx.text());
            VoiceResult result = ttsEngine.synthesize(ttsText, timbreSession.getCurrentVoiceId(ctx.userId()));
            if (result.hasAudio()) {
                ctx.sender().sendFile(ctx.userId(), result.wavAudio(),
                        "tts-" + System.currentTimeMillis() + ".wav", ttsText);
                return true;
            }
            ctx.sender().sendText(ctx.userId(), "语音生成失败：返回空数据");
        } catch (VoiceSynthesisException e) {
            ctx.sender().sendText(ctx.userId(), "语音生成失败：" + e.getMessage());
        }
        return true;
    }

    /** 使用默认音色合成（命令场景） */
    public VoiceResult synthesizeWithDefaultVoice(String text) {
        return ttsEngine.synthesize(text, null);
    }

    /** 切换音色 */
    public Timbre switchTimbre(String userId, int number) {
        Timbre t = Timbre.byNumber(number)
                .orElseThrow(() -> new VoiceSynthesisException("无效的音色编号：" + number));
        timbreSession.switchTo(userId, t);
        return t;
    }

    public String getTimbreStatus(String userId) {
        return timbreSession.getStatusText(userId);
    }

    private String extractTtsText(String text) {
        if (text == null) return "";
        return text.replaceAll("^(用语音|语音生成|语音合成|朗读|播报|说|生成语音|合成语音)\\s*", "").trim();
    }

    private String stripTimbrePrefix(String text) {
        if (text == null) return "";
        return text.replaceAll("^用.{1,6}(?:音|声)(?:说|生成|合成|朗读|播报)?\\s*", "").trim();
    }
}
