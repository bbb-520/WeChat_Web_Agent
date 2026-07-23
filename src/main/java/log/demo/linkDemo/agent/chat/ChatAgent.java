package log.demo.linkDemo.agent.chat;

import log.demo.linkDemo.agent.Agent;
import log.demo.linkDemo.agent.AgentContext;
import log.demo.linkDemo.agent.Intent;
import log.demo.linkDemo.agent.image.ImageCacheManager;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.enums.RouteContext;
import log.demo.linkDemo.exception.AIServiceException;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import log.demo.linkDemo.rag.RAGRetrievalService;
import log.demo.linkDemo.agent.voice.TTSEngine;
import log.demo.linkDemo.agent.voice.TimbreSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用对话智能体 —— 通义千问（qwen-plus）文本对话 + RAG 增强。
 * 兜底 Agent，意图 = {@link Intent#CHAT}。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAgent implements Agent {

    private final ChatService chatService;
    private final TTSEngine ttsEngine;
    private final TimbreSession timbreSession;
    private final ImageCacheManager imageCacheManager;
    private final RAGRetrievalService ragRetrievalService;

    @Override public String name() { return "chat"; }
    @Override public Intent intent() { return Intent.CHAT; }

    @Override
    public boolean execute(AgentContext ctx) {
        imageCacheManager.removeSilently(ctx.userId());
        log.info("[CHAT] userId={} | context={} | textLen={}",
                ctx.userId(), ctx.routeContext(), ctx.text() != null ? ctx.text().length() : 0);

        String response;
        try {
            var docs = ragRetrievalService.retrieve(ctx.userId(), ctx.text());
            if (ragRetrievalService.hasRelevant(docs)) {
                response = chatService.chatWithRAG(ctx.userId(), ctx.text(), docs);
            } else {
                response = chatService.chat(ctx.userId(), ctx.text());
            }
        } catch (AIServiceException e) {
            log.error("[CHAT] AI 失败 | userId={} | operation={}", ctx.userId(), e.getOperation(), e);
            ctx.sender().sendText(ctx.userId(), "AI 回复失败：" + e.getMessage());
            return true;
        }
        ctx.sender().sendText(ctx.userId(), response);

        if (ctx.routeContext() == RouteContext.VOICE) {
            ttsAndSend(ctx.userId(), response, ctx.sender());
        }
        return true;
    }

    private void ttsAndSend(String userId, String text, log.demo.linkDemo.service.MessageSender sender) {
        try {
            VoiceResult result = ttsEngine.synthesize(text, timbreSession.getCurrentVoiceId(userId));
            if (result.hasAudio()) {
                sender.sendFile(userId, result.wavAudio(), "tts-" + System.currentTimeMillis() + ".wav", text);
            }
        } catch (VoiceSynthesisException e) {
            sender.sendText(userId, "语音生成失败：" + e.getMessage());
        } catch (Exception e) {
            log.warn("[CHAT] TTS 发送失败 | userId={}", userId, e);
        }
    }
}
