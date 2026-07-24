package log.demo.linkDemo.tools.command;

import log.demo.linkDemo.tools.Agent;
import log.demo.linkDemo.tools.AgentContext;
import log.demo.linkDemo.tools.Intent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 命令智能体 —— 处理 / 开头的系统命令。
 * 注册到 {@code AgentRouter}，意图 = {@link Intent#COMMAND}。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandAgent implements Agent {

    private final CommandRegistry registry;

    @Override public String name() { return "command"; }
    @Override public Intent intent() { return Intent.COMMAND; }

    @Override
    public boolean execute(AgentContext ctx) {
        registry.execute(ctx.userId(), ctx.text(), ctx.sender(), ctx.isRunning());
        return true;
    }
}
