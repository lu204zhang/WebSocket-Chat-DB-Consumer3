package vito.room;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import vito.model.UserInfo;

import static vito.config.Constants.USER_ID_ATTR;
import static vito.config.Constants.USERNAME_ATTR;

@Component
public class RoomSessionManager {
    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserInfo> activeUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionIdToUserId = new ConcurrentHashMap<>();

    /**
     * Adds session to the room and optionally records user info from attributes.
     * @param roomId  room id
     * @param session WebSocket session to add
     */
    public void addSession(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        String userId = (String) session.getAttributes().get(USER_ID_ATTR);
        if (userId != null && !userId.isBlank()) {
            String username = (String) session.getAttributes().get(USERNAME_ATTR);
            activeUsers.put(userId, new UserInfo(userId, username, roomId, session));
            sessionIdToUserId.put(session.getId(), userId);
        }
    }

    /**
     * Removes session from the room and clears user mapping.
     * @param roomId  room id
     * @param session WebSocket session to remove
     */
    public void removeSession(String roomId, WebSocketSession session) {
        roomSessions.computeIfPresent(roomId, (k, v) -> {
            v.remove(session);
            return v.isEmpty() ? null : v;
        });
        String userId = sessionIdToUserId.remove(session.getId());
        if (userId != null) {
            activeUsers.remove(userId);
        }
    }

    /**
     * @param roomId room id
     * @return live set of WebSocket sessions in the room (empty if none)
     */
    public Set<WebSocketSession> getSessions(String roomId) {
        return roomSessions.getOrDefault(roomId, ConcurrentHashMap.newKeySet());
    }

    /**
     * @param userId user id
     * @return user info for the userId or null if not in any room
     */
    public UserInfo getActiveUser(String userId) {
        return activeUsers.get(userId);
    }
}
