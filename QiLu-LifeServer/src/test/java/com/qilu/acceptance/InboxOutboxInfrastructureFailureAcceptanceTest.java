package com.qilu.acceptance;

import com.qilu.config.InboxMqConfig;
import com.qilu.config.InboxOutboxProperties;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxPublishStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.annotation.Resource;
import java.nio.file.Path;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "acceptance.inbox-outbox-infrastructure", matches = "true")
class InboxOutboxInfrastructureFailureAcceptanceTest extends InboxOutboxAcceptanceSupport {

    @Resource
    private InboxOutboxProperties outboxProperties;

    @Test
    void rabbitStopStillCommitsPendingTaskAndRecoveryDeliversWithinThirtySeconds() throws Exception {
        dockerCompose("stop", "rabbitmq");
        InboxDeliveryTask task = null;
        try {
            task = sendToStudent("outbox-rabbit-recovery-" + System.nanoTime());
            Thread.sleep(500L);
            InboxDeliveryTask pending = taskMapper.selectById(task.getId());
            assertNotEquals(InboxDeliveryStatus.SUCCESS.name(), pending.getDeliveryStatus());
            assertTrue(InboxPublishStatus.PENDING.name().equals(pending.getPublishStatus())
                    || InboxPublishStatus.PUBLISHING.name().equals(pending.getPublishStatus())
                    || InboxPublishStatus.RETRY_WAIT.name().equals(pending.getPublishStatus()));
        } finally {
            dockerCompose("start", "rabbitmq");
            waitForRabbitHealthy();
        }

        assertNotNull(task);
        long startedAt = System.nanoTime();
        InboxDeliveryTask delivered = waitForDeliveryStatus(
                task.getId(), InboxDeliveryStatus.SUCCESS, 30_000L
        );
        long recoveryMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertEquals(InboxDeliveryStatus.SUCCESS.name(), delivered.getDeliveryStatus());
        assertTrue(recoveryMillis <= 30_000L);
        assertEquals(1L, userCopyCount(task));
    }

    @Test
    void mandatoryReturnKeepsWrongRoutingKeyOutOfPublishedAndSuccessStates() throws Exception {
        outboxProperties.setRoutingKey("inbox.delivery.acceptance.no-route");
        InboxDeliveryTask task = sendToStudent("outbox-return-" + System.nanoTime());
        InboxDeliveryTask returned = waitForPublishStatus(
                task.getId(), InboxPublishStatus.RETRY_WAIT, 10_000L
        );
        assertEquals(InboxPublishStatus.RETRY_WAIT.name(), returned.getPublishStatus());
        assertNotEquals(InboxDeliveryStatus.SUCCESS.name(), returned.getDeliveryStatus());
        assertTrue(returned.getLastPublishError().contains("returned unroutable"));

        outboxProperties.setRoutingKey(InboxMqConfig.INBOX_DELIVERY_ROUTING_KEY);
        InboxDeliveryTask delivered = waitForDeliveryStatus(
                task.getId(), InboxDeliveryStatus.SUCCESS, 30_000L
        );
        assertEquals(InboxDeliveryStatus.SUCCESS.name(), delivered.getDeliveryStatus());
        assertEquals(1L, userCopyCount(task));
    }

    private InboxDeliveryTask waitForPublishStatus(Long taskId, InboxPublishStatus expected,
                                                   long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        InboxDeliveryTask task = null;
        while (System.currentTimeMillis() < deadline) {
            task = taskMapper.selectById(taskId);
            if (task != null && expected.name().equals(task.getPublishStatus())) {
                return task;
            }
            Thread.sleep(100L);
        }
        return task;
    }

    private void dockerCompose(String action, String service) throws Exception {
        Path repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().getParent();
        Process process = new ProcessBuilder(
                "docker", "compose", "-f",
                repositoryRoot.resolve("deploy/acceptance/docker-compose.yml").toString(),
                action, service
        ).directory(repositoryRoot.toFile()).inheritIO().start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "docker compose command timed out");
        assertEquals(0, process.exitValue(), "docker compose command failed");
    }

    private void waitForRabbitHealthy() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            Process process = new ProcessBuilder(
                    "docker", "inspect", "--format={{.State.Health.Status}}",
                    "qilu-acceptance-rabbitmq-1"
            ).redirectErrorStream(true).start();
            String status;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                status = reader.readLine();
            }
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
                    && "healthy".equals(status)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("RabbitMQ did not become healthy within 60 seconds");
    }
}
