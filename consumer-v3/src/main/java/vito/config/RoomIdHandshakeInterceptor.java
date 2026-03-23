package vito.config;


import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static vito.config.Constants.MAX_ROOM_ID;
import static vito.config.Constants.MIN_ROOM_ID;
import static vito.config.Constants.ROOM_ID_ATTR;
import static vito.config.Constants.USER_ID_ATTR;
import static vito.config.Constants.USERNAME_ATTR;

@Component
public class RoomIdHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * Parses room id from path, validates range, puts roomId/userId/username in attributes.
     * @param request    HTTP request
     * @param response   HTTP response (status set to 400 on failure)
     * @param wsHandler  WebSocket handler
     * @param attributes handshake attributes to store roomId, userId, username
     * @return true if path has valid room id and handshake may continue; false otherwise
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String roomIdStr = path.substring(lastSlash + 1);
        if (roomIdStr.isEmpty()){
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        int roomId;
        try {
            roomId = Integer.parseInt(roomIdStr);
        }catch (NumberFormatException e){
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (roomId < MIN_ROOM_ID || roomId > MAX_ROOM_ID) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        attributes.put(ROOM_ID_ATTR, String.valueOf(roomId));
        Optional.ofNullable(request.getURI().getQuery())
                .stream()
                .flatMap(q -> Stream.of(q.split("&")))
                .map(pair -> pair.split("=", 2))
                .filter(p -> p.length == 2)
                .forEach(p -> {
                    String key = p[0].trim();
                    String value = p[1].trim();
                    if (USER_ID_ATTR.equalsIgnoreCase(key) && !value.isEmpty()) {
                        attributes.put(USER_ID_ATTR, value);
                    } else if (USERNAME_ATTR.equalsIgnoreCase(key)) {
                        attributes.put(USERNAME_ATTR, value);
                    }
                });
        return true;
    }

    /**
     * No-op. Called after handshake completes.
     * @param request   HTTP request
     * @param response  HTTP response
     * @param wsHandler WebSocket handler
     * @param exception exception from handshake, or null
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
    }
}
