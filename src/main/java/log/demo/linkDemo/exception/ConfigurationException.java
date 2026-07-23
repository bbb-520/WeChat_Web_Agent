package log.demo.linkDemo.exception;

/**
 * 配置异常 —— 关键配置项缺失或无效时在启动阶段抛出。
 *
 * @author bbb
 * @since 2026-07-22
 */
public class ConfigurationException extends BotException {

    private final String configKey;

    public ConfigurationException(String configKey, String message) {
        super(message);
        this.configKey = configKey;
    }

    public ConfigurationException(String configKey, String message, Throwable cause) {
        super(message, cause);
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
