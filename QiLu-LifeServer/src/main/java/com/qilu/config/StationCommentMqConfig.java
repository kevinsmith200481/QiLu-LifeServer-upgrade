package com.qilu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StationCommentMqConfig {

    public static final String COMMENT_EXCHANGE = "qilu.station.comment.exchange";
    public static final String COMMENT_DELETE_QUEUE = "qilu.station.comment.delete.queue";
    public static final String COMMENT_DELETE_ROUTING_KEY = "station.comment.delete";

    @Bean
    public DirectExchange stationCommentExchange() {
        return new DirectExchange(COMMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue stationCommentDeleteQueue() {
        return new Queue(COMMENT_DELETE_QUEUE, true);
    }

    @Bean
    public Binding stationCommentDeleteBinding() {
        return BindingBuilder.bind(stationCommentDeleteQueue())
                .to(stationCommentExchange())
                .with(COMMENT_DELETE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter stationCommentMessageConverter(ObjectMapper objectMapper) {
        // Reuse Boot's mapper so appointment events containing LocalDateTime use
        // the same Java Time modules as HTTP JSON serialization.
        return new Jackson2JsonMessageConverter(objectMapper.copy());
    }
}
