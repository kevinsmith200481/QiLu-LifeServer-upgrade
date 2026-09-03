package com.qilu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qilu.vo.InboxRealtimePayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InboxWebSocketHandler extends TextWebSocketHandler {

    private static final CloseStatus AUTH_FAILED_STATUS = new CloseStatus(4401, "authentication failed");
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, WebSocketSession>> sessionMap =
            new ConcurrentHashMap<>();

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocketSession serialSession = decorate(session);
        if (Boolean.TRUE.equals(session.getAttributes().get(InboxWebSocketHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE))) {
            sendControlMessage(serialSession, authFailedPayload());
            serialSession.close(AUTH_FAILED_STATUS);
            return;
        }
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            serialSession.close(AUTH_FAILED_STATUS);
            return;
        }
        // The decorator serializes heartbeat and business pushes for the same browser connection.
        sessionMap.computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .put(session.getId(), serialSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode request = objectMapper.readTree(message.getPayload());
            if (!"PING".equals(request.path("type").asText())) {
                return;
            }
            WebSocketSession serialSession = findSession(session);
            if (serialSession != null && serialSession.isOpen()) {
                ObjectNode pong = objectMapper.createObjectNode();
                pong.put("type", "PONG");
                if (request.hasNonNull("sentAt")) {
                    pong.set("sentAt", request.get("sentAt"));
                }
                pong.put("serverAt", System.currentTimeMillis());
                sendControlMessage(serialSession, pong);
            }
        } catch (Exception e) {
            log.debug("ignore invalid inbox websocket control frame, sessionId={}", session.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("inbox websocket transport error, sessionId={}", session.getId(), exception);
        removeSession(session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    public void push(InboxRealtimePayload payload) {
        Map<String, WebSocketSession> sessions = sessionMap.get(payload.getUserId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.forEach((sessionId, session) -> {
                if (!session.isOpen()) {
                    removeSession(payload.getUserId(), sessionId);
                    return;
                }
                try {
                    session.sendMessage(textMessage);
                } catch (Exception e) {
                    // A broken tab must not prevent delivery to the user's other active tabs.
                    log.warn("send inbox websocket message failed, userId={}, sessionId={}",
                            payload.getUserId(), sessionId, e);
                    removeSession(payload.getUserId(), sessionId);
                    closeQuietly(session, CloseStatus.SERVER_ERROR);
                }
            });
        } catch (IOException e) {
            log.warn("serialize inbox websocket message failed, userId={}", payload.getUserId(), e);
        }
    }

    int getSessionCount(Long userId) {
        Map<String, WebSocketSession> sessions = sessionMap.get(userId);
        return sessions == null ? 0 : sessions.size();
    }

    int getTrackedUserCount() {
        return sessionMap.size();
    }

    private WebSocketSession decorate(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
    }

    private WebSocketSession findSession(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        Map<String, WebSocketSession> sessions = userId == null ? null : sessionMap.get(userId);
        return sessions == null ? null : sessions.get(session.getId());
    }

    private ObjectNode authFailedPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "AUTH_FAILED");
        payload.put("serverAt", System.currentTimeMillis());
        return payload;
    }

    private void sendControlMessage(WebSocketSession session, JsonNode payload) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    private void removeSession(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            removeSession(userId, session.getId());
        }
    }

    private void removeSession(Long userId, String sessionId) {
        sessionMap.computeIfPresent(userId, (key, sessions) -> {
            sessions.remove(sessionId);
            sessions.entrySet().removeIf(entry -> !entry.getValue().isOpen());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException e) {
            log.debug("close inbox websocket session failed, sessionId={}", session.getId());
        }
    }
}
