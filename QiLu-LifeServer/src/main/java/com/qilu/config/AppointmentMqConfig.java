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
public class AppointmentMqConfig {

    public static final String APPOINTMENT_EXCHANGE = "qilu.appointment.exchange";
    public static final String APPOINTMENT_RETRY_EXCHANGE = "qilu.appointment.retry.exchange";
    public static final String APPOINTMENT_DEAD_EXCHANGE = "qilu.appointment.dead.exchange";

    public static final String APPOINTMENT_NOTIFICATION_QUEUE = "qilu.appointment.notification.queue";
    public static final String APPOINTMENT_RETRY_QUEUE = "qilu.appointment.notification.retry.queue";
    public static final String APPOINTMENT_DEAD_QUEUE = "qilu.appointment.notification.dead.queue";

    public static final String APPOINTMENT_EVENT_ROUTING_KEY = "appointment.event";
    public static final String APPOINTMENT_RETRY_ROUTING_KEY = "appointment.event.retry";
    public static final String APPOINTMENT_DEAD_ROUTING_KEY = "appointment.event.dead";

    @Bean
    public DirectExchange appointmentExchange() {
        return new DirectExchange(APPOINTMENT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange appointmentRetryExchange() {
        return new DirectExchange(APPOINTMENT_RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange appointmentDeadExchange() {
        return new DirectExchange(APPOINTMENT_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue appointmentNotificationQueue() {
        Map<String, Object> args = new HashMap<>(2);
        args.put("x-dead-letter-exchange", APPOINTMENT_DEAD_EXCHANGE);
        args.put("x-dead-letter-routing-key", APPOINTMENT_DEAD_ROUTING_KEY);
        return new Queue(APPOINTMENT_NOTIFICATION_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue appointmentRetryQueue() {
        Map<String, Object> args = new HashMap<>(3);
        args.put("x-message-ttl", 5000);
        args.put("x-dead-letter-exchange", APPOINTMENT_EXCHANGE);
        args.put("x-dead-letter-routing-key", APPOINTMENT_EVENT_ROUTING_KEY);
        return new Queue(APPOINTMENT_RETRY_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue appointmentDeadQueue() {
        return new Queue(APPOINTMENT_DEAD_QUEUE, true);
    }

    @Bean
    public Binding appointmentNotificationBinding() {
        return BindingBuilder.bind(appointmentNotificationQueue())
                .to(appointmentExchange())
                .with(APPOINTMENT_EVENT_ROUTING_KEY);
    }

    @Bean
    public Binding appointmentRetryBinding() {
        return BindingBuilder.bind(appointmentRetryQueue())
                .to(appointmentRetryExchange())
                .with(APPOINTMENT_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding appointmentDeadBinding() {
        return BindingBuilder.bind(appointmentDeadQueue())
                .to(appointmentDeadExchange())
                .with(APPOINTMENT_DEAD_ROUTING_KEY);
    }
}
