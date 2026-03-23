package vito.model;

import org.springframework.web.socket.WebSocketSession;

public class UserInfo {
    private final String userId;
    private final String username;
    private final String roomId;
    private final WebSocketSession session;

    /**
     * @param userId   user id
     * @param username display name (may be null)
     * @param roomId   room id
     * @param session  WebSocket session for this user in the room
     */
    public UserInfo(String userId, String username, String roomId, WebSocketSession session) {
        this.userId = userId;
        this.username = username;
        this.roomId = roomId;
        this.session = session;
    }

    /** @return user id */
    public String getUserId() {
        return userId;
    }

    /** @return display username (may be null) */
    public String getUsername() {
        return username;
    }

    /** @return room id the user is in */
    public String getRoomId() {
        return roomId;
    }

    /** @return WebSocket session */
    public WebSocketSession getSession() {
        return session;
    }
}
