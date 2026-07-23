package log.demo.linkDemo.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
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
     * ChatClient —— 通义千问 (qwen-plus)，带会话记忆。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                  MessageChatMemoryAdvisor chatMemoryAdvisor) {
        log.info("[AI-CONFIG] 构建 ChatClient | model=qwen-plus | advisor=MessageChatMemoryAdvisor");
        return chatClientBuilder
                .defaultOptions(DashScopeChatOptions.builder().withModel("qwen-plus").build())
                .defaultAdvisors(chatMemoryAdvisor)
                .build();
    }

    /**
     * 意图分类专用 ChatClient —— qwen-turbo，轻量快速，无记忆。
     * 不设置 temperature/maxTokens，使用模型默认值。
     */
    @Bean
    @Qualifier("intentChatClient")
    public ChatClient intentChatClient(ChatClient.Builder builder) {
        log.info("[AI-CONFIG] 构建 intentChatClient | model=qwen-turbo");
        return builder
                .defaultOptions(DashScopeChatOptions.builder().withModel("qwen-turbo").build())
                .build();
    }
}
