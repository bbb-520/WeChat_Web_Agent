package log.summer.aigc.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import log.summer.aigc.loop.ActResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具注册中心 —— 在所有 Bean 初始化完成后扫描 @Tool 方法。
 *
 * <p>使用 {@link ContextRefreshedEvent} 替代 {@code @PostConstruct}，
 * 确保扫描时所有依赖（ImageGenService、TTSEngine 等）已就绪，
 * 不会因初始化顺序导致工具被静默跳过。</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
public class ToolRegistry implements ApplicationListener<ContextRefreshedEvent> {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    private volatile List<ToolCallback> callbacks = List.of();
    private volatile Map<String, ToolCallback> callbackMap = Map.of();
    private volatile boolean scanned = false;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 在 Spring 容器完全刷新后扫描所有 @Tool Bean。
     * 此时所有单例 Bean 已实例化、依赖已注入、@PostConstruct 已执行。
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 只在根容器刷新时扫描一次（避免子容器重复触发）
        if (event.getApplicationContext().getParent() == null && !scanned) {
            scan();
            scanned = true;
        }
    }

    private void scan() {
        List<Object> toolBeans = new ArrayList<>();
        List<String> skippedBeans = new ArrayList<>();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                for (var method : bean.getClass().getMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        toolBeans.add(bean);
                        break;
                    }
                }
            } catch (Exception e) {
                skippedBeans.add(beanName + "(" + e.getMessage() + ")");
            }
        }

        if (!skippedBeans.isEmpty()) {
            log.warn("[TOOL-REGISTRY] {} 个 bean 无法实例化: {}",
                    skippedBeans.size(), skippedBeans);
        }

        if (!toolBeans.isEmpty()) {
            MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                    .toolObjects(toolBeans.toArray())
                    .build();
            this.callbacks = List.of(provider.getToolCallbacks());
        }

        Map<String, ToolCallback> map = new HashMap<>();
        for (ToolCallback cb : callbacks) {
            map.put(cb.getToolDefinition().name(), cb);
        }
        this.callbackMap = Map.copyOf(map);

        log.info("[TOOL-REGISTRY] ✅ 扫描完成: {} tool beans → {} ToolCallbacks: {}",
                toolBeans.size(), callbacks.size(),
                callbackMap.keySet().stream().sorted().toList());
    }

    public List<ToolCallback> getCallbacks() {
        return callbacks;
    }

    public ActResult execute(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = callbackMap.get(toolName);
        if (callback == null) {
            return ActResult.failure("Unknown tool: " + toolName);
        }

        try {
            String jsonArgs = objectMapper.writeValueAsString(arguments);
            String resultStr = callback.call(jsonArgs);

            try {
                return objectMapper.readValue(resultStr, ActResult.class);
            } catch (Exception e) {
                return ActResult.success(resultStr);
            }
        } catch (Exception e) {
            log.error("[TOOL-REGISTRY] Tool execution failed | tool={}", toolName, e);
            return ActResult.failure(
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Tool execution failed: " + e.getClass().getSimpleName());
        }
    }
}
