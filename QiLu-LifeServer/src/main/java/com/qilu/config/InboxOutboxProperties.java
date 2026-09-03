package com.qilu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qilu.inbox.outbox")
public class InboxOutboxProperties {

    private boolean enabled = true;
    private int batchSize = 100;
    private int leaseSeconds = 30;
    private long confirmTimeoutMillis = 5000L;
    private int maxPublishAttempts = 5;
    private int maxDeliveryAttempts = 5;
    private String routingKey = InboxMqConfig.INBOX_DELIVERY_ROUTING_KEY;
    private int immediateThreads = 2;
    private int immediateQueueCapacity = 1000;
}
