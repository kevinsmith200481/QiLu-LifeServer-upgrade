package com.qilu.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class InboxMqConfig {

    public static final String INBOX_EXCHANGE = "qilu.inbox.exchange";
    public static final String INBOX_DEAD_EXCHANGE = "qilu.inbox.dead.exchange";

    public static final String INBOX_DELIVERY_QUEUE = "qilu.inbox.delivery.queue";
    public static final String INBOX_DEAD_QUEUE = "qilu.inbox.delivery.dead.queue";

    public static final String INBOX_DELIVERY_ROUTING_KEY = "inbox.delivery";
    public static final String INBOX_DEAD_ROUTING_KEY = "inbox.delivery.dead";

    @Bean
    public DirectExchange inboxExchange() {
        return new DirectExchange(INBOX_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange inboxDeadExchange() {
        return new DirectExchange(INBOX_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue inboxDeliveryQueue() {
        Map<String, Object> args = new HashMap<>(2);
        args.put("x-dead-letter-exchange", INBOX_DEAD_EXCHANGE);
        args.put("x-dead-letter-routing-key", INBOX_DEAD_ROUTING_KEY);
        return new Queue(INBOX_DELIVERY_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue inboxDeadQueue() {
        return new Queue(INBOX_DEAD_QUEUE, true);
    }

    @Bean
    public Binding inboxDeliveryBinding() {
        return BindingBuilder.bind(inboxDeliveryQueue())
                .to(inboxExchange())
                .with(INBOX_DELIVERY_ROUTING_KEY);
    }

    @Bean
    public Binding inboxDeadBinding() {
        return BindingBuilder.bind(inboxDeadQueue())
                .to(inboxDeadExchange())
                .with(INBOX_DEAD_ROUTING_KEY);
    }

}
