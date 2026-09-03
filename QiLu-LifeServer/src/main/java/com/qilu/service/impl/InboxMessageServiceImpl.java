package com.qilu.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.common.CurrentUserContext;
import com.qilu.common.InboxTableRouter;
import com.qilu.dto.InboxBatchActionRequest;
import com.qilu.dto.InboxQueryRequest;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.entity.InboxMessage;
import com.qilu.entity.InboxUserMessage;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxPublishStatus;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.mapper.InboxMessageMapper;
import com.qilu.mapper.InboxUserMessageMapper;
import com.qilu.service.IInboxMessageService;
import com.qilu.service.InboxOutboxRelayService;
import com.qilu.service.strategy.InboxMessageStrategy;
import com.qilu.service.strategy.InboxMessageStrategyFactory;
import com.qilu.utils.CreateOnlyId;
import com.qilu.utils.RedisConstants;
import com.qilu.vo.AdminInboxMessageVO;
import com.qilu.vo.InboxCursorPageVO;
import com.qilu.vo.InboxMessageVO;
import com.qilu.vo.InboxTypeCountVO;
import com.qilu.vo.InboxUnreadCountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InboxMessageServiceImpl implements IInboxMessageService {

    private static final String UNREAD_CACHE_VERSION_FIELD = "__VERSION";
    private static final String UNREAD_CACHE_VERSION = "2";

    @Resource
    private CurrentUserContext currentUserContext;

    @Resource
    private InboxTableRouter inboxTableRouter;

    @Resource
    private InboxMessageMapper inboxMessageMapper;

    @Resource
    private InboxUserMessageMapper inboxUserMessageMapper;

    @Resource
    private InboxDeliveryTaskMapper inboxDeliveryTaskMapper;

    @Resource
    private InboxOutboxRelayService inboxOutboxRelayService;

    @Resource
    private InboxMessageStrategyFactory strategyFactory;

    @Resource
    private CreateOnlyId createOnlyId;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AcceptanceFaultInjector acceptanceFaultInjector;

    @Resource
    private Executor inboxOutboxExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result send(InboxSendRequest request) {
        return doSend(request, currentUserContext.currentUserId(), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result sendInternal(InboxSendRequest request, Long senderId) {
        return doSend(request, senderId, false);
    }

    private Result doSend(InboxSendRequest request, Long senderId, boolean checkPermission) {
        if (checkPermission) {
            checkManagePermission(request.getMessageType());
        }
        InboxMessageStrategy strategy = strategyFactory.getStrategy(request.getMessageType());
        strategy.validate(request);
        String monthKey = inboxTableRouter.currentMonthKey();
        InboxMessage message = strategy.buildMessage(
                request,
                senderId,
                String.valueOf(createOnlyId.createId("inbox:message")),
                monthKey
        );
        /*
         * 设计说明：发送事务只写消息主表和 Outbox 任务。
         * 全量公告面对十万级用户时不在 HTTP 线程内展开写用户副本，避免接口超时和数据库瞬时写入峰值。
         */
        String messageTable = inboxTableRouter.messageTable(monthKey);
        inboxMessageMapper.insertMessage(messageTable, message);
        fillGeneratedMessageId(messageTable, message);
        acceptanceFaultInjector.afterInboxMessageInsert();
        InboxDeliveryTask task = buildDeliveryTask(message, request);
        inboxDeliveryTaskMapper.insert(task);
        fillGeneratedTaskId(task);
        // Acceptance-only fault point: the surrounding transaction must roll
        // back both message and delivery-task rows when this throws.
        acceptanceFaultInjector.afterDatabaseOperation();
        triggerPostCommitWork(task.getId(), message);
        return Result.ok(message.getId());
    }

    @Override
    public Result queryMyMessages(InboxQueryRequest request) {
        Long userId = currentUserContext.currentUserId();
        String monthKey = inboxTableRouter.normalizeMonthKey(request.getMonthKey());
        int limit = request.getPageSize() + 1;
        List<InboxMessageVO> records = inboxUserMessageMapper.selectCursorPage(
                inboxTableRouter.messageTable(monthKey),
                inboxTableRouter.userMessageTable(monthKey),
                userId,
                request.getMessageType(),
                request.getReadStatus(),
                request.getStarStatus(),
                request.getCursor(),
                limit
        );
        boolean hasMore = records.size() > request.getPageSize();
        if (hasMore) {
            records = records.subList(0, request.getPageSize());
        }
        hydrateReadStatusFromBitmap(userId, monthKey, records);
        Long nextCursor = records.isEmpty() ? null : records.get(records.size() - 1).getCursorId();
        return Result.ok(new InboxCursorPageVO<>(records, nextCursor, hasMore));
    }

    @Override
    public Result detail(String monthKey, Long messageId) {
        Long userId = currentUserContext.currentUserId();
        String actualMonth = inboxTableRouter.normalizeMonthKey(monthKey);
        InboxMessageVO detail = inboxMessageMapper.selectUserDetail(
                inboxTableRouter.messageTable(actualMonth),
                inboxTableRouter.userMessageTable(actualMonth),
                userId,
                messageId
        );
        if (detail == null) {
            return Result.fail("Message not found");
        }
        markRead(buildAction(actualMonth, Collections.singletonList(messageId)));
        detail.setReadStatus(1);
        return Result.ok(detail);
    }

    @Override
    public Result markRead(InboxBatchActionRequest request) {
        return updateRead(request, 1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result markAllRead(String monthKey) {
        Long userId = currentUserContext.currentUserId();
        String actualMonth = inboxTableRouter.normalizeMonthKey(monthKey);
        String userTable = inboxTableRouter.userMessageTable(actualMonth);
        List<InboxUserMessage> unreadMessages = inboxUserMessageMapper.selectUnreadUserMessages(userTable, userId);
        if (CollUtil.isEmpty(unreadMessages)) {
            rebuildUnreadCache(userId, actualMonth);
            return Result.ok(0);
        }
        /*
         * 设计说明：一键已读直接按用户维度更新本月所有未读副本，避免前端分页循环提交。
         * Redis 未读 Hash 以重建方式校准，Bitmap 逐条补位，兼顾计数准确性和后续批量读状态判断性能。
         */
        inboxUserMessageMapper.updateAllReadStatus(userTable, userId);
        for (InboxUserMessage item : unreadMessages) {
            updateReadBitmap(userId, actualMonth, item.getMessageId(), 1);
        }
        rebuildUnreadCache(userId, actualMonth);
        return Result.ok(unreadMessages.size());
    }

    @Override
    public Result markUnread(InboxBatchActionRequest request) {
        return updateRead(request, 0);
    }

    @Override
    public Result star(InboxBatchActionRequest request) {
        return updateStar(request, 1);
    }

    @Override
    public Result unstar(InboxBatchActionRequest request) {
        return updateStar(request, 0);
    }

    @Override
    public Result delete(InboxBatchActionRequest request) {
        Long userId = currentUserContext.currentUserId();
        String monthKey = inboxTableRouter.normalizeMonthKey(request.getMonthKey());
        String userTable = inboxTableRouter.userMessageTable(monthKey);
        List<InboxUserMessage> beforeList = loadExisting(userTable, userId, request.getMessageIds());
        int updated = inboxUserMessageMapper.deleteUserMessages(userTable, userId, request.getMessageIds());
        for (InboxUserMessage item : beforeList) {
            if (Integer.valueOf(0).equals(item.getReadStatus())) {
                incrementUnread(userId, item.getMessageType(), -1);
            }
        }
        return Result.ok(updated);
    }

    @Override
    public Result unreadCounts() {
        Long userId = currentUserContext.currentUserId();
        String key = RedisConstants.INBOX_UNREAD_HASH_KEY + userId;
        Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(key);
        if (cached.isEmpty() || !UNREAD_CACHE_VERSION.equals(String.valueOf(cached.get(UNREAD_CACHE_VERSION_FIELD)))) {
            rebuildUnreadCache(userId, inboxTableRouter.currentMonthKey());
            cached = stringRedisTemplate.opsForHash().entries(key);
        }
        Map<String, Long> typeCounts = new HashMap<>();
        long total = 0L;
        for (Map.Entry<Object, Object> entry : cached.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (UNREAD_CACHE_VERSION_FIELD.equals(field)) {
                continue;
            }
            Long count = Long.valueOf(String.valueOf(entry.getValue()));
            if ("TOTAL".equals(field)) {
                total = count;
            } else {
                typeCounts.put(field, count);
            }
        }
        InboxUnreadCountVO vo = new InboxUnreadCountVO();
        vo.setTotal(total);
        vo.setTypeCounts(typeCounts);
        return Result.ok(vo);
    }

    @Override
    public Result queryActiveSentMessages(String monthKey, Integer pageSize) {
        checkManagePermission(null);
        String actualMonth = inboxTableRouter.normalizeMonthKey(monthKey);
        int limit = Math.max(1, Math.min(pageSize == null ? 50 : pageSize, 100));
        /*
         * 设计说明：撤回弹窗只查询消息主表中 status=1 且未过期的数据。
         * 不关联用户副本表，避免用户侧已读/删除状态影响管理员对“已发送有效消息”的治理视图。
         */
        List<AdminInboxMessageVO> records = inboxMessageMapper.selectActiveSentMessages(
                inboxTableRouter.messageTable(actualMonth),
                currentUserContext.isManager(),
                limit
        );
        return Result.ok(records, (long) records.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result batchRevoke(InboxBatchActionRequest request) {
        checkManagePermission(null);
        String actualMonth = inboxTableRouter.normalizeMonthKey(request.getMonthKey());
        int updated = 0;
        for (Long messageId : request.getMessageIds()) {
            updated += revokeOne(actualMonth, messageId);
        }
        return Result.ok(updated);
    }

    @Override
    public Result revoke(String monthKey, Long messageId) {
        checkManagePermission(null);
        String actualMonth = inboxTableRouter.normalizeMonthKey(monthKey);
        int updated = revokeOne(actualMonth, messageId);
        return Result.ok(updated);
    }

    private int revokeOne(String monthKey, Long messageId) {
        String messageTable = inboxTableRouter.messageTable(monthKey);
        InboxMessage message = inboxMessageMapper.selectById(messageTable, messageId);
        if (message == null) {
            return 0;
        }
        checkRevocablePermission(message);
        if (!Integer.valueOf(1).equals(message.getStatus())
                || (message.getExpireTime() != null && !message.getExpireTime().isAfter(LocalDateTime.now()))) {
            return 0;
        }
        inboxMessageMapper.revoke(messageTable, messageId);
        stringRedisTemplate.delete(RedisConstants.INBOX_HOT_MESSAGE_KEY + monthKey + ":" + messageId);
        return 1;
    }

    private Result updateRead(InboxBatchActionRequest request, Integer readStatus) {
        Long userId = currentUserContext.currentUserId();
        String monthKey = inboxTableRouter.normalizeMonthKey(request.getMonthKey());
        String userTable = inboxTableRouter.userMessageTable(monthKey);
        List<InboxUserMessage> beforeList = loadExisting(userTable, userId, request.getMessageIds());
        int updated = inboxUserMessageMapper.updateReadStatus(userTable, userId, request.getMessageIds(), readStatus);
        for (InboxUserMessage item : beforeList) {
            if (!readStatus.equals(item.getReadStatus())) {
                incrementUnread(userId, item.getMessageType(), readStatus == 1 ? -1 : 1);
            }
            updateReadBitmap(userId, monthKey, item.getMessageId(), readStatus);
        }
        return Result.ok(updated);
    }

    private Result updateStar(InboxBatchActionRequest request, Integer starStatus) {
        Long userId = currentUserContext.currentUserId();
        String monthKey = inboxTableRouter.normalizeMonthKey(request.getMonthKey());
        int updated = inboxUserMessageMapper.updateStarStatus(
                inboxTableRouter.userMessageTable(monthKey),
                userId,
                request.getMessageIds(),
                starStatus
        );
        return Result.ok(updated);
    }

    private List<InboxUserMessage> loadExisting(String userTable, Long userId, List<Long> messageIds) {
        return messageIds.stream()
                .map(messageId -> inboxUserMessageMapper.selectUserMessage(userTable, userId, messageId))
                .filter(item -> item != null)
                .collect(Collectors.toList());
    }

    private void updateReadBitmap(Long userId, String monthKey, Long messageId, Integer readStatus) {
        /*
         * 设计说明：Redis Bitmap 按用户和月份分桶，offset 使用消息 ID。
         * 批量判断已读状态时只需要 GETBIT，内存占用远低于把每条已读消息存成 Set 成员。
         */
        String bitmapKey = RedisConstants.INBOX_READ_BITMAP_KEY + monthKey + ":" + userId;
        stringRedisTemplate.opsForValue().setBit(bitmapKey, messageId, Integer.valueOf(1).equals(readStatus));
        stringRedisTemplate.expire(bitmapKey, 180, TimeUnit.DAYS);
    }

    private void hydrateReadStatusFromBitmap(Long userId, String monthKey, List<InboxMessageVO> records) {
        String bitmapKey = RedisConstants.INBOX_READ_BITMAP_KEY + monthKey + ":" + userId;
        for (InboxMessageVO record : records) {
            Boolean read = stringRedisTemplate.opsForValue().getBit(bitmapKey, record.getMessageId());
            if (Boolean.TRUE.equals(read)) {
                record.setReadStatus(1);
            }
        }
    }

    private void rebuildUnreadCache(Long userId, String monthKey) {
        List<InboxTypeCountVO> counts = inboxUserMessageMapper.countUnreadGroupByType(
                inboxTableRouter.userMessageTable(monthKey),
                userId
        );
        String key = RedisConstants.INBOX_UNREAD_HASH_KEY + userId;
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForHash().put(key, UNREAD_CACHE_VERSION_FIELD, UNREAD_CACHE_VERSION);
        if (CollUtil.isEmpty(counts)) {
            stringRedisTemplate.opsForHash().put(key, "TOTAL", "0");
            stringRedisTemplate.expire(key, 30, TimeUnit.DAYS);
            return;
        }
        long total = 0L;
        for (InboxTypeCountVO count : counts) {
            stringRedisTemplate.opsForHash().put(key, count.getMessageType(), String.valueOf(count.getCount()));
            total += count.getCount();
        }
        stringRedisTemplate.opsForHash().put(key, "TOTAL", String.valueOf(total));
        stringRedisTemplate.expire(key, 30, TimeUnit.DAYS);
    }

    private void incrementUnread(Long userId, String messageType, long delta) {
        String key = RedisConstants.INBOX_UNREAD_HASH_KEY + userId;
        stringRedisTemplate.opsForHash().increment(key, messageType, delta);
        stringRedisTemplate.opsForHash().increment(key, "TOTAL", delta);
        stringRedisTemplate.expire(key, 30, TimeUnit.DAYS);
    }

    private void fillGeneratedMessageId(String messageTable, InboxMessage message) {
        if (message.getId() != null) {
            return;
        }
        InboxMessage stored = inboxMessageMapper.selectByMessageNo(messageTable, message.getMessageNo());
        if (stored == null || stored.getId() == null) {
            throw new IllegalStateException("Create inbox message failed");
        }
        message.setId(stored.getId());
        message.setCreateTime(stored.getCreateTime());
    }

    private void fillGeneratedTaskId(InboxDeliveryTask task) {
        if (task.getId() != null) {
            return;
        }
        /*
         * Defensive fallback for a JDBC driver or custom key generator that did
         * not populate the generated ID. SIMPLE execution normally fills it at insert time.
         */
        InboxDeliveryTask stored = inboxDeliveryTaskMapper.selectOne(
                new LambdaQueryWrapper<InboxDeliveryTask>().eq(InboxDeliveryTask::getTaskNo, task.getTaskNo())
        );
        if (stored == null || stored.getId() == null) {
            throw new IllegalStateException("Create inbox delivery task failed");
        }
        task.setId(stored.getId());
    }

    private void triggerPostCommitWork(Long taskId, InboxMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheHotMessageAsync(message);
            inboxOutboxRelayService.triggerImmediate(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The hot cache is an optimization only. Queries continue to
                // use MySQL as their source of truth when this best-effort write fails.
                cacheHotMessageAsync(message);
                inboxOutboxRelayService.triggerImmediate(taskId);
            }
        });
    }

    private InboxDeliveryTask buildDeliveryTask(InboxMessage message, InboxSendRequest request) {
        InboxDeliveryTask task = new InboxDeliveryTask();
        task.setTaskNo(String.valueOf(createOnlyId.createId("inbox:task")));
        task.setMonthKey(message.getMonthKey());
        task.setMessageId(message.getId());
        task.setTargetType(request.getTargetType());
        task.setTargetValue(buildTargetValue(request));
        task.setPublishStatus(InboxPublishStatus.PENDING.name());
        task.setPublishAttempts(0);
        task.setDeliveryStatus(InboxDeliveryStatus.WAITING.name());
        task.setDeliveryAttempts(0);
        task.setVersion(0);
        return task;
    }

    private String buildTargetValue(InboxSendRequest request) {
        InboxTargetType targetType = InboxTargetType.of(request.getTargetType());
        if (targetType == InboxTargetType.ALL) {
            return "";
        }
        if (targetType == InboxTargetType.USER) {
            Set<Long> ids = new LinkedHashSet<>(request.getUserIds());
            return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return request.getRoles().stream().filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(","));
    }

    private void cacheHotMessage(InboxMessage message) {
        if (!InboxMessageType.of(message.getMessageType()).isHotCache()) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.INBOX_HOT_MESSAGE_KEY + message.getMonthKey() + ":" + message.getId(),
                    objectMapper.writeValueAsString(message),
                    RedisConstants.INBOX_HOT_MESSAGE_TTL,
                    TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.warn("cache hot inbox message failed, messageId={}", message.getId(), e);
        }
    }

    private void cacheHotMessageAsync(InboxMessage message) {
        try {
            inboxOutboxExecutor.execute(() -> cacheHotMessage(message));
        } catch (RuntimeException e) {
            // Dropping this optimization is safe: every inbox query has a
            // database source-of-truth path and the Outbox row is unaffected.
            log.warn("schedule hot inbox cache failed, messageId={}", message.getId(), e);
        }
    }

    private InboxBatchActionRequest buildAction(String monthKey, List<Long> messageIds) {
        InboxBatchActionRequest request = new InboxBatchActionRequest();
        request.setMonthKey(monthKey);
        request.setMessageIds(messageIds);
        return request;
    }

    private void checkManagePermission(String messageType) {
        if (currentUserContext.isAdmin()) {
            return;
        }
        if (currentUserContext.isManager()
                && (messageType == null || !InboxMessageType.EXCEPTION_ALERT.getCode().equals(messageType))) {
            return;
        }
        throw new IllegalStateException("No permission to manage inbox message");
    }

    private void checkRevocablePermission(InboxMessage message) {
        if (currentUserContext.isAdmin()) {
            return;
        }
        if (currentUserContext.isManager()
                && !InboxMessageType.EXCEPTION_ALERT.getCode().equals(message.getMessageType())) {
            return;
        }
        throw new IllegalStateException("No permission to revoke inbox message");
    }
}
