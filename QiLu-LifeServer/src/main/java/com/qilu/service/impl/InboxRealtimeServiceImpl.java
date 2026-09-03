package com.qilu.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.config.InboxWebSocketHandler;
import com.qilu.service.IInboxRealtimeService;
import com.qilu.utils.RedisConstants;
import com.qilu.vo.InboxRealtimePayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class InboxRealtimeServiceImpl implements IInboxRealtimeService, MessageListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private InboxWebSocketHandler inboxWebSocketHandler;

    @Override
    public void publish(InboxRealtimePayload payload) {
        try {
            stringRedisTemplate.convertAndSend(RedisConstants.INBOX_WS_CHANNEL, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("publish inbox websocket payload failed, userId={}", payload.getUserId(), e);
        }
    }

    @Override
    public void pushLocal(InboxRealtimePayload payload) {
        inboxWebSocketHandler.push(payload);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            InboxRealtimePayload payload = objectMapper.readValue(message.getBody(), InboxRealtimePayload.class);
            pushLocal(payload);
        } catch (Exception e) {
            log.warn("consume inbox websocket pubsub failed", e);
        }
    }
}
