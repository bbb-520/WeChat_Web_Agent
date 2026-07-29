package log.summer.aigc.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import log.summer.aigc.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置 —— 整合会话记忆（ChatMemory）、记忆顾问（Advisor）、属性绑定。
 *
 * @author bbb
 * @since 2026-07-20
 */
@Configuration
@Slf4j
@EnableConfigurationProperties({VoiceProperties.class, BotProperties.class})
public class AiConfig {

    /**
     * 会话记忆存储 —— 基于内存的 {@link ChatMemoryRepository}，重启后数据丢失!
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    /**
     * 会话记忆 —— 以"滑动窗口"方式保留最近 N 条消息，
     * 自动截断超出窗口的历史，避免 Token 超限。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)      // 最多保留 20 条消息（10 轮一问一答）
                .build();
    }

    /**
     * 会话记忆顾问 —— 在每次 ChatClient 调用前后自动执行：
     */
    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * ChatClient —— 通义千问 (qwen-plus)。
     * 不注册 MessageChatMemoryAdvisor——AgentLoop 手动管理 ChatMemory。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        log.info("[AI-CONFIG] 构建 ChatClient | model=qwen-plus | 无默认 Advisor");
        return chatClientBuilder
                .defaultOptions(DashScopeChatOptions.builder().withModel("qwen-plus").build())
                .build();
    }

    /**
     * 全局 ToolCallbackProvider —— 注入到 DashScopeChatModel 内部的
     * DefaultToolCallingManager，使其能找到 @Tool 方法并执行。
     *
     * <p>没有这个 Bean，DashScopeChatModel.internalCall() 在 LLM 返回
     * tool call 后会报 "No ToolCallback found"。</p>
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(ToolRegistry toolRegistry) {
        // 延迟调用：ToolRegistry 在 ContextRefreshedEvent 后才完成扫描，
        // 不能在 Bean 创建时捕获 callbacks，必须在每次调用时动态获取。
        return () -> {
            var callbacks = toolRegistry.getCallbacks();
            return callbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]);
        };
    }
}
