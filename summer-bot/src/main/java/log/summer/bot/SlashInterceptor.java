package log.summer.bot;

import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.tool.BotMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

/**
 * Pre-intercepts slash commands before they reach AgentLoop.
 * Returns true if the message was handled and should NOT be forwarded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlashInterceptor {

    private final BotMetrics botMetrics;
    private final ChatMemory chatMemory;

    /**
     * @return true if intercepted (message consumed), false to forward to AgentLoop
     */
    public boolean intercept(BotMessage msg, MessageSender sender) {
        if (!msg.hasText() || !msg.text().startsWith("/")) {
            return false;
        }

        String text = msg.text().trim();
        String userId = msg.userId();

        switch (text) {
            case "/help" -> {
                sender.sendText(userId, """
                        【可用命令】
                        /help    — 查看帮助
                        /status  — 查看机器人运行状态
                        /cancel  — 取消当前会话

                        【功能】
                        • 天气查询：今天北京的天气怎么样？
                        • 图片生成：帮我画一只猫
                        • 图片识别：发送图片让我识别
                        • 语音合成：用语音朗读一段文字
                        • 文件分析：发送 PDF/Word 让我分析
                        • 成语接龙：跟我玩成语接龙
                        • 设置提醒：明天下午3点提醒我开会
                        """);
            }
            case "/status" -> {
                var stats = botMetrics.getStats();
                sender.sendText(userId, String.format("""
                        【运行状态】
                        运行时长：%s
                        已处理消息：%d
                        错误数：%d
                        """, stats.uptime(), stats.messageCount(), stats.errorCount()));
            }
            case "/cancel" -> {
                chatMemory.clear(userId);
                sender.sendText(userId, "当前会话已取消。开始新的对话吧！");
            }
            default -> {
                if (text.startsWith("/")) {
                    sender.sendText(userId, "未知命令，输入 /help 查看可用命令");
                }
            }
        }

        return true;
    }
}
