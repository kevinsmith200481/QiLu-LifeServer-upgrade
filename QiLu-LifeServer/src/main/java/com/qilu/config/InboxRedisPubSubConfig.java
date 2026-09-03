package com.qilu.config;

import com.qilu.service.impl.InboxRealtimeServiceImpl;
import com.qilu.utils.RedisConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import javax.annotation.Resource;

@Configuration
public class InboxRedisPubSubConfig {

    @Resource
    private InboxRealtimeServiceImpl inboxRealtimeService;

    @Bean
    public RedisMessageListenerContainer inboxRedisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(inboxRealtimeService, new ChannelTopic(RedisConstants.INBOX_WS_CHANNEL));
        return container;
    }
}
