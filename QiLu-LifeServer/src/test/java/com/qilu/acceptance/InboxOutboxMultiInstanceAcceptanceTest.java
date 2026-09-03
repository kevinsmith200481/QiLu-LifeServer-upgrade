package com.qilu.acceptance;

import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxPublishStatus;
import com.qilu.enums.InboxTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "acceptance.inbox-outbox", matches = "true")
@TestPropertySource(properties = "qilu.inbox.outbox.enabled=false")
class InboxOutboxMultiInstanceAcceptanceTest extends InboxOutboxAcceptanceSupport {

    @Test
    void twoRelayOwnersClaimEachOfOneThousandTasksAtMostOnce() throws Exception {
        String prefix = "multi-relay-" + System.nanoTime() + "-";
        List<InboxDeliveryTask> tasks = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            InboxDeliveryTask task = new InboxDeliveryTask();
            task.setTaskNo(prefix + index);
            task.setMonthKey(tableRouter.currentMonthKey());
            task.setMessageId(10_000_000L + index);
            task.setTargetType(InboxTargetType.USER.getCode());
            task.setTargetValue(String.valueOf(STUDENT_ID));
            task.setPublishStatus(InboxPublishStatus.PENDING.name());
            task.setPublishAttempts(0);
            task.setDeliveryStatus(InboxDeliveryStatus.WAITING.name());
            task.setDeliveryAttempts(0);
            task.setVersion(0);
            taskMapper.insert(task);
            tasks.add(task);
        }

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> claims = new ArrayList<>();
        try {
            for (InboxDeliveryTask task : tasks) {
                claims.add(executor.submit(() -> claim(task, "relay-a", start)));
                claims.add(executor.submit(() -> claim(task, "relay-b", start)));
            }
            start.countDown();
            int claimed = 0;
            for (Future<Integer> claim : claims) {
                claimed += claim.get(30, TimeUnit.SECONDS);
            }
            assertEquals(1000, claimed);
            Long publishing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM inbox_delivery_task WHERE task_no LIKE ? "
                            + "AND publish_status = 'PUBLISHING' AND lease_owner IS NOT NULL",
                    Long.class, prefix + "%"
            );
            assertEquals(1000L, publishing);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            jdbcTemplate.update("DELETE FROM inbox_delivery_task WHERE task_no LIKE ?", prefix + "%");
        }
    }

    private int claim(InboxDeliveryTask task, String owner, CountDownLatch start) throws InterruptedException {
        start.await();
        return taskMapper.claimForPublish(
                task.getId(), 0, owner, LocalDateTime.now().plusSeconds(30)
        );
    }
}
