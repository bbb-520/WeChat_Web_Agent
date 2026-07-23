package log.demo.linkDemo.agent;

/**
 * 智能体接口 —— 每个智能体代表一个独立的功能域。
 * 由 {@link AgentRouter} 根据 {@link IntentClassifier} 的分类结果直接路由。
 *
 * <p>异步 Agent：在 {@code execute()} 内部提交异步任务后立即返回 {@code true}，
 * 结果通过 {@link AgentContext#sender()} 回传。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
public interface Agent {

    /** Agent 名称，用于日志追踪 */
    String name();

    /** 该 Agent 处理的意图类型 */
    Intent intent();

    /**
     * 执行 Agent 逻辑。
     *
     * @return {@code true} = 消息已被消费；
     *         {@code false} = 继续尝试后续 Agent（如音色切换后还有剩余文本）
     */
    boolean execute(AgentContext ctx);
}
