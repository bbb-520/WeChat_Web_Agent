package log.summer.aigc.port;

import log.summer.common.enums.RouteContext;

/**
 * Normalized inbound message DTO — platform-agnostic.
 * All fields except userId are nullable.
 */
public record BotMessage(
    String userId,
    String text,
    byte[] imageBytes,
    byte[] fileBytes,
    String fileName,
    RouteContext context
) {
    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasImage() {
        return imageBytes != null && imageBytes.length > 0;
    }

    public boolean hasFile() {
        return fileBytes != null && fileBytes.length > 0;
    }
}
