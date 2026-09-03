package com.qilu.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.config.InboxWebSocketHandler;
import com.qilu.vo.InboxRealtimePayload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxRealtimeServiceImplTest {

    @Test
    void sameRedisPublicationShouldReachHandlersInTwoApplicationInstances() {
        InboxWebSocketHandler firstHandler = mock(InboxWebSocketHandler.class);
        InboxWebSocketHandler secondHandler = mock(InboxWebSocketHandler.class);
        InboxRealtimeServiceImpl firstInstance = service(firstHandler);
        InboxRealtimeServiceImpl secondInstance = service(secondHandler);
        Message redisMessage = mock(Message.class);
        when(redisMessage.getBody()).thenReturn(("{\"userId\":501,\"messageId\":9001,"
                + "\"messageType\":\"SYSTEM_NOTICE\",\"title\":\"test\"}")
                .getBytes(StandardCharsets.UTF_8));

        firstInstance.onMessage(redisMessage, null);
        secondInstance.onMessage(redisMessage, null);

        ArgumentCaptor<InboxRealtimePayload> firstPayload = ArgumentCaptor.forClass(InboxRealtimePayload.class);
        ArgumentCaptor<InboxRealtimePayload> secondPayload = ArgumentCaptor.forClass(InboxRealtimePayload.class);
        verify(firstHandler).push(firstPayload.capture());
        verify(secondHandler).push(secondPayload.capture());
        assertThat(firstPayload.getValue().getMessageId()).isEqualTo(9001L);
        assertThat(secondPayload.getValue().getMessageId()).isEqualTo(9001L);
        assertThat(firstPayload.getValue().getUserId()).isEqualTo(501L);
        assertThat(secondPayload.getValue().getUserId()).isEqualTo(501L);
    }

    private InboxRealtimeServiceImpl service(InboxWebSocketHandler handler) {
        InboxRealtimeServiceImpl service = new InboxRealtimeServiceImpl();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "inboxWebSocketHandler", handler);
        return service;
    }
}
