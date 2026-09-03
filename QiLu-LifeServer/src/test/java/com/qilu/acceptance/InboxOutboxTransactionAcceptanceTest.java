package com.qilu.acceptance;

import com.qilu.dto.InboxSendRequest;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "acceptance.inbox-outbox", matches = "true")
@TestPropertySource(properties = {
        "qilu.inbox.outbox.enabled=false",
        "qilu.acceptance.fault.enabled=true",
        "qilu.acceptance.fault.inbox-after-message-insert=true"
})
class InboxOutboxTransactionAcceptanceTest extends InboxOutboxAcceptanceSupport {

    @Test
    void messageInsertFaultRollsBackMessageTaskAndHotCache() {
        String title = "outbox-rollback-" + System.nanoTime();
        long taskCountBefore = taskMapper.selectCount(null);
        Set<String> cacheKeysBefore = stringRedisTemplate.keys("inbox:hot:*");

        InboxSendRequest request = new InboxSendRequest();
        request.setMessageType(InboxMessageType.SYSTEM_NOTICE.getCode());
        request.setTargetType(InboxTargetType.USER.getCode());
        request.setTitle(title);
        request.setContent("rollback acceptance");
        request.setUserIds(Collections.singletonList(STUDENT_ID));

        assertThrows(RuntimeException.class, () -> messageService.sendInternal(request, ADMIN_ID));

        Long messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + tableRouter.messageTable(tableRouter.currentMonthKey())
                        + " WHERE title = ?", Long.class, title
        );
        Set<String> cacheKeysAfter = stringRedisTemplate.keys("inbox:hot:*");
        assertEquals(0L, messageCount);
        assertEquals(taskCountBefore, taskMapper.selectCount(null));
        assertEquals(cacheKeysBefore, cacheKeysAfter, "rollback must not leave an orphan hot cache");
    }
}
