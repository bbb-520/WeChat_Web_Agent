package log.demo.linkDemo.agent.file;

import log.demo.linkDemo.agent.Agent;
import log.demo.linkDemo.agent.AgentContext;
import log.demo.linkDemo.agent.Intent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 文件处理智能体 —— Tika 文本提取 + AI 分析 + RAG 切片。
 * 意图 = {@link Intent#FILE}。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileAgent implements Agent {

    private final FileRecognitionService fileRecognitionService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override public String name() { return "file"; }
    @Override public Intent intent() { return Intent.FILE; }

    @Override
    public boolean execute(AgentContext ctx) {
        executor.submit(() -> doFileRecognition(ctx));
        return true;
    }

    private void doFileRecognition(AgentContext ctx) {
        log.info("[FILE] 开始处理 | userId={} | fileName={} | size={}bytes",
                ctx.userId(), ctx.fileName(), ctx.fileBytes().length);
        try {
            String result = fileRecognitionService.recognize(ctx.userId(), ctx.fileBytes(), ctx.fileName());
            ctx.sender().sendText(ctx.userId(), result);
        } catch (Exception e) {
            log.error("[FILE] 处理失败 | userId={} | fileName={}", ctx.userId(), ctx.fileName(), e);
            ctx.sender().sendText(ctx.userId(), "文件处理失败：" + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
        try { executor.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
