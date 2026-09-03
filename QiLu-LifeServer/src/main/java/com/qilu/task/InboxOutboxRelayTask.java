package com.qilu.task;

import com.qilu.config.InboxSchemaInitializer;
import com.qilu.service.InboxOutboxRelayService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InboxOutboxRelayTask {

    private final InboxOutboxRelayService relayService;
    private final InboxSchemaInitializer schemaInitializer;
    private final Executor executor;
    private final AtomicBoolean scanRunning = new AtomicBoolean();

    public InboxOutboxRelayTask(InboxOutboxRelayService relayService,
                                InboxSchemaInitializer schemaInitializer,
                                @Qualifier("inboxOutboxExecutor") Executor executor) {
        this.relayService = relayService;
        this.schemaInitializer = schemaInitializer;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${qilu.inbox.outbox.scan-delay-millis:500}")
    public void relayReadyTasks() {
        if (!schemaInitializer.isOutboxSchemaReady() || !scanRunning.compareAndSet(false, true)) {
            return;
        }
        // A confirm timeout must not occupy Spring's shared scheduler thread.
        // The atomic gate also prevents fixed-delay ticks from queueing duplicate scans.
        try {
            executor.execute(() -> {
                try {
                    relayService.relayReadyTasks();
                } finally {
                    scanRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            scanRunning.set(false);
            throw e;
        }
    }
}
