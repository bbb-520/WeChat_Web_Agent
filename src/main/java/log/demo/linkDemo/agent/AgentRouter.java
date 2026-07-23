package log.demo.linkDemo.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 路由器 —— 基于意图分类结果，O(1) 直接路由到对应 Agent。
 *
 * <p>与旧 {@code ToolDispatcher}（O(n) 链式遍历）相比：
 * <ul>
 *   <li>意图由 {@link IntentClassifier}（qwen-turbo）提前分类</li>
 *   <li>路由是 Map 查找 O(1)，不再遍历优先级链</li>
 *   <li>CHAT 作为兜底 Agent（与其他 Agent 一起注册，非特殊逻辑）</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class AgentRouter {

    private final IntentClassifier classifier;
    private final Map<Intent, Agent> agentMap = new EnumMap<>(Intent.class);
    private final Agent fallbackAgent;

    /**
     * Spring 注入所有 Agent Bean，构建 Intent → Agent 映射。
     * CHAT Agent 同时作为兜底。
     */
    public AgentRouter(IntentClassifier classifier, List<Agent> agents) {
        this.classifier = classifier;
        Agent chat = null;
        for (Agent agent : agents) {
            agentMap.put(agent.intent(), agent);
            if (agent.intent() == Intent.CHAT) {
                chat = agent;
            }
        }
        this.fallbackAgent = chat;
    }

    @PostConstruct
    void logRegistry() {
        log.info("[AGENT-ROUTER] Agent 注册完成 | agents={} | fallback={}",
                agentMap.keySet(),
                fallbackAgent != null ? fallbackAgent.name() : "NONE");
    }

    /**
     * 路由消息到对应 Agent。
     *
     * <ol>
     *   <li>{@link IntentClassifier#classify} → {@link Intent}</li>
     *   <li>Map 查找 → 找到则执行</li>
     *   <li>未找到 → fallback（CHAT Agent）</li>
     * </ol>
     */
    public void route(AgentContext ctx) {
        Intent intent = classifier.classify(ctx);

        Agent agent = agentMap.get(intent);
        if (agent == null) {
            agent = fallbackAgent;
        }

        if (agent != null) {
            log.debug("[AGENT-ROUTER] {} ← intent={} | userId={}",
                    agent.name(), intent, ctx.userId());
            agent.execute(ctx);
        } else {
            log.warn("[AGENT-ROUTER] 无可用的 Agent | intent={} | userId={}", intent, ctx.userId());
        }
    }
}
