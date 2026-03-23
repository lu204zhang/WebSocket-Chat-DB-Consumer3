package vito.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import vito.handler.BroadcastWebSocketHandler;

@Configuration
@EnableWebSocket
public class ConsumerWebSocketConfig implements WebSocketConfigurer {

    private final BroadcastWebSocketHandler broadcastWebSocketHandler;
    private final RoomIdHandshakeInterceptor roomIdHandshakeInterceptor;

    /**
     * @param broadcastWebSocketHandler   handler for chat WebSocket
     * @param roomIdHandshakeInterceptor validates room id from path and puts it in attributes
     */
    public ConsumerWebSocketConfig(BroadcastWebSocketHandler broadcastWebSocketHandler,
                                   RoomIdHandshakeInterceptor roomIdHandshakeInterceptor) {
        this.broadcastWebSocketHandler = broadcastWebSocketHandler;
        this.roomIdHandshakeInterceptor = roomIdHandshakeInterceptor;
    }

    /** @param registry WebSocket handler registry to register the handler */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(broadcastWebSocketHandler, "/chat/*")
                .addInterceptors(roomIdHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
