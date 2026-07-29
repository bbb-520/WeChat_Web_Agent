package log.summer.aigc.loop;

import log.summer.aigc.port.BotMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ArgumentResolver {

    private static final String IMAGE_PLACEHOLDER = "${message.image}";
    private static final String FILE_PLACEHOLDER = "${message.file}";
    private static final String FILE_NAME_PLACEHOLDER = "${message.fileName}";


    public Map<String, Object> resolve(Map<String, Object> rawArgs, BotMessage msg) {
        // 新建 Map，避免修改原始参数
        Map<String, Object> resolved = new LinkedHashMap<>(rawArgs);

        // 遍历所有参数
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            Object value = entry.getValue();
            // 只处理字符串类型的值
            if (value instanceof String str) {
                switch (str) {
                    // 值为 "${message.image}"
                    case IMAGE_PLACEHOLDER -> {
                        // 当前消息中是否有图片
                        if (msg.hasImage()) {
                            // 替换为图片的 byte[]
                            entry.setValue(msg.imageBytes());
                        } else {
                            // 无图片 → 抛出异常
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.image} but no image in current message");
                        }
                    }
                    // 值为 "${message.file}"
                    case FILE_PLACEHOLDER -> {
                        // 当前消息中是否有文件
                        if (msg.hasFile()) {
                            // 替换为文件的 byte[]
                            entry.setValue(msg.fileBytes());
                        } else {
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.file} but no file in current message");
                        }
                    }
                    // 值为 "${message.fileName}"
                    case FILE_NAME_PLACEHOLDER -> {
                        // 文件名是否存在
                        if (msg.fileName() != null) {
                            // 替换为文件名
                            entry.setValue(msg.fileName());
                        } else {
                            throw new PlaceholderResolutionException(
                                    "LLM referenced ${message.fileName} but no fileName in current message");
                        }
                    }
                }
            }
        }

        return resolved;
    }

    public static class PlaceholderResolutionException extends RuntimeException {
        public PlaceholderResolutionException(String message) {
            super(message);
        }
    }
}
