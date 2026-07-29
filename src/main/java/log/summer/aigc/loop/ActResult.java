package log.summer.aigc.loop;


public record ActResult(
        boolean success,
        Object data,
        String errorMessage,
        boolean suspend,
        String suspendReason,
        boolean requiresConfirmation) {

    public static ActResult success(Object data) {
        return new ActResult(true, data, null, false, null, false);
    }

    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage, false, null, false);
    }

    public static ActResult suspend(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }


    public static ActResult confirmRequired(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }
}
