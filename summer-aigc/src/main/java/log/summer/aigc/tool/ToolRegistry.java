package log.summer.aigc.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import log.summer.aigc.loop.ActResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Scans the Spring context for beans with {@link Tool @Tool}-annotated methods,
 * builds a combined {@link ToolCallback} list for the LLM, and provides
 * {@link #execute(String, Map)} for {@code AgentLoop} to invoke tools.
 *
 * <p>Adapted to Spring AI 1.0.0 GA — uses {@link ToolCallback} (not
 * the M6-era {@code FunctionCallback}) and {@link MethodToolCallbackProvider}
 * for bean scanning.</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    private List<ToolCallback> callbacks = List.of();
    private Map<String, ToolCallback> callbackMap = Map.of();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scan all beans in the context for {@link Tool @Tool}-annotated methods
     * and build a combined callback list usable by {@code ChatClient.toolCallbacks()}.
     */
    @PostConstruct
    public void scan() {
        List<Object> toolBeans = new ArrayList<>();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                for (var method : bean.getClass().getMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        toolBeans.add(bean);
                        break; // one bean may have multiple @Tool methods; add it once
                    }
                }
            } catch (Exception ignored) {
                // Skip beans that cannot be instantiated (e.g. scoped proxies,
                // lazy beans that fail, or beans in the middle of creation).
            }
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

        log.info("[TOOL-REGISTRY] Scanned {} tool beans → {} ToolCallbacks",
                toolBeans.size(), callbacks.size());
    }

    /**
     * Returns all callbacks for injection into the LLM call.
     */
    public List<ToolCallback> getCallbacks() {
        return callbacks;
    }

    /**
     * Execute a named tool with the given arguments.
     * <p>
     * Arguments are serialized to JSON, passed to {@link ToolCallback#call(String)},
     * and the raw result string is deserialised back. If the result string is valid
     * JSON that parses as an {@link ActResult}, it is returned directly; otherwise
     * it is wrapped in {@link ActResult#success(Object)}.
     * </p>
     *
     * @param toolName  the name of the tool (matches {@code @Tool.name()} or the
     *                  method name if not explicitly set)
     * @param arguments the resolved arguments to pass to the tool
     * @return an ActResult indicating success or failure
     */
    public ActResult execute(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = callbackMap.get(toolName);
        if (callback == null) {
            return ActResult.failure("Unknown tool: " + toolName);
        }

        try {
            String jsonArgs = objectMapper.writeValueAsString(arguments);
            String resultStr = callback.call(jsonArgs);

            // Attempt to deserialize the result string back into an ActResult.
            // If the tool method returned an ActResult, the serialized JSON
            // will contain the expected fields.
            try {
                return objectMapper.readValue(resultStr, ActResult.class);
            } catch (Exception e) {
                // The result is not an ActResult — wrap it.
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
