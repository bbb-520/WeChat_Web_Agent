package log.summer.aigc.tool.idiom;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.loop.ActResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 成语接龙游戏工具 —— 会话管理、规则校验、AI 接龙。
 *
 * <h3>游戏规则</h3>
 * <ol>
 *   <li>用户说的四字成语首字须与当前成语末字相同</li>
 *   <li>不能重复使用已出现过的成语</li>
 *   <li>必须是词典中存在的成语</li>
 *   <li>每成功接龙 +1 分</li>
 *   <li>AI 无法接龙时用户获 +2 奖励分并随机重新开始</li>
 *   <li>超过 5 分钟无操作自动结束</li>
 * </ol>
 *
 * @author bbb
 * @since 2026-07-24
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdiomGameTool {

    private final IdiomDictionary dictionary;

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
        log.info("[IDIOM-GAME] 正在关闭，清理 {} 个活跃会话...", sessions.size());
        sessions.clear();
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try { cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // @Tool 方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 成语接龙游戏。LLM 向此工具传入用户的输入文本，工具内部管理游戏状态。
     *
     * <p>支持的命令：</p>
     * <ul>
     *   <li>/cy start [成语] — 开始新游戏（可指定起始成语）</li>
     *   <li>/cy stop — 结束游戏</li>
     *   <li>/cy ls — 查看积分（本会话内）</li>
     *   <li>/cy help — 帮助</li>
     *   <li>四字成语 — 接龙</li>
     * </ul>
     *
     * @param input  用户输入文本
     * @param userId 用户 ID（由 LLM 传入）
     * @return 游戏结果
     */
    @Tool(name = "idiom_game", description = "成语接龙游戏。传入用户输入的文本，工具自动管理游戏状态。支持 /cy start 开始、/cy stop 结束、/cy ls 查看积分、/cy help 帮助，或直接输入四字成语接龙。注意：用户ID由系统自动注入，调用时无需填写。")
    public ActResult idiomGame(
            @ToolParam(required = true, description = "用户输入的文本，可以是命令（/cy start、/cy stop、/cy ls、/cy help）或四字成语") String input) {

        // BUG FIX: source userId from per-request context instead of LLM-provided parameter.
        String userId = UserContextHolder.getUserId();

        try {
            if (input == null || input.isBlank()) {
                return ActResult.failure("请输入有效的文本");
            }

            String trimmed = input.trim();

            // 命令处理（不依赖游戏状态）
            if (trimmed.startsWith("/cy help") || trimmed.equals("/cy")) {
                return ActResult.success(getHelp());
            }

            if (trimmed.startsWith("/cy start")) {
                String startIdiom = trimmed.substring("/cy start".length()).trim();
                return ActResult.success(startGame(userId, startIdiom));
            }

            if (trimmed.startsWith("/cy stop")) {
                return ActResult.success(endGame(userId, "USER_STOP"));
            }

            if (trimmed.startsWith("/cy ls")) {
                return ActResult.success(getScoreboard(userId));
            }

            if (trimmed.startsWith("/cy")) {
                return ActResult.failure("未知命令：" + trimmed + "，输入 /cy help 查看帮助");
            }

            // 不是命令，尝试作为成语接龙处理
            GameSession session = sessions.get(userId);
            if (session == null || isExpired(session)) {
                // 用户不在游戏中，输入的可能是成语，自动开始
                if (trimmed.length() == 4 && dictionary.isValid(trimmed)) {
                    return ActResult.success(startGame(userId, trimmed));
                }
                return ActResult.failure("你当前不在游戏中，输入 /cy start 开始一局吧！");
            }

            return ActResult.success(handleInput(userId, trimmed));
        } catch (Exception e) {
            log.error("[IDIOM-GAME] 处理失败 | userId={} | input={}", userId, input, e);
            return ActResult.failure("游戏处理异常：" + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 游戏逻辑
    // ═══════════════════════════════════════════════════════════════

    private String startGame(String userId, String startIdiom) {
        GameSession existing = sessions.get(userId);
        if (existing != null && !isExpired(existing)) {
            return "你已经在游戏中啦！当前成语：「" + existing.currentIdiom()
                    + "」，输入 /cy stop 可结束游戏";
        }

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

    private String handleInput(String userId, String input) {
        GameSession session = sessions.get(userId);
        if (session == null) return null;

        String trimmed = input.trim();

        // 校验长度
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

        // 用户接龙成功
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
            // AI 无法接龙：用户获胜
            session.addBonus(2);
            int finalScore = session.score();
            int finalRounds = session.rounds();

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

    private String endGame(String userId, String reason) {
        GameSession session = sessions.remove(userId);
        if (session == null) {
            return "当前没有进行中的游戏";
        }

        int finalScore = session.score();
        int finalRounds = session.rounds();

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
                + "输入 /cy start 再来一局";
    }

    /** 简单积分信息（内存态，重启丢失） */
    private String getScoreboard(String userId) {
        GameSession active = sessions.get(userId);
        if (active != null && !isExpired(active)) {
            return "📋 当前游戏\n"
                    + "────────────────\n"
                    + "当前成语：「" + active.currentIdiom() + "」\n"
                    + "积分：" + active.score() + " | 轮数：" + active.rounds() + "\n"
                    + "已用成语：" + active.usedIdioms().size() + " 个\n"
                    + "────────────────\n"
                    + "输入 /cy start 开始新一局";
        }
        return "你还没有进行中的游戏，输入 /cy start 开始吧！";
    }

    private String getHelp() {
        return """
                🎯 成语接龙 —— 帮助
                ────────────────
                /cy start        随机开始一局
                /cy start <成语>  指定起始成语
                /cy stop         结束游戏并保存积分
                /cy ls           查看当前积分
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
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[IDIOM-GAME] 清理 {} 个超时会话 | remaining={}", removed, sessions.size());
        }
    }
}
