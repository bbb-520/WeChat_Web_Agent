package log.demo.linkDemo.tools.history;

import log.demo.linkDemo.entity.Conversation;
import log.demo.linkDemo.entity.Message;
import log.demo.linkDemo.service.IConversationService;
import log.demo.linkDemo.service.IMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 历史记录查询服务 —— 基于 DB 中的 conversation + message 表。
 *
 * <h3>命令</h3>
 * <ul>
 *   <li>{@code /history} — 最近 3 个会话摘要</li>
 *   <li>{@code /history [id]} — 指定会话的完整对话</li>
 *   <li>{@code /history all} — 全部会话摘要（自动截断 2048 字）</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final IConversationService conversationService;
    private final IMessageService messageService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 微信消息上限约 2048 字，预留安全边距 */
    private static final int MAX_CHARS = 1900;

    // ═══════════════════════════════════════════════════════════════
    // /history — 最近 3 个会话摘要
    // ═══════════════════════════════════════════════════════════════

    public String recentHistory(String userId) {
        return buildHistorySummary(userId, 3);
    }

    // ═══════════════════════════════════════════════════════════════
    // /history [id] — 指定会话完整对话
    // ═══════════════════════════════════════════════════════════════

    public String conversationDetail(String userId, long convId) {
        Conversation conv = conversationService.getByConvId(convId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return "未找到会话 #" + convId + "，或该会话不属于你";
        }

        List<Message> messages = messageService.getMessages(convId);
        if (messages.isEmpty()) {
            return "会话 #" + convId + " 暂无消息记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 会话 #").append(convId).append(" 完整记录\n");
        sb.append("────────────────\n");
        sb.append("时间：").append(formatTime(conv.getCreatedAt())).append("\n");
        sb.append("消息数：").append(messages.size()).append("\n");
        sb.append("────────────────\n");

        int charCount = sb.length();
        for (Message msg : messages) {
            String role = "USER".equals(msg.getMessageType()) ? "👤" : "🤖";
            String content = msg.getTextContent() != null ? msg.getTextContent() : "[非文本消息]";
            // 单条消息截断
            if (content.length() > 300) content = content.substring(0, 300) + "...";

            String line = role + " " + content + "\n";
            if (charCount + line.length() > MAX_CHARS) {
                sb.append("...（内容过长，已截断）\n");
                break;
            }
            sb.append(line);
            charCount += line.length();
        }
        sb.append("────────────────\n");
        sb.append("输入 /history 返回会话列表");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // /history all — 全部会话摘要
    // ═══════════════════════════════════════════════════════════════

    public String allHistory(String userId) {
        List<Conversation> convs = conversationService.getAllByUser(userId);
        if (convs.isEmpty()) return "暂无历史会话记录";

        StringBuilder sb = new StringBuilder("📋 全部历史会话（共 " + convs.size() + " 个）\n");
        sb.append("────────────────\n");

        int count = 0;
        int charCount = sb.length();
        for (Conversation c : convs) {
            count++;
            String status = c.getStatus() != null && c.getStatus() == 1 ? "🟢进行中" : "⚪已结束";
            String title = c.getTitle() != null ? c.getTitle() : "（无标题）";
            if (title.length() > 30) title = title.substring(0, 30) + "...";
            String line = String.format("[#%d] %s | %s | %d条消息 | %s\n",
                    c.getId(), formatTime(c.getCreatedAt()), status,
                    c.getMessageCount() != null ? c.getMessageCount() : 0, title);

            if (charCount + line.length() > MAX_CHARS) {
                sb.append("...（共 ").append(convs.size()).append(" 个会话，已截断）\n");
                break;
            }
            sb.append(line);
            charCount += line.length();
        }
        sb.append("────────────────\n");
        sb.append("输入 /history [编号] 查看详细对话");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════════

    private String buildHistorySummary(String userId, int limit) {
        List<Conversation> convs = conversationService.getRecentByUser(userId, limit);
        if (convs.isEmpty()) return "暂无历史会话记录，发送一条消息开始对话吧！";

        StringBuilder sb = new StringBuilder("📋 最近 " + convs.size() + " 个会话\n");
        sb.append("────────────────\n");
        int idx = 1;
        for (Conversation c : convs) {
            String status = c.getStatus() != null && c.getStatus() == 1 ? "🟢" : "⚪";
            String title = c.getTitle() != null ? c.getTitle() : "（无标题）";
            if (title.length() > 25) title = title.substring(0, 25) + "...";
            sb.append(String.format(" [#%d] %s %s | %d条 | %s\n",
                    c.getId(), status, formatTime(c.getCreatedAt()),
                    c.getMessageCount() != null ? c.getMessageCount() : 0, title));
            idx++;
        }
        sb.append("────────────────\n");
        sb.append("查看详情：/history [编号]\n");
        sb.append("查看全部：/history all");
        return sb.toString();
    }

    private static String formatTime(LocalDateTime time) {
        return time != null ? time.format(FMT) : "未知";
    }
}
