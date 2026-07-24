package log.demo.linkDemo.tools;

import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.exception.GlobalExceptionHandler;
import log.demo.linkDemo.tools.idiom.IdiomGameService;
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
 *   <li>v2.1：统一异常拦截 —— 未处理异常委托 {@link GlobalExceptionHandler} 格式化</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class AgentRouter {

    private final IntentClassifier classifier;
    private final GlobalExceptionHandler exceptionHandler;
    private final IdiomGameService idiomGameService;
    //用EnumMap直接查找对应意图的 Agent 并执行（使用 CHAT 作为兜底 Agent）
    private final Map<Intent, Agent> agentMap = new EnumMap<>(Intent.class);
    private final Agent fallbackAgent;

    /**
     * Spring 注入所有 Agent Bean，构建 Intent → Agent 映射。
     * CHAT Agent 同时作为兜底。
     */
    public AgentRouter(IntentClassifier classifier,
                       GlobalExceptionHandler exceptionHandler,
                       IdiomGameService idiomGameService,
                       List<Agent> agents) {
        this.classifier = classifier;
        this.exceptionHandler = exceptionHandler;
        this.idiomGameService = idiomGameService;

        Agent chat = null;
        for (Agent agent : agents) {
            agentMap.put(agent.intent(), agent);
            if (agent.intent() == Intent.CHAT) {
                chat = agent;
            }
        }
        this.fallbackAgent = chat;
    }

    /**
     * 在 Bean 初始化完成后，自动打印所有已注册的 Agent 信息
     */
    @PostConstruct
    void logRegistry() {
        log.info("[AGENT-ROUTER] Agent 注册完成 | agents={} | fallback={}",
                agentMap.keySet(),      //获取 agentMap中所有已注册的 Intent 键的 Set 集合
                fallbackAgent != null ? fallbackAgent.name() : "NONE");
    }

    /**
     * 路由消息到对应 Agent。
     *
     * <ol>
     *   <li>{@link IntentClassifier#classify} → {@link Intent}</li>
     *   <li>Map 查找 → 找到则执行</li>
     *   <li>未找到 → fallback（CHAT Agent）</li>
     *   <li>未处理异常 → {@link GlobalExceptionHandler} 统一格式化返回</li>
     * </ol>
     */
    public void route(AgentContext ctx) {
        // ── 成语接龙游戏拦截：游戏中非命令文本优先路由到游戏 ──
        if (ctx.hasText() && idiomGameService.isUserInGame(ctx.userId())) {
            String text = ctx.text().trim();
            if (!text.startsWith("/")) {
                String gameResult = idiomGameService.handleInput(ctx.userId(), text);
                if (gameResult != null) {
                    ctx.sender().sendText(ctx.userId(), gameResult);
                    return;
                }
                // gameResult == null 表示不在游戏中（会话刚好过期），继续正常流程
            }
        }

        Intent intent = classifier.classify(ctx);

        //通过意图获取Agent实例
        Agent agent = agentMap.get(intent);
        if (agent == null) {
            agent = fallbackAgent;
        }

        if (agent != null) {
            log.debug("[AGENT-ROUTER] {} ← intent={} | userId={}",
                    agent.name(), intent, ctx.userId());
            try {
                agent.execute(ctx);
            } catch (Exception e) {
                log.error("[AGENT-ROUTER] Agent 执行异常 | agent={} | userId={} | intent={}",
                        agent.name(), ctx.userId(), intent, e);
                //将 Agent 执行过程中抛出的异常交给全局异常处理器统一处理
                exceptionHandler.handle(ctx.userId(), ctx.sender(),
                        agent.name(), e);
            }
        } else {
            log.warn("[AGENT-ROUTER] 无可用的 Agent | intent={} | userId={}", intent, ctx.userId());
            try {
                ctx.sender().sendText(ctx.userId(),
                        "服务暂不可用，请稍后重试。输入 /help 查看可用命令。");
            } catch (Exception ignored) {
                log.error("[AGENT-ROUTER] 连错误消息都无法发送 | userId={}", ctx.userId());
            }
        }
    }
}
