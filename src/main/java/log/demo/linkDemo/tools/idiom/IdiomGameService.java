package log.demo.linkDemo.tools.idiom;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.entity.IdiomGameRecord;
import log.demo.linkDemo.service.IIdiomGameRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 成语接龙游戏服务 —— 会话管理、规则校验、AI 接龙、积分与持久化。
 *
 * <h3>游戏规则</h3>
 * <ol>
 *   <li>用户说的四字成语首字须与当前成语末字相同</li>
 *   <li>不能重复使用已出现过的成语</li>
 *   <li>必须是词典中存在的成语</li>
 *   <li>每成功接龙 +1 分</li>
 *   <li>AI 无法接龙时用户获 +2 奖励分并随机重新开始</li>
 *   <li>超过 {@link #INACTIVITY_TIMEOUT_MINUTES} 分钟无操作自动结束</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdiomGameService {

    private final IdiomDictionary dictionary;
    private final IIdiomGameRecordService recordService;

    /** 无操作超时（分钟） */
    private static final int INACTIVITY_TIMEOUT_MINUTES = 5;
    /** 清理间隔（分钟） */
    private static final int CLEANUP_INTERVAL_MINUTES = 2;

    /** 用户 → 游戏会话 */
    private final ConcurrentHashMap<String, GameSession> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    void init() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idiom-game-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupInactive,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("[IDIOM-GAME] 成语接龙服务初始化 | timeout={}min | cleanupInterval={}min",
                INACTIVITY_TIMEOUT_MINUTES, CLEANUP_INTERVAL_MINUTES);
    }

    @PreDestroy
    void destroy() {
        log.info("[IDIOM-GAME] 正在关闭，保存 {} 个活跃会话...", sessions.size());
        sessions.values().forEach(s -> saveRecord(s.userId(), s.score(), s.rounds(), "TIMEOUT"));
        sessions.clear();
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try { cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /** 用户是否在游戏中 */
    public boolean isUserInGame(String userId) {
        GameSession s = sessions.get(userId);
        if (s == null) return false;
        if (isExpired(s)) {
            endGame(userId, "TIMEOUT");
            return false;
        }
        return true;
    }

    /**
     * 开始一局新游戏。
     *
     * @param userId      用户 ID
     * @param startIdiom  起始成语（null 或空字符串则随机）
     * @return 游戏开始消息
     */
    public String startGame(String userId, String startIdiom) {
        // 检查是否已在游戏中
        GameSession existing = sessions.get(userId);
        if (existing != null && !isExpired(existing)) {
            return "你已经在游戏中啦！当前成语：「" + existing.currentIdiom()
                    + "」，输入 /cy stop 可结束游戏";
        }

        // 解析起始成语
        String idiom;
        if (startIdiom != null && !startIdiom.isBlank()) {
            Optional<String> validated = dictionary.validateIdiom(startIdiom.trim());
            if (validated.isEmpty()) {
                return "「" + startIdiom.trim() + "」不是有效成语，请输入一个四字成语或使用 /cy start 随机开始";
            }
            idiom = validated.get();
        } else {
            idiom = dictionary.randomIdiom();
        }

        GameSession session = new GameSession(userId, idiom);
        sessions.put(userId, session);

        log.info("[IDIOM-GAME] 游戏开始 | userId={} | startIdiom={}", userId, idiom);
        return "🎯 成语接龙开始！\n\n"
                + "当前成语：「" + idiom + "」\n"
                + "请说出一个以「" + idiom.charAt(idiom.length() - 1) + "」开头的成语\n"
                + "────────────────\n"
                + "输入 /cy stop 结束游戏\n"
                + "输入 /cy help 查看帮助";
    }

    /**
     * 处理用户的接龙输入（四字文本）。
     *
     * @param userId 用户 ID
     * @param input  用户输入的文本（应为四字）
     * @return 响应消息
     */
    public String handleInput(String userId, String input) {
        GameSession session = sessions.get(userId);
        if (session == null) return null; // 不在游戏中，应由正常流程处理

        if (input.startsWith("/")) return null; // 命令，走正常流程

        // 校验长度
        String trimmed = input.trim();
        if (trimmed.length() != 4) {
            session.touch();
            return "请输入一个四字成语来接龙（当前：「" + session.currentIdiom() + "」）";
        }

        // 校验是否为成语
        if (!dictionary.isValid(trimmed)) {
            session.touch();
            return "「" + trimmed + "」不是成语哦，请输入一个真实的四字成语";
        }

        // 校验是否已使用
        if (session.usedIdioms().contains(trimmed)) {
            session.touch();
            return "「" + trimmed + "」已经用过了，换一个吧！";
        }

        // 校验首字是否匹配
        String current = session.currentIdiom();
        char expectedFirst = current.charAt(current.length() - 1);
        if (trimmed.charAt(0) != expectedFirst) {
            session.touch();
            return "❌ 首字不匹配！当前成语「" + current + "」以「"
                    + expectedFirst + "」结尾，请说出一个以「" + expectedFirst + "」开头的成语";
        }

        // ── 用户接龙成功 ──
        session.recordSuccess(trimmed);

        // AI 尝试接龙
        char aiFirst = trimmed.charAt(trimmed.length() - 1);
        Optional<String> aiMove = dictionary.findChain(aiFirst, session.usedIdioms());

        if (aiMove.isPresent()) {
            String aiIdiom = aiMove.get();
            session.recordAiMove(aiIdiom);
            char nextChar = aiIdiom.charAt(aiIdiom.length() - 1);
            return "✅ 接龙成功！" + trimmed + " → " + aiIdiom + "\n"
                    + "轮到你了！请说出以「" + nextChar + "」开头的成语\n"
                    + "────────────────\n"
                    + "📊 当前积分：" + session.score() + " | 已接 " + session.rounds() + " 轮";
        } else {
            // AI 无法接龙：用户获胜，奖励分
            session.addBonus(2);
            int finalScore = session.score();
            int finalRounds = session.rounds();

            // 保存记录
            saveRecord(userId, finalScore, finalRounds, "USER_WIN");
            sessions.remove(userId);

            log.info("[IDIOM-GAME] 用户获胜 | userId={} | score={} | rounds={}", userId, finalScore, finalRounds);
            return "🎉 恭喜！我已无法接龙，你赢了！\n"
                    + "你的成语：「" + trimmed + "」\n"
                    + "────────────────\n"
                    + "📊 最终积分：" + finalScore + "（含 +2 奖励分）\n"
                    + "🔄 总轮数：" + finalRounds + "\n"
                    + "────────────────\n"
                    + "输入 /cy start 再来一局\n"
                    + "输入 /cy ls 查看历史积分";
        }
    }

    /**
     * 结束游戏。
     *
     * @param userId 用户 ID
     * @param reason 结束原因
     * @return 结束消息
     */
    public String endGame(String userId, String reason) {
        GameSession session = sessions.remove(userId);
        if (session == null) {
            return "当前没有进行中的游戏";
        }

        int finalScore = session.score();
        int finalRounds = session.rounds();
        saveRecord(userId, finalScore, finalRounds, reason);

        log.info("[IDIOM-GAME] 游戏结束 | userId={} | score={} | rounds={} | reason={}",
                userId, finalScore, finalRounds, reason);

        String reasonText = switch (reason) {
            case "TIMEOUT" -> "⏰ 超时未响应，游戏自动结束";
            case "USER_STOP" -> "🛑 游戏已结束";
            default -> "游戏已结束";
        };

        return reasonText + "\n"
                + "────────────────\n"
                + "📊 最终积分：" + finalScore + "\n"
                + "🔄 总轮数：" + finalRounds + "\n"
                + "使用的成语：" + session.usedIdioms().size() + " 个\n"
                + "────────────────\n"
                + "输入 /cy start 再来一局\n"
                + "输入 /cy ls 查看历史积分";
    }

    /** 查询历史积分（最近 3 局） */
    public String getHistory(String userId) {
        List<IdiomGameRecord> records = recordService.getRecentByUser(userId, 3);
        if (records.isEmpty()) {
            return "你还没有玩过成语接龙，输入 /cy start 开始吧！";
        }

        StringBuilder sb = new StringBuilder("📋 最近成语接龙积分\n");
        sb.append("────────────────\n");
        int rank = 1;
        for (IdiomGameRecord r : records) {
            String time = r.getCreatedAt() != null
                    ? r.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    : "未知";
            sb.append(String.format("%d. %s | 积分：%d | 轮数：%d | %s\n",
                    rank++, time, r.getScore(), r.getRounds(),
                    switch (r.getEndReason()) {
                        case "USER_WIN" -> "🏆 胜利";
                        case "USER_STOP" -> "🛑 主动结束";
                        case "TIMEOUT" -> "⏰ 超时";
                        default -> r.getEndReason();
                    }));
        }
        sb.append("────────────────\n");
        sb.append("输入 /cy start 开始新一局");
        return sb.toString();
    }

    /** 帮助信息 */
    public String getHelp() {
        return """
                🎯 成语接龙 —— 帮助
                ────────────────
                /cy start        随机开始一局
                /cy start <成语>  指定起始成语
                /cy stop         结束游戏并保存积分
                /cy ls           查看最近三局积分
                /cy help         查看帮助
                ────────────────
                📌 规则：
                ① 你说的成语首字须与上一个成语末字相同
                ② 已用成语不可重复
                ③ 必须是四字成语
                ④ 成功接龙 +1 分，AI 无法接龙 +2 奖励分
                ⑤ 超过 5 分钟无操作自动结束""";
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    private boolean isExpired(GameSession s) {
        return Duration.between(s.lastActivity(), LocalDateTime.now())
                .toMinutes() >= INACTIVITY_TIMEOUT_MINUTES;
    }

    private void cleanupInactive() {
        int removed = 0;
        Iterator<Map.Entry<String, GameSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GameSession> entry = it.next();
            if (isExpired(entry.getValue())) {
                saveRecord(entry.getKey(), entry.getValue().score(),
                        entry.getValue().rounds(), "TIMEOUT");
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[IDIOM-GAME] 清理 {} 个超时会话 | remaining={}", removed, sessions.size());
        }
    }

    private void saveRecord(String userId, int score, int rounds, String endReason) {
        try {
            IdiomGameRecord record = new IdiomGameRecord();
            record.setUserId(userId);
            record.setScore(score);
            record.setRounds(rounds);
            record.setEndReason(endReason);
            record.setCreatedAt(LocalDateTime.now());
            recordService.save(record);
        } catch (Exception e) {
            log.warn("[IDIOM-GAME] 保存积分失败 | userId={} | {}", userId, e.getMessage());
        }
    }
}
