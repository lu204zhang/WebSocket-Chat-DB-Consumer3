package vito.persistence.model;

import lombok.Data;
import vito.model.MessageType;

@Data
public class UserActivity {
    private String userId;
    private String roomId;
    private MessageType type;
    private long timestamp;
    private String sessionId;
}