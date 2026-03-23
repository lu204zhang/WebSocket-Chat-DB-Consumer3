package vito.persistence.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vito.model.MessageType;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PersistentMessage {
    private String messageId;
    private String roomId;
    private String userId;
    private String username;
    private String message;
    private long timestamp;
    private MessageType messageType;
    private String status;
    private String serverId;
    private String clientIp;
}
