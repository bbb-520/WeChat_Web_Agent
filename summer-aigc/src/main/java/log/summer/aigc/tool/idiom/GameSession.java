package log.summer.aigc.tool.idiom;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 单个用户的成语接龙游戏会话。
 *
 * @author bbb
 * @since 2026-07-24
 */
public class GameSession {

    private final String userId;
    private String currentIdiom;       // 当前成语（等待用户接龙）
    private final Set<String> usedIdioms = new HashSet<>();
    private int score;
    private int rounds;
    private final LocalDateTime startTime;
    private volatile LocalDateTime lastActivity;

    public GameSession(String userId, String startIdiom) {
        this.userId = userId;
        this.currentIdiom = startIdiom;
        this.usedIdioms.add(startIdiom);
        this.startTime = LocalDateTime.now();
        this.lastActivity = LocalDateTime.now();
        this.score = 0;
        this.rounds = 0;
    }

    // ── Getters ──

    public String userId() { return userId; }
    public String currentIdiom() { return currentIdiom; }
    public Set<String> usedIdioms() { return usedIdioms; }
    public int score() { return score; }
    public int rounds() { return rounds; }
    public LocalDateTime startTime() { return startTime; }
    public LocalDateTime lastActivity() { return lastActivity; }

    // ── Mutators ──

    /** 记录用户成功接龙 */
    public void recordSuccess(String newIdiom) {
        this.currentIdiom = newIdiom;
        this.usedIdioms.add(newIdiom);
        this.score++;
        this.rounds++;
        this.lastActivity = LocalDateTime.now();
    }

    /** 记录 AI 接龙（不增加用户分数） */
    public void recordAiMove(String aiIdiom) {
        this.currentIdiom = aiIdiom;
        this.usedIdioms.add(aiIdiom);
        this.rounds++;
        this.lastActivity = LocalDateTime.now();
    }

    /** 手动加奖励分 */
    public void addBonus(int points) {
        this.score += points;
    }

    /** 刷新活动时间（避免超时清理） */
    public void touch() {
        this.lastActivity = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "GameSession{userId='" + userId + "', current='" + currentIdiom
                + "', score=" + score + ", rounds=" + rounds + "}";
    }
}
