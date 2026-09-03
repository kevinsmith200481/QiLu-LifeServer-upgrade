package com.qilu.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.vo.InboxRealtimePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxWebSocketHandlerTest {

    private InboxWebSocketHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new InboxWebSocketHandler();
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(handler, "objectMapper", objectMapper);
    }

    @Test
    void shouldReplyPongWithClientAndServerTimestamps() throws Exception {
        WebSocketSession session = session("socket-1", 101L);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\",\"sentAt\":12345}"));

        ArgumentCaptor<WebSocketMessage<?>> messageCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        JsonNode payload = objectMapper.readTree((String) messageCaptor.getValue().getPayload());
        assertThat(payload.path("type").asText()).isEqualTo("PONG");
        assertThat(payload.path("sentAt").asLong()).isEqualTo(12345L);
        assertThat(payload.path("serverAt").asLong()).isPositive();
    }

    @Test
    void shouldIgnoreUnknownAndInvalidControlFrames() throws Exception {
        WebSocketSession session = session("socket-2", 102L);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"UNKNOWN\"}"));
        handler.handleTextMessage(session, new TextMessage("not-json"));

        verify(session, never()).sendMessage(any());
        assertThat(handler.getSessionCount(102L)).isEqualTo(1);
    }

    @Test
    void oneBrokenSessionShouldNotInterruptOtherSessions() throws Exception {
        WebSocketSession brokenSession = session("broken", 103L);
        WebSocketSession healthySession = session("healthy", 103L);
        handler.afterConnectionEstablished(brokenSession);
        handler.afterConnectionEstablished(healthySession);
        doThrow(new IOException("connection reset")).when(brokenSession).sendMessage(any());

        InboxRealtimePayload payload = new InboxRealtimePayload();
        payload.setUserId(103L);
        payload.setMessageId(9001L);
        payload.setTitle("test notice");
        handler.push(payload);

        verify(healthySession).sendMessage(any());
        assertThat(handler.getSessionCount(103L)).isEqualTo(1);
    }

    @Test
    void closeAndTransportErrorShouldRemoveSessionsAndEmptyUsers() throws Exception {
        WebSocketSession closedSession = session("closed", 104L);
        WebSocketSession errorSession = session("error", 104L);
        handler.afterConnectionEstablished(closedSession);
        handler.afterConnectionEstablished(errorSession);

        handler.afterConnectionClosed(closedSession, CloseStatus.NORMAL);
        assertThat(handler.getSessionCount(104L)).isEqualTo(1);

        handler.handleTransportError(errorSession, new IOException("broken pipe"));
        assertThat(handler.getSessionCount(104L)).isZero();
        assertThat(handler.getTrackedUserCount()).isZero();
        verify(errorSession).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void authenticationFailureShouldSendControlFrameAndCloseWithoutTracking() throws Exception {
        WebSocketSession session = session("unauthorized", null);
        session.getAttributes().put(InboxWebSocketHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true);

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<WebSocketMessage<?>> messageCaptor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        JsonNode payload = objectMapper.readTree((String) messageCaptor.getValue().getPayload());
        assertThat(payload.path("type").asText()).isEqualTo("AUTH_FAILED");
        ArgumentCaptor<CloseStatus> closeCaptor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(closeCaptor.capture());
        assertThat(closeCaptor.getValue().getCode()).isEqualTo(4401);
        assertThat(handler.getTrackedUserCount()).isZero();
    }

    private WebSocketSession session(String sessionId, Long userId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        if (userId != null) {
            attributes.put("userId", userId);
        }
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
