package com.qilu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

@Configuration
@EnableWebSocket
public class InboxWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private InboxWebSocketHandler inboxWebSocketHandler;

    @Resource
    private InboxWebSocketHandshakeInterceptor inboxWebSocketHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(inboxWebSocketHandler, "/ws/inbox")
                .addInterceptors(inboxWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
