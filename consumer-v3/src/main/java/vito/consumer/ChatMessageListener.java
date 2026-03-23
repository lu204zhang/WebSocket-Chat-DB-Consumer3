package vito.consumer;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import vito.metrics.instrumentation.ConsumerMetrics;
import vito.model.QueueMessage;
import vito.room.RoomSessionManager;
import vito.persistence.writebehind.WriteBehindQueue;

@Component
public class ChatMessageListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageListener.class);

    private final RoomSessionManager roomSessionManager;
    private final ObjectMapper objectMapper;
    private final MessageDeduplicator deduplicator;
    private final RetryTracker retryTracker;
    private final RoomBroadcaster roomBroadcaster;
    private final ConsumerMetrics consumerMetrics;
    private final WriteBehindQueue writeBehindQueue;

    /**
     * @param roomSessionManager session manager per room
     * @param objectMapper       for JSON parsing
     * @param deduplicator       for duplicate detection
     * @param retryTracker       for retry count and limit
     * @param roomBroadcaster    for broadcasting to WebSocket sessions
     * @param consumerMetrics    for recording processed/failed/skipped counts
     * @param writeBehindQueue   for async persistence to DynamoDB
     */
    public ChatMessageListener(RoomSessionManager roomSessionManager,
            ObjectMapper objectMapper,
            MessageDeduplicator deduplicator,
            RetryTracker retryTracker,
            RoomBroadcaster roomBroadcaster,
            ConsumerMetrics consumerMetrics,
            WriteBehindQueue writeBehindQueue) {
        this.roomSessionManager = roomSessionManager;
        this.objectMapper       = objectMapper;
        this.deduplicator       = deduplicator;
        this.retryTracker       = retryTracker;
        this.roomBroadcaster    = roomBroadcaster;
        this.consumerMetrics    = consumerMetrics;
        this.writeBehindQueue   = writeBehindQueue;
    }

    /**
     * Parses message, dedups, checks retries, broadcasts, then ack or nack.
     * @param message AMQP message
     * @param channel RabbitMQ channel for ack/nack
     * @throws Exception on parse or broadcast failure
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String json = new String(message.getBody(), StandardCharsets.UTF_8);

        QueueMessage queueMessage;
        try {
            queueMessage = objectMapper.readValue(json, QueueMessage.class);
        } catch (Exception e) {
            log.error("Invalid QueueMessage JSON, skipping message", e);
            channel.basicAck(deliveryTag, false);
            return;
        }

        String messageId = queueMessage.getMessageId();

        if (deduplicator.isDuplicate(messageId)) {
            log.debug("Duplicate message {}, ack and skip", messageId);
            consumerMetrics.recordSkippedDuplicate();
            channel.basicAck(deliveryTag, false);
            return;
        }

        if (retryTracker.exceedsMaxRetries(messageId)) {
            log.warn("Message {} already at max retries ({}), ack and skip", messageId, retryTracker.getMaxRetries());
            channel.basicAck(deliveryTag, false);
            retryTracker.remove(messageId);
            return;
        }

        String roomId = queueMessage.getRoomId();
        if (roomSessionManager.getSessions(roomId).isEmpty()) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        boolean success = roomBroadcaster.broadcast(roomId, queueMessage);

        if (!success) {
            consumerMetrics.recordFailed();
            int newCount = retryTracker.incrementAndGet(messageId);
            if (newCount < retryTracker.getMaxRetries()) {
                log.warn("Broadcast failed for message {}, retry {}/{}", messageId, newCount,
                        retryTracker.getMaxRetries());
                channel.basicNack(deliveryTag, false, true);
            } else {
                log.error("Message {} exceeded max retries ({}), nack without requeue", messageId,
                        retryTracker.getMaxRetries());
                channel.basicNack(deliveryTag, false, false);
                retryTracker.remove(messageId);
            }
        } else {
            channel.basicAck(deliveryTag, false);
            retryTracker.remove(messageId);
            deduplicator.markProcessed(messageId);
            consumerMetrics.recordProcessed();

            // Write-behind: offer to persistence queues (non-blocking, best-effort)
            boolean offeredMsg   = writeBehindQueue.offerMessage(queueMessage);
            boolean offeredStats = writeBehindQueue.offerStats(queueMessage);
            if (!offeredMsg || !offeredStats) {
                log.warn("WriteBehindQueue full, skipping persistence for messageId={}", messageId);
                consumerMetrics.recordWriteBehindDropped();
            }
        }
    }
}
