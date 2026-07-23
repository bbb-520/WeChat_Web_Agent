package log.demo.linkDemo.agent.command;

import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.enums.Timbre;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import log.demo.linkDemo.service.ChatPersistenceService;
import log.demo.linkDemo.service.MessageSender;
import log.demo.linkDemo.agent.chat.ChatService;
import log.demo.linkDemo.agent.image.ImageCacheManager;
import log.demo.linkDemo.agent.image.ImageContextManager;
import log.demo.linkDemo.agent.image.ImageGenService;
import log.demo.linkDemo.agent.voice.VoiceGenAgent;
import log.demo.linkDemo.agent.weather.WeatherAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令注册表 —— 前缀匹配 + 委托执行。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandRegistry {

    private final ChatService chatService;
    private final ImageCacheManager imageCacheManager;
    private final ImageContextManager imageContextManager;
    private final ImageGenService imageGenService;
    private final VoiceGenAgent voiceGenAgent;
    private final WeatherAgent weatherAgent;
    private final ChatPersistenceService persistence;

    private Map<String, Command> registry;

    @PostConstruct
    void buildRegistry() {
        registry = new LinkedHashMap<>();
        registry.put("/draw ",      this::handleDraw);
        registry.put("/weather ",   this::handleWeather);
        registry.put("/weather",    (u, c, s, r) -> s.sendText(u, "请指定城市，例如：/weather 北京"));
        registry.put("/tts ",       this::handleTts);
        registry.put("/voice ",     this::handleVoice);
        registry.put("/help",       this::handleHelp);
        registry.put("/status",     this::handleStatus);
        registry.put("/clear",      this::handleClear);
        registry.put("/cancel",     this::handleCancel);
    }

    public void execute(String userId, String cmd, MessageSender sender, boolean isRunning) {
        for (var entry : registry.entrySet()) {
            if (cmd.startsWith(entry.getKey())) {
                entry.getValue().execute(userId, cmd, sender, isRunning);
                return;
            }
        }
        sender.sendText(userId, "未知命令，输入 /help 查看帮助");
    }

    private void handleDraw(String userId, String cmd, MessageSender sender, boolean running) {
        String prompt = cmd.substring(6).trim();
        sender.sendText(userId, "正在绘制...");
        Thread.startVirtualThread(() -> {
            try {
                String url = imageGenService.generateImageUrl(prompt);
                if (url == null) { sender.sendText(userId, "图片生成失败，请稍后重试"); return; }
                byte[] bytes = imageGenService.downloadImage(url);
                if (bytes != null && bytes.length > 0) {
                    sender.sendImage(userId, bytes, "draw.png", prompt);
                } else { sender.sendText(userId, "图片下载失败"); }
            } catch (Exception e) { sender.sendText(userId, "图片生成失败：" + e.getMessage()); }
        });
    }

    private void handleWeather(String userId, String cmd, MessageSender sender, boolean running) {
        String city = cmd.substring(9).trim();
        sender.sendText(userId, "正在查询「" + city + "」天气...");
        try { sender.sendText(userId, weatherAgent.generateReport(userId, city + "天气")); }
        catch (Exception e) { sender.sendText(userId, "天气查询失败：" + e.getMessage()); }
    }

    private void handleTts(String userId, String cmd, MessageSender sender, boolean running) {
        String text = cmd.substring(5).trim();
        if (text.isEmpty()) { sender.sendText(userId, "请输入要合成的文本，例如：/tts 你好世界"); return; }
        sender.sendText(userId, "正在生成语音：\"" + (text.length() > 30 ? text.substring(0, 30) + "..." : text) + "\"");
        try {
            VoiceResult result = voiceGenAgent.synthesizeWithDefaultVoice(text);
            if (!result.hasAudio()) { sender.sendText(userId, "语音生成失败：返回空数据"); return; }
            sender.sendFile(userId, result.wavAudio(), "tts-" + System.currentTimeMillis() + ".wav", text);
        } catch (VoiceSynthesisException e) { sender.sendText(userId, "语音生成失败：" + e.getMessage()); }
        catch (Exception e) { sender.sendText(userId, "语音发送失败：" + e.getMessage()); }
    }

    private void handleVoice(String userId, String cmd, MessageSender sender, boolean running) {
        String arg = cmd.substring(7).trim();
        if ("list".equalsIgnoreCase(arg)) { sender.sendText(userId, Timbre.formatList()); }
        else {
            try {
                int num = Integer.parseInt(arg);
                Timbre t = voiceGenAgent.switchTimbre(userId, num);
                sender.sendText(userId, "✅ 已切换到音色：" + t.getDisplayName() + " —— " + t.getDescription());
            } catch (NumberFormatException e) { sender.sendText(userId, "请输入有效编号，如 /voice 3。输入 /voice list 查看列表"); }
            catch (VoiceSynthesisException e) { sender.sendText(userId, e.getMessage()); }
        }
    }

    private void handleHelp(String userId, String cmd, MessageSender sender, boolean running) {
        sender.sendText(userId, """
                命令列表：
                /help   —— 帮助
                /status —— 状态
                /clear  —— 清除记忆
                /draw <描述> —— 文生图
                /tts <文字>  —— 文字转语音
                /weather <城市> —— 查询天气
                /voice list  —— 查看可用音色
                /voice <编号> —— 切换音色
                /cancel —— 取消图片编辑模式

                💬 也可直接说 "用<关键词>音说…" 切换音色并对话
                🌤 直接说 "北京天气" 即可查询天气
                🎤 发送语音消息自动以当前音色回复""");
    }

    private void handleStatus(String userId, String cmd, MessageSender sender, boolean running) {
        String timbreStatus = voiceGenAgent.getTimbreStatus(userId);
        sender.sendText(userId, "运行正常，状态：" + (running ? "已连接" : "未连接")
                + "\n🎤 当前音色：" + timbreStatus);
    }

    private void handleClear(String userId, String cmd, MessageSender sender, boolean running) {
        chatService.clearHistory(userId);
        imageCacheManager.removeSilently(userId);
        imageContextManager.clear(userId);
        try { var conv = persistence.getActiveConversation(userId); if (conv != null) persistence.closeConversation(conv.getId()); }
        catch (Exception ignored) {}
        sender.sendText(userId, "记忆已清除");
    }

    private void handleCancel(String userId, String cmd, MessageSender sender, boolean running) {
        imageCacheManager.removeSilently(userId);
        sender.sendText(userId, "已取消图片编辑模式");
    }
}
