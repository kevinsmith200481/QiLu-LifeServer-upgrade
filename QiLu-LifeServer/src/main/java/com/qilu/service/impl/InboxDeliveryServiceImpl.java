package com.qilu.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.common.InboxTableRouter;
import com.qilu.config.InboxOutboxProperties;
import com.qilu.dto.InboxDeliveryEvent;
import com.qilu.entity.InboxDeadLetter;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.entity.InboxMessage;
import com.qilu.entity.InboxUserMessage;
import com.qilu.entity.User;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.InboxDeadLetterMapper;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.mapper.InboxMessageMapper;
import com.qilu.mapper.InboxUserMessageMapper;
import com.qilu.mapper.UserMapper;
import com.qilu.service.IInboxDeliveryService;
import com.qilu.service.IInboxRealtimeService;
import com.qilu.service.InboxOutboxBackoff;
import com.qilu.service.InboxOutboxMetrics;
import com.qilu.service.strategy.InboxMessageStrategy;
import com.qilu.service.strategy.InboxMessageStrategyFactory;
import com.qilu.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InboxDeliveryServiceImpl implements IInboxDeliveryService {

    private static final int BATCH_SIZE = 500;
    private static final int ERROR_MAX_LENGTH = 512;

    private final InboxDeliveryTaskMapper taskMapper;
    private final InboxDeadLetterMapper deadLetterMapper;
    private final InboxMessageMapper messageMapper;
    private final InboxUserMessageMapper userMessageMapper;
    private final UserMapper userMapper;
    private final InboxTableRouter tableRouter;
    private final StringRedisTemplate redisTemplate;
    private final InboxMessageStrategyFactory strategyFactory;
    private final IInboxRealtimeService realtimeService;
    private final InboxOutboxProperties properties;
    private final AcceptanceFaultInjector faultInjector;
    private final InboxOutboxMetrics metrics;

    public InboxDeliveryServiceImpl(InboxDeliveryTaskMapper taskMapper,
                                    InboxDeadLetterMapper deadLetterMapper,
                                    InboxMessageMapper messageMapper,
                                    InboxUserMessageMapper userMessageMapper,
                                    UserMapper userMapper,
                                    InboxTableRouter tableRouter,
                                    StringRedisTemplate redisTemplate,
                                    InboxMessageStrategyFactory strategyFactory,
                                    IInboxRealtimeService realtimeService,
                                    InboxOutboxProperties properties,
                                    AcceptanceFaultInjector faultInjector,
                                    InboxOutboxMetrics metrics) {
        this.taskMapper = taskMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.messageMapper = messageMapper;
        this.userMessageMapper = userMessageMapper;
        this.userMapper = userMapper;
        this.tableRouter = tableRouter;
        this.redisTemplate = redisTemplate;
        this.strategyFactory = strategyFactory;
        this.realtimeService = realtimeService;
        this.properties = properties;
        this.faultInjector = faultInjector;
        this.metrics = metrics;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consumeDelivery(InboxDeliveryEvent event) {
        InboxDeliveryTask task = taskMapper.selectById(event.getTaskId());
        if (task == null || InboxDeliveryStatus.SUCCESS.name().equals(task.getDeliveryStatus())
                || InboxDeliveryStatus.DEAD.name().equals(task.getDeliveryStatus())) {
            return;
        }
        String lockKey = RedisConstants.INBOX_CONSUME_DEDUP_KEY + task.getTaskNo();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            faultInjector.beforeInboxDelivery();
            doConsume(task);
            if (taskMapper.markDeliverySuccess(task.getId()) == 1) {
                metrics.deliverySuccess();
            }
            log.info("inbox delivery succeeded, taskNo={}, messageId={}, publishAttempt={}, "
                            + "deliveryAttempt={}, traceId={}",
                    task.getTaskNo(), task.getMessageId(), task.getPublishAttempts(),
                    task.getDeliveryAttempts(), traceId());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordDeliveryFailure(InboxDeliveryEvent event, Throwable failure) {
        InboxDeliveryTask task = taskMapper.selectById(event.getTaskId());
        if (task == null || InboxDeliveryStatus.SUCCESS.name().equals(task.getDeliveryStatus())
                || InboxDeliveryStatus.DEAD.name().equals(task.getDeliveryStatus())) {
            return;
        }
        int nextAttempt = task.getDeliveryAttempts() + 1;
        String error = safeError(failure);
        boolean retrying = nextAttempt < properties.getMaxDeliveryAttempts();
        if (retrying) {
            LocalDateTime retryTime = InboxOutboxBackoff.nextTime(nextAttempt);
            taskMapper.markDeliveryRetry(task.getId(), retryTime, error);
        } else {
            if (taskMapper.markDeliveryDead(task.getId(), error) == 1) {
                upsertDeadLetter(task, error);
            }
        }
        metrics.deliveryFailure(retrying);
        log.warn("inbox delivery state updated after failure, taskNo={}, messageId={}, publishAttempt={}, "
                        + "deliveryAttempt={}, retrying={}, traceId={}",
                task.getTaskNo(), task.getMessageId(), task.getPublishAttempts(),
                nextAttempt, retrying, traceId(), failure);
    }

    private void doConsume(InboxDeliveryTask task) {
        String messageTable = tableRouter.messageTable(task.getMonthKey());
        String userTable = tableRouter.userMessageTable(task.getMonthKey());
        InboxMessage message = messageMapper.selectById(messageTable, task.getMessageId());
        if (message == null) {
            throw new IllegalStateException("Inbox message not found");
        }
        List<Long> userIds = resolveTargetUsers(task);
        InboxMessageStrategy strategy = strategyFactory.getStrategy(message.getMessageType());
        for (int i = 0; i < userIds.size(); i += BATCH_SIZE) {
            List<Long> batch = userIds.subList(i, Math.min(i + BATCH_SIZE, userIds.size()));
            List<Long> existingUserIds = userMessageMapper.selectExistingUserIds(
                    userTable, message.getId(), batch
            );
            Set<Long> existingUserIdSet = new HashSet<>(existingUserIds);
            List<Long> newUserIds = batch.stream()
                    .filter(userId -> !existingUserIdSet.contains(userId))
                    .collect(Collectors.toList());
            List<InboxUserMessage> copies = buildUserCopies(message, newUserIds);
            if (CollUtil.isEmpty(copies)) {
                continue;
            }
            /*
             * The Redis lock serializes one task's consumers; the database
             * unique key remains the final idempotency boundary. A short row
             * count is treated as a race and rolled back before Redis counters
             * are touched, so unread counts never guess which rows were new.
             */
            int inserted = userMessageMapper.batchInsert(userTable, copies);
            if (inserted != copies.size()) {
                throw new IllegalStateException("Inbox user-copy insert count mismatch");
            }
            for (Long userId : newUserIds) {
                incrementUnread(userId, message.getMessageType(), 1);
                realtimeService.publish(strategy.renderPushPayload(message, userId));
            }
        }
    }

    private List<InboxUserMessage> buildUserCopies(InboxMessage message, List<Long> userIds) {
        List<InboxUserMessage> result = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            InboxUserMessage userMessage = new InboxUserMessage();
            userMessage.setMonthKey(message.getMonthKey());
            userMessage.setMessageId(message.getId());
            userMessage.setMessageNo(message.getMessageNo());
            userMessage.setUserId(userId);
            userMessage.setMessageType(message.getMessageType());
            result.add(userMessage);
        }
        return result;
    }

    private List<Long> resolveTargetUsers(InboxDeliveryTask task) {
        InboxTargetType targetType = InboxTargetType.of(task.getTargetType());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().select(User::getId);
        if (targetType == InboxTargetType.USER) {
            List<Long> ids = splitLongs(task.getTargetValue());
            if (ids.isEmpty()) {
                return new ArrayList<>();
            }
            wrapper.in(User::getId, ids);
        } else if (targetType == InboxTargetType.ROLE) {
            List<String> roles = splitStrings(task.getTargetValue());
            if (roles.isEmpty()) {
                return new ArrayList<>();
            }
            wrapper.in(User::getRole, roles);
        }
        return userMapper.selectList(wrapper).stream().map(User::getId).collect(Collectors.toList());
    }

    private List<Long> splitLongs(String value) {
        return splitStrings(value).stream().map(Long::valueOf).collect(Collectors.toList());
    }

    private List<String> splitStrings(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toList());
    }

    private void incrementUnread(Long userId, String messageType, long delta) {
        String key = RedisConstants.INBOX_UNREAD_HASH_KEY + userId;
        redisTemplate.opsForHash().increment(key, messageType, delta);
        redisTemplate.opsForHash().increment(key, "TOTAL", delta);
        redisTemplate.expire(key, 30, TimeUnit.DAYS);
    }

    private void upsertDeadLetter(InboxDeliveryTask task, String error) {
        InboxDeadLetter letter = new InboxDeadLetter();
        letter.setTaskNo(task.getTaskNo());
        letter.setMonthKey(task.getMonthKey());
        letter.setMessageId(task.getMessageId());
        // Only task identity is persisted; target user lists are deliberately excluded.
        letter.setPayload("taskId=" + task.getId());
        letter.setErrorMsg(error);
        deadLetterMapper.upsertByTaskNo(letter);
    }

    private String safeError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "Unknown delivery failure" : error.getClass().getSimpleName();
        }
        return message.length() <= ERROR_MAX_LENGTH ? message : message.substring(0, ERROR_MAX_LENGTH);
    }

    private String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "-" : traceId;
    }
}
