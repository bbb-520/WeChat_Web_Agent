package log.demo.linkDemo.agent.command;

import log.demo.linkDemo.service.MessageSender;

/**
 * 命令处理器函数式接口。
 *
 * @author bbb
 * @since 2026-07-23
 */
public interface Command {

    void execute(String userId, String cmd, MessageSender sender, boolean isRunning);
}
