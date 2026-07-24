package log.demo.linkDemo.tools.command;

import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.entity.VoiceResult;
import log.demo.linkDemo.enums.Timbre;
import log.demo.linkDemo.exception.VoiceSynthesisException;
import log.demo.linkDemo.service.ChatPersistenceService;
import log.demo.linkDemo.service.MessageSender;
import log.demo.linkDemo.tools.chat.ChatService;
import log.demo.linkDemo.tools.image.ImageCacheManager;
import log.demo.linkDemo.tools.image.ImageContextManager;
import log.demo.linkDemo.tools.image.ImageGenService;
import log.demo.linkDemo.config.BotProperties;
import log.demo.linkDemo.tools.memoryMonitor.MemoryMonitorTools;
import log.demo.linkDemo.tools.navigation.NavigationTools;
import log.demo.linkDemo.tools.reminder.ReminderTools;
import log.demo.linkDemo.tools.history.HistoryService;
import log.demo.linkDemo.tools.idiom.IdiomGameService;
import log.demo.linkDemo.tools.voice.VoiceGenAgent;
import log.demo.linkDemo.tools.weather.WeatherAgent;
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
    private final IdiomGameService idiomGameService;
    private final HistoryService historyService;
    private final BotProperties botProperties;

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
        registry.put("/cy ",        this::handleIdiomGame);
        registry.put("/memory",     this::handleMemory);
        registry.put("/nav ",       this::handleNav);
        registry.put("/traffic ",   this::handleTraffic);
        registry.put("/remind ",    this::handleRemind);
        registry.put("/remind",     (u, c, s, r) -> s.sendText(u, ReminderTools.helpText()));
        registry.put("/history ",   this::handleHistory);
        registry.put("/history",    this::handleHistory);
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
                /help    —— 帮助
                /status  —— 状态
                /clear   —— 清除记忆
                /draw <描述>     —— 文生图
                /tts <文字>      —— 文字转语音
                /weather <城市>   —— 查询天气
                /voice list      —— 查看可用音色
                /voice <编号>     —— 切换音色
                /cancel          —— 取消图片编辑模式
                /cy start        —— 开始成语接龙
                /cy stop         —— 结束接龙
                /cy ls           —— 接龙积分
                /memory          —— 内存监控报告
                /nav 从<A>到<B>   —— 路线规划
                /traffic <道路>   —— 实时路况
                /remind <时间> <内容> —— 设置提醒
                /history          —— 查看最近会话
                /history [编号]    —— 查看会话详情

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

    private void handleIdiomGame(String userId, String cmd, MessageSender sender, boolean running) {
        String sub = cmd.substring(3).trim();
        String response;
        if (sub.isEmpty() || sub.equals("help")) {
            response = idiomGameService.getHelp();
        } else if (sub.startsWith("start")) {
            String arg = sub.substring(5).trim();
            response = idiomGameService.startGame(userId, arg.isEmpty() ? null : arg);
        } else if (sub.equals("stop")) {
            response = idiomGameService.endGame(userId, "USER_STOP");
        } else if (sub.equals("ls")) {
            response = idiomGameService.getHistory(userId);
        } else if (sub.equals("help")) {
            response = idiomGameService.getHelp();
        } else {
            response = "未知的 /cy 子命令，输入 /cy help 查看帮助";
        }
        sender.sendText(userId, response);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内存监控 /memory
    // ═══════════════════════════════════════════════════════════════

    private void handleMemory(String userId, String cmd, MessageSender sender, boolean running) {
        String arg = cmd.length() > 7 ? cmd.substring(7).trim() : "";
        String response;
        if (arg.isEmpty()) {
            var snapshot = MemoryMonitorTools.collectSnapshot();
            MemoryMonitorTools.recordSnapshot(snapshot);
            response = MemoryMonitorTools.generateReport(snapshot);
        } else if (arg.startsWith("diff")) {
            var prev = MemoryMonitorTools.getLatest();
            var curr = MemoryMonitorTools.collectSnapshot();
            MemoryMonitorTools.recordSnapshot(curr);
            if (prev != null) {
                response = MemoryMonitorTools.generateReport(prev, curr);
            } else {
                response = "没有历史快照可供对比，已采集当前快照。\n\n"
                        + MemoryMonitorTools.generateReport(curr);
            }
        } else if (arg.equals("simple")) {
            var snapshot = MemoryMonitorTools.collectSnapshot();
            MemoryMonitorTools.recordSnapshot(snapshot);
            response = MemoryMonitorTools.generateSimpleReport(snapshot);
        } else if (arg.equals("history")) {
            response = MemoryMonitorTools.generateHistoryReport();
        } else {
            response = MemoryMonitorTools.helpText();
        }
        sender.sendText(userId, response);
    }

    // ═══════════════════════════════════════════════════════════════
    // 导航 /nav 和 /traffic
    // ═══════════════════════════════════════════════════════════════

    private void handleNav(String userId, String cmd, MessageSender sender, boolean running) {
        String text = cmd.substring(4).trim(); // 去除 "/nav" 前缀
        if (text.isEmpty() || text.equals("help")) {
            sender.sendText(userId, NavigationTools.helpText());
            return;
        }

        // 解析：从<起点>到<终点> [出行方式]
        String origin = null, destination = null;
        NavigationTools.TravelMode mode = NavigationTools.TravelMode.DRIVING;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("从(.+?)到(.+)").matcher(text);
        if (m.find()) {
            origin = m.group(1).trim();
            destination = m.group(2).trim();
            // 检查是否有出行方式后缀
            String suffix = destination;
            if (suffix.contains(" 步行")) {
                mode = NavigationTools.TravelMode.WALKING;
                destination = suffix.replace(" 步行", "").trim();
            } else if (suffix.contains(" 骑行")) {
                mode = NavigationTools.TravelMode.BICYCLING;
                destination = suffix.replace(" 骑行", "").trim();
            } else if (suffix.contains(" 公交")) {
                mode = NavigationTools.TravelMode.TRANSIT;
                destination = suffix.replace(" 公交", "").trim();
            } else if (suffix.contains(" 驾车")) {
                destination = suffix.replace(" 驾车", "").trim();
            }
        }

        if (origin == null || destination == null) {
            sender.sendText(userId, "格式：/nav 从<起点>到<终点> [出行方式]\n"
                    + "示例：/nav 从北京西站到天安门 步行\n"
                    + "出行方式：驾车（默认）、步行、骑行、公交");
            return;
        }

        String apiKey = botProperties.getWeather().getApiKey();
        sender.sendText(userId, "正在规划路线：从「" + origin + "」到「" + destination
                + "」（" + mode.label() + "）...");

        final String fOrigin = origin;
        final String fDest = destination;
        final NavigationTools.TravelMode fMode = mode;
        final String fApiKey = apiKey;
        Thread.startVirtualThread(() -> {
            try {
                var result = NavigationTools.planRoute(fApiKey, fOrigin, fDest,
                        fMode, null, true, false);
                String report = NavigationTools.buildTextReport(result, fMode, fOrigin, fDest);
                sender.sendText(userId, report);
            } catch (Exception e) {
                sender.sendText(userId, "路线规划失败：" + e.getMessage());
            }
        });
    }

    private void handleTraffic(String userId, String cmd, MessageSender sender, boolean running) {
        String roadName = cmd.substring(8).trim(); // 去除 "/traffic" 前缀
        if (roadName.isEmpty()) {
            sender.sendText(userId, "请输入道路名，例如：/traffic 中关村南大街");
            return;
        }

        final String fApiKey = botProperties.getWeather().getApiKey();
        final String fRoadName = roadName;
        sender.sendText(userId, "正在查询「" + fRoadName + "」路况...");

        Thread.startVirtualThread(() -> {
            try {
                var conditions = NavigationTools.queryTraffic(fApiKey, fRoadName);
                if (conditions.isEmpty()) {
                    sender.sendText(userId, "未查询到「" + fRoadName + "」的实时路况信息");
                } else {
                    StringBuilder sb = new StringBuilder("🚦 " + fRoadName + " 实时路况\n");
                    sb.append("────────────────\n");
                    for (var tc : conditions) {
                        sb.append(tc).append("\n");
                    }
                    sb.append("────────────────");
                    sender.sendText(userId, sb.toString());
                }
            } catch (Exception e) {
                sender.sendText(userId, "路况查询失败：" + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // 提醒 /remind
    // ═══════════════════════════════════════════════════════════════

    private void handleRemind(String userId, String cmd, MessageSender sender, boolean running) {
        String arg = cmd.substring(7).trim(); // 去除 "/remind" 前缀
        String response;

        if (arg.isEmpty() || arg.equals("help")) {
            response = ReminderTools.helpText();
        } else if (arg.equals("list")) {
            response = ReminderTools.formatReminderList(userId);
        } else if (arg.startsWith("cancel")) {
            String sub = arg.substring(6).trim();
            if (sub.equals("all")) {
                int count = ReminderTools.cancelAll(userId);
                response = "已取消 " + count + " 个提醒";
            } else {
                try {
                    int taskId = Integer.parseInt(sub);
                    boolean ok = ReminderTools.cancelReminder(userId, taskId);
                    response = ok ? "已取消提醒 ID:" + taskId
                            : "未找到提醒 ID:" + taskId;
                } catch (NumberFormatException e) {
                    response = "请指定提醒 ID（数字），或使用 /remind cancel all 取消全部";
                }
            }
        } else {
            // 创建提醒
            ReminderTools.setSender(sender); // 注入 sender 以便触发时发送
            var task = ReminderTools.createReminderFromText(userId, arg);
            if (task != null) {
                response = "✅ 提醒已设置！\n"
                        + "────────────────\n"
                        + "内容：" + task.message() + "\n"
                        + "触发时间：" + task.triggerTimeFormatted() + "\n"
                        + "剩余时间：" + ReminderTools.formatTimeRemaining(task.triggerTime()) + "\n"
                        + "提醒ID：" + task.id() + "\n"
                        + "────────────────\n"
                        + "输入 /remind list 查看所有提醒";
            } else {
                response = ReminderTools.helpText();
            }
        }
        sender.sendText(userId, response);
    }

    // ═══════════════════════════════════════════════════════════════
    // 历史记录 /history
    // ═══════════════════════════════════════════════════════════════

    private void handleHistory(String userId, String cmd, MessageSender sender, boolean running) {
        String arg = cmd.length() > 8 ? cmd.substring(8).trim() : "";
        String response;
        if (arg.isEmpty()) {
            response = historyService.recentHistory(userId);
        } else if (arg.equals("all")) {
            response = historyService.allHistory(userId);
        } else {
            try {
                long convId = Long.parseLong(arg);
                response = historyService.conversationDetail(userId, convId);
            } catch (NumberFormatException e) {
                response = "请输入有效的会话编号，如 /history 123\n"
                        + "/history      — 最近 3 个会话\n"
                        + "/history all  — 全部会话\n"
                        + "/history [编号] — 查看会话详情";
            }
        }
        sender.sendText(userId, response);
    }
}
