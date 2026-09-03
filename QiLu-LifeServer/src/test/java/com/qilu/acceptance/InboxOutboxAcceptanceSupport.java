package com.qilu.acceptance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qilu.common.InboxTableRouter;
import com.qilu.dto.InboxDeliveryEvent;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.InboxDeadLetterMapper;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.service.IInboxDeliveryService;
import com.qilu.service.IInboxMessageService;
import com.qilu.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "qilu.inbox.outbox.scan-delay-millis=100")
@ActiveProfiles("acceptance")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class InboxOutboxAcceptanceSupport {

    protected static final long STUDENT_ID = 990001L;
    protected static final long ADMIN_ID = 990003L;

    @Resource
    protected IInboxMessageService messageService;

    @Resource
    protected IInboxDeliveryService deliveryService;

    @Resource
    protected InboxDeliveryTaskMapper taskMapper;

    @Resource
    protected InboxDeadLetterMapper deadLetterMapper;

    @Resource
    protected InboxTableRouter tableRouter;

    @Resource
    protected JdbcTemplate jdbcTemplate;

    @Resource
    protected StringRedisTemplate stringRedisTemplate;

    private final List<InboxDeliveryTask> createdTasks = new ArrayList<>();

    protected InboxDeliveryTask sendToStudent(String title) {
        InboxSendRequest request = new InboxSendRequest();
        request.setMessageType(InboxMessageType.SYSTEM_NOTICE.getCode());
        request.setTargetType(InboxTargetType.USER.getCode());
        request.setTitle(title);
        request.setContent("Stage 2 Outbox acceptance content");
        request.setSummary("Stage 2 Outbox acceptance");
        request.setUserIds(Collections.singletonList(STUDENT_ID));
        Result result = messageService.sendInternal(request, ADMIN_ID);
        Long messageId = ((Number) result.getData()).longValue();
        InboxDeliveryTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<InboxDeliveryTask>()
                        .eq(InboxDeliveryTask::getMessageId, messageId)
                        .orderByDesc(InboxDeliveryTask::getId)
                        .last("LIMIT 1")
        );
        assertNotNull(task, "send transaction must persist one Outbox task");
        createdTasks.add(task);
        return task;
    }

    protected InboxDeliveryEvent event(InboxDeliveryTask task) {
        InboxDeliveryEvent event = new InboxDeliveryEvent();
        event.setTaskId(task.getId());
        event.setTaskNo(task.getTaskNo());
        event.setMonthKey(task.getMonthKey());
        event.setMessageId(task.getMessageId());
        return event;
    }

    protected InboxDeliveryTask waitForDeliveryStatus(Long taskId, InboxDeliveryStatus expected,
                                                      long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        InboxDeliveryTask task = null;
        while (System.currentTimeMillis() < deadline) {
            task = taskMapper.selectById(taskId);
            if (task != null && expected.name().equals(task.getDeliveryStatus())) {
                return task;
            }
            Thread.sleep(100L);
        }
        return task;
    }

    protected long userCopyCount(InboxDeliveryTask task) {
        String table = tableRouter.userMessageTable(task.getMonthKey());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE message_id = ? AND user_id = ?",
                Long.class, task.getMessageId(), STUDENT_ID
        );
        return count == null ? 0L : count;
    }

    protected long unreadTotal(long userId) {
        Object value = stringRedisTemplate.opsForHash().get(
                RedisConstants.INBOX_UNREAD_HASH_KEY + userId, "TOTAL"
        );
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    @AfterEach
    void cleanupCreatedOutboxData() {
        for (InboxDeliveryTask task : createdTasks) {
            jdbcTemplate.update("DELETE FROM inbox_dead_letter WHERE task_no = ?", task.getTaskNo());
            jdbcTemplate.update("DELETE FROM " + tableRouter.userMessageTable(task.getMonthKey())
                    + " WHERE message_id = ?", task.getMessageId());
            jdbcTemplate.update("DELETE FROM " + tableRouter.messageTable(task.getMonthKey())
                    + " WHERE id = ?", task.getMessageId());
            taskMapper.deleteById(task.getId());
            stringRedisTemplate.delete(RedisConstants.INBOX_HOT_MESSAGE_KEY
                    + task.getMonthKey() + ":" + task.getMessageId());
        }
        createdTasks.clear();
        stringRedisTemplate.delete(RedisConstants.INBOX_UNREAD_HASH_KEY + STUDENT_ID);
    }
}
