package log.summer.aigc.port;

/**
 * Inbound port for bot message reception.
 * Transport adapters (ILink, Feishu, Discord) implement this interface.
 */
@FunctionalInterface
public interface BotInboundPort {
    void onMessage(BotMessage msg);
}
