package vito.consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import vito.model.QueueMessage;
import vito.room.RoomSessionManager;

@Component
public class RoomBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcaster.class);

    private final RoomSessionManager roomSessionManager;
    private final ObjectMapper objectMapper;
    private final ExecutorService broadcastExecutor;

    /**
     * @param roomSessionManager session manager per room
     * @param objectMapper       for JSON serialization
     * @param broadcastExecutor  used to send messages asynchronously to sessions
     */
    public RoomBroadcaster(RoomSessionManager roomSessionManager,
            ObjectMapper objectMapper,
            ExecutorService broadcastExecutor) {
        this.roomSessionManager = roomSessionManager;
        this.objectMapper = objectMapper;
        this.broadcastExecutor = broadcastExecutor;
    }

    /**
     * Sends the message to all sessions in the room; removes sessions that fail.
     * 
     * @param roomId  room id
     * @param message message to broadcast
     * @return true if all sends succeeded, false if at least one failed
     */
    public boolean broadcast(String roomId, QueueMessage message) {
        Set<WebSocketSession> sessions = roomSessionManager.getSessions(roomId);
        if (sessions.isEmpty()) {
            return true;
        }
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to serialize QueueMessage", e);
            return false;
        }
        TextMessage textMessage = new TextMessage(jsonPayload);
        List<WebSocketSession> sessionList = new ArrayList<>(sessions);
        AtomicBoolean anyFailed = new AtomicBoolean(false);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (WebSocketSession session : sessionList) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                synchronized (session) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (Exception e) {
                        log.warn("Broadcast failed for session in room {}, removing session", roomId, e);
                        roomSessionManager.removeSession(roomId, session);
                        anyFailed.set(true);
                    }
                }
            }, broadcastExecutor);
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return !anyFailed.get();
    }
}
