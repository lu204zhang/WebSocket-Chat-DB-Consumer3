package vito.persistence.util;

import vito.model.QueueMessage;
import vito.persistence.model.PersistentMessage;

public final class QueueMessageConverter {

    private QueueMessageConverter() {}

    /**
     * Converts a {@link QueueMessage} to a {@link PersistentMessage} for DynamoDB storage.
     * @param q source message from the broker layer
     * @return persistence-layer model with status set to "SUCCESS"
     */
    public static PersistentMessage toPersistentMessage(QueueMessage q) {
        return PersistentMessage.builder()
                .messageId(q.getMessageId())
                .roomId(q.getRoomId())
                .userId(q.getUserId())
                .username(q.getUsername())
                .message(q.getMessage())
                .timestamp(q.getTimestamp() != null ? q.getTimestamp().toEpochMilli() : 0L)
                .messageType(q.getMessageType())
                .status("SUCCESS")
                .serverId(q.getServerId())
                .clientIp(q.getClientIp())
                .build();
    }
}
