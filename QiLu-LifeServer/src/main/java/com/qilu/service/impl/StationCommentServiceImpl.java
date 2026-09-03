package com.qilu.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.config.StationCommentMqConfig;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.dto.StationCommentCreateRequest;
import com.qilu.dto.StationCommentDTO;
import com.qilu.dto.StationCommentDeleteMessage;
import com.qilu.dto.StationCommentPageDTO;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AdminCommentView;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.StationComment;
import com.qilu.entity.StationCommentCleanupTask;
import com.qilu.entity.User;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.AdminCommentViewMapper;
import com.qilu.mapper.ServicePointMapper;
import com.qilu.mapper.StationCommentCleanupTaskMapper;
import com.qilu.mapper.StationCommentMapper;
import com.qilu.mapper.UserMapper;
import com.qilu.service.IInboxMessageService;
import com.qilu.service.IStationCommentService;
import com.qilu.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StationCommentServiceImpl extends ServiceImpl<StationCommentMapper, StationComment> implements IStationCommentService {

    private static final int NORMAL = 1;
    private static final int DELETED = -1;
    private static final int CLEANUP_PENDING = 0;
    private static final int CLEANUP_DONE = 1;
    private static final int CLEANUP_FAILED = -1;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Long ROOT_PARENT_ID = 0L;
    private static final String SORT_OLDEST = "oldest";
    private static final String FLOOR_COUNTER_KEY = "station:comment:floor:";
    private static final String HOT_ZSET_KEY = "station:comment:hot:";
    private static final String COMMENT_LIKED_KEY = "station:comment:liked:";
    private static final String DELETE_IDEMPOTENT_KEY = "mq:station-comment-delete:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AdminCommentViewMapper adminCommentViewMapper;

    @Resource
    private StationCommentCleanupTaskMapper cleanupTaskMapper;

    @Resource
    private ServicePointMapper servicePointMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private IInboxMessageService inboxMessageService;

    @Override
    @Transactional
    public Result createComment(Long stationId, StationCommentCreateRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        ServicePoint servicePoint = servicePointMapper.selectById(stationId);
        if (servicePoint == null) {
            return Result.fail("service point not found");
        }

        Long parentId = request.getParentId() == null ? ROOT_PARENT_ID : request.getParentId();
        StationComment parent = null;
        StationComment replyTo = null;
        Integer floorNo = null;
        Long rootId = ROOT_PARENT_ID;
        if (ROOT_PARENT_ID.equals(parentId)) {
            // Redis INCR gives the floor an atomic monotonic number under high concurrency.
            floorNo = Objects.requireNonNull(stringRedisTemplate.opsForValue()
                    .increment(FLOOR_COUNTER_KEY + stationId)).intValue();
        } else {
            parent = getNormalComment(parentId, stationId);
            if (parent == null || !ROOT_PARENT_ID.equals(parent.getParentId())) {
                return Result.fail("parent floor not found");
            }
            rootId = parent.getId();
            replyTo = request.getReplyToCommentId() == null ? parent : getNormalComment(request.getReplyToCommentId(), stationId);
            if (replyTo == null || !rootId.equals(resolveRootId(replyTo))) {
                return Result.fail("reply comment not found");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        StationComment comment = new StationComment()
                .setStationId(stationId)
                .setParentId(parentId)
                .setRootId(rootId)
                .setReplyToCommentId(replyTo == null ? null : replyTo.getId())
                .setReplyToUserId(replyTo == null ? null : replyTo.getUserId())
                .setUserId(user.getId())
                .setUserType(resolveUserType(user))
                .setFloorNo(floorNo)
                .setContent(request.getContent())
                .setLikeCount(0)
                .setReplyCount(0)
                .setStatus(NORMAL)
                .setCreateTime(now)
                .setUpdateTime(now);
        save(comment);

        if (parent != null) {
            incrementReplyCount(parent.getId());
            refreshHotScore(parent.getId());
        }
        syncAdminShadow(comment, replyTo);
        refreshHotScore(comment.getId());
        scheduleSiteReplyNotification(comment, replyTo, servicePoint, user);
        User dbUser = userMapper.selectById(user.getId());
        return Result.ok(toDto(comment, dbUser, replyTo, Collections.emptyMap()));
    }

    @Override
    public Result queryByCursor(Long stationId, String sort, Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        boolean oldest = SORT_OLDEST.equalsIgnoreCase(sort);
        LambdaQueryWrapper<StationComment> wrapper = new LambdaQueryWrapper<StationComment>()
                .eq(StationComment::getStationId, stationId)
                .eq(StationComment::getParentId, ROOT_PARENT_ID)
                .eq(StationComment::getStatus, NORMAL)
                .lt(!oldest && cursor != null, StationComment::getId, cursor)
                .gt(oldest && cursor != null, StationComment::getId, cursor)
                .last("limit " + (pageSize + 1));
        if (oldest) {
            wrapper.orderByAsc(StationComment::getId);
        } else {
            wrapper.orderByDesc(StationComment::getId);
        }
        return Result.ok(buildCursorPage(baseMapper.selectList(wrapper), pageSize, null, null));
    }

    @Override
    public Result queryReplies(Long stationId, Long rootCommentId, Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        LambdaQueryWrapper<StationComment> wrapper = new LambdaQueryWrapper<StationComment>()
                .eq(StationComment::getStationId, stationId)
                .eq(StationComment::getRootId, rootCommentId)
                .ne(StationComment::getParentId, ROOT_PARENT_ID)
                .eq(StationComment::getStatus, NORMAL)
                .gt(cursor != null, StationComment::getId, cursor)
                .orderByAsc(StationComment::getId)
                .last("limit " + (pageSize + 1));
        return Result.ok(buildCursorPage(baseMapper.selectList(wrapper), pageSize, null, null));
    }

    @Override
    public Result queryHot(Long stationId, Double cursorScore, Integer offset, Integer size) {
        int pageSize = normalizeSize(size);
        int pageOffset = offset == null ? 0 : Math.max(offset, 0);
        // Use live DB counters so "hot" is cumulative popularity for the whole floor thread.
        List<StationComment> comments = baseMapper.selectHotRootComments(stationId, pageOffset, pageSize + 1);
        StationCommentPageDTO page = buildCursorPage(comments, pageSize, null, pageOffset);
        page.setOffset(pageOffset + page.getList().size());
        return Result.ok(page);
    }

    @Override
    public Result queryAdminComments(Long stationId, Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        List<AdminCommentView> rows = adminCommentViewMapper.selectList(new LambdaQueryWrapper<AdminCommentView>()
                .eq(AdminCommentView::getStationId, stationId)
                .eq(AdminCommentView::getStatus, NORMAL)
                .lt(cursor != null, AdminCommentView::getCommentId, cursor)
                .orderByDesc(AdminCommentView::getCommentId)
                .last("limit " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        Set<Long> userIds = rows.stream().map(AdminCommentView::getAdminId).collect(Collectors.toSet());
        Map<Long, User> userMap = loadUserMap(userIds);
        List<StationCommentDTO> list = rows.stream().map(row -> toDto(row, userMap.get(row.getAdminId()))).collect(Collectors.toList());
        StationCommentPageDTO page = new StationCommentPageDTO();
        page.setList(list);
        page.setHasMore(hasMore);
        page.setOffset(0);
        if (!rows.isEmpty()) {
            page.setNextCursor(rows.get(rows.size() - 1).getCommentId());
        }
        return Result.ok(page);
    }

    @Override
    @Transactional
    public Result likeComment(Long stationId, Long commentId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        StationComment comment = getNormalComment(commentId, stationId);
        if (comment == null) {
            return Result.fail("comment not found");
        }
        String key = COMMENT_LIKED_KEY + commentId;
        String userId = user.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);
        boolean liked;
        if (score == null) {
            update(new LambdaUpdateWrapper<StationComment>()
                    .eq(StationComment::getId, commentId)
                    .setSql("like_count = like_count + 1"));
            stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            liked = true;
        } else {
            update(new LambdaUpdateWrapper<StationComment>()
                    .eq(StationComment::getId, commentId)
                    .setSql("like_count = GREATEST(like_count - 1, 0)"));
            stringRedisTemplate.opsForZSet().remove(key, userId);
            liked = false;
        }
        Long hotCommentId = isFloorComment(comment) ? comment.getId() : comment.getRootId();
        refreshHotScore(hotCommentId);
        StationComment fresh = getById(commentId);
        StationCommentDTO dto = toDto(fresh, userMapper.selectById(fresh.getUserId()), null, Collections.emptyMap());
        dto.setLiked(liked);
        return Result.ok(dto);
    }

    @Override
    @Transactional
    public Result deleteComment(Long stationId, Long commentId) {
        UserDTO user = UserHolder.getUser();
        if (!canManageStation(user, stationId)) {
            return Result.fail("No permission to delete comment");
        }
        StationComment comment = getNormalComment(commentId, stationId);
        if (comment == null) {
            return Result.fail("comment not found");
        }

        update(new LambdaUpdateWrapper<StationComment>()
                .eq(StationComment::getId, commentId)
                .eq(StationComment::getStatus, NORMAL)
                .set(StationComment::getStatus, DELETED)
                .set(StationComment::getDeletedBy, user.getId())
                .set(StationComment::getDeleteTime, LocalDateTime.now()));
        adminCommentViewMapper.update(null, new LambdaUpdateWrapper<AdminCommentView>()
                .eq(AdminCommentView::getCommentId, commentId)
                .set(AdminCommentView::getStatus, DELETED));
        stringRedisTemplate.opsForZSet().remove(HOT_ZSET_KEY + stationId, commentId.toString());

        if (isFloorComment(comment)) {
            StationCommentDeleteMessage message = new StationCommentDeleteMessage(
                    IdUtil.fastSimpleUUID(), stationId, commentId, user.getId());
            StationCommentCleanupTask task = saveCleanupTask(message, null, null);
            sendCleanupMessageOrFallback(message, task);
        } else {
            decrementReplyCount(comment.getRootId());
            refreshHotScore(comment.getRootId());
        }
        return Result.ok();
    }

    @Override
    public void cleanupDeletedFloor(StationCommentDeleteMessage message) {
        String idempotentKey = DELETE_IDEMPOTENT_KEY + message.getMessageId();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(idempotentKey))) {
            return;
        }
        // Soft delete children in short batches to avoid a long transaction locking a hot comment tree.
        Long cursor = 0L;
        while (true) {
            List<StationComment> children = baseMapper.selectList(new LambdaQueryWrapper<StationComment>()
                    .eq(StationComment::getStationId, message.getStationId())
                    .eq(StationComment::getRootId, message.getRootCommentId())
                    .eq(StationComment::getStatus, NORMAL)
                    .gt(StationComment::getId, cursor)
                    .orderByAsc(StationComment::getId)
                    .last("limit 200"));
            if (children.isEmpty()) {
                break;
            }
            List<Long> ids = children.stream().map(StationComment::getId).collect(Collectors.toList());
            update(new LambdaUpdateWrapper<StationComment>()
                    .in(StationComment::getId, ids)
                    .set(StationComment::getStatus, DELETED)
                    .set(StationComment::getDeletedBy, message.getDeletedBy())
                    .set(StationComment::getDeleteTime, LocalDateTime.now()));
            adminCommentViewMapper.update(null, new LambdaUpdateWrapper<AdminCommentView>()
                    .in(AdminCommentView::getCommentId, ids)
                    .set(AdminCommentView::getStatus, DELETED));
            stringRedisTemplate.opsForZSet().remove(HOT_ZSET_KEY + message.getStationId(),
                    ids.stream().map(String::valueOf).toArray());
            cursor = ids.get(ids.size() - 1);
        }
        cleanupTaskMapper.update(null, new LambdaUpdateWrapper<StationCommentCleanupTask>()
                .eq(StationCommentCleanupTask::getMessageId, message.getMessageId())
                .set(StationCommentCleanupTask::getStatus, CLEANUP_DONE)
                .set(StationCommentCleanupTask::getErrorMsg, null));
        stringRedisTemplate.opsForValue().set(idempotentKey, "1", Duration.ofDays(1));
    }

    @Override
    public void rebuildHotComments() {
        List<StationComment> comments = baseMapper.selectList(new LambdaQueryWrapper<StationComment>()
                .eq(StationComment::getStatus, NORMAL)
                .eq(StationComment::getParentId, ROOT_PARENT_ID)
                .ge(StationComment::getCreateTime, LocalDateTime.now().minusDays(30))
                .last("limit 1000"));
        for (StationComment comment : comments) {
            refreshHotScore(comment);
        }
    }

    @Override
    public void rebuildAdminCommentView() {
        List<StationComment> comments = baseMapper.selectList(new LambdaQueryWrapper<StationComment>()
                .eq(StationComment::getStatus, NORMAL)
                .in(StationComment::getUserType, "admin", "manager")
                .ge(StationComment::getUpdateTime, LocalDateTime.now().minusDays(1))
                .last("limit 500"));
        for (StationComment comment : comments) {
            Long count = adminCommentViewMapper.selectCount(new LambdaQueryWrapper<AdminCommentView>()
                    .eq(AdminCommentView::getCommentId, comment.getId()));
            if (count != null && count > 0) {
                continue;
            }
            StationComment replyTo = comment.getReplyToCommentId() == null ? null : getById(comment.getReplyToCommentId());
            syncAdminShadow(comment, replyTo);
        }
    }

    @Override
    public void retryCleanupTasks() {
        List<StationCommentCleanupTask> tasks = cleanupTaskMapper.selectList(new LambdaQueryWrapper<StationCommentCleanupTask>()
                .eq(StationCommentCleanupTask::getStatus, CLEANUP_PENDING)
                .lt(StationCommentCleanupTask::getRetryCount, 10)
                .orderByAsc(StationCommentCleanupTask::getId)
                .last("limit 50"));
        for (StationCommentCleanupTask task : tasks) {
            StationCommentDeleteMessage message = new StationCommentDeleteMessage(
                    task.getMessageId(), task.getStationId(), task.getRootCommentId(), task.getDeletedBy());
            sendCleanupMessageOrFallback(message, task);
        }
    }

    private StationCommentPageDTO buildCursorPage(List<StationComment> comments, int pageSize, Double score, Integer offset) {
        boolean hasMore = comments.size() > pageSize;
        if (hasMore) {
            comments = comments.subList(0, pageSize);
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> replyIds = new HashSet<>();
        for (StationComment comment : comments) {
            userIds.add(comment.getUserId());
            if (comment.getReplyToUserId() != null) {
                userIds.add(comment.getReplyToUserId());
            }
            if (comment.getReplyToCommentId() != null) {
                replyIds.add(comment.getReplyToCommentId());
            }
        }
        Map<Long, User> userMap = loadUserMap(userIds);
        Map<Long, StationComment> replyMap = loadCommentMap(replyIds);
        List<StationCommentDTO> list = comments.stream()
                .map(comment -> toDto(comment, userMap.get(comment.getUserId()),
                        replyMap.get(comment.getReplyToCommentId()), userMap))
                .collect(Collectors.toList());
        StationCommentPageDTO page = new StationCommentPageDTO();
        page.setList(list);
        page.setHasMore(hasMore);
        page.setNextCursor(comments.isEmpty() ? null : comments.get(comments.size() - 1).getId());
        page.setNextCursorScore(score);
        page.setOffset(offset == null ? 0 : offset);
        return page;
    }

    private StationCommentDTO toDto(StationComment comment, User user, StationComment replyTo, Map<Long, User> userMap) {
        StationCommentDTO dto = new StationCommentDTO();
        dto.setId(comment.getId());
        dto.setStationId(comment.getStationId());
        dto.setParentId(comment.getParentId());
        dto.setRootId(comment.getRootId());
        dto.setFloorNo(comment.getFloorNo());
        dto.setUserId(comment.getUserId());
        dto.setUserName(user == null ? null : user.getNickName());
        dto.setUserIcon(user == null ? null : user.getIcon());
        dto.setUserType(comment.getUserType());
        dto.setContent(comment.getContent());
        dto.setLikeCount(comment.getLikeCount());
        dto.setLiked(isLikedByCurrentUser(comment.getId()));
        dto.setReplyCount(comment.getReplyCount());
        dto.setReplyToCommentId(comment.getReplyToCommentId());
        dto.setReplyToUserId(comment.getReplyToUserId());
        User replyUser = comment.getReplyToUserId() == null ? null : userMap.get(comment.getReplyToUserId());
        dto.setReplyToUserName(replyUser == null ? null : replyUser.getNickName());
        dto.setReplyToContent(replyTo == null ? null : replyTo.getContent());
        dto.setCreateTime(comment.getCreateTime());
        return dto;
    }

    private StationCommentDTO toDto(AdminCommentView row, User user) {
        StationCommentDTO dto = new StationCommentDTO();
        dto.setId(row.getCommentId());
        dto.setStationId(row.getStationId());
        dto.setParentId(row.getParentId());
        dto.setRootId(row.getRootId());
        dto.setFloorNo(row.getFloorNo());
        dto.setUserId(row.getAdminId());
        dto.setUserName(user == null ? null : user.getNickName());
        dto.setUserIcon(user == null ? null : user.getIcon());
        dto.setUserType(row.getAdminType());
        dto.setContent(row.getContent());
        dto.setLikeCount(0);
        dto.setLiked(isLikedByCurrentUser(row.getCommentId()));
        dto.setReplyToCommentId(row.getReplyToCommentId());
        dto.setReplyToUserId(row.getReplyToUserId());
        dto.setReplyToUserName(row.getReplyToUserName());
        dto.setReplyToContent(row.getReplyToContent());
        dto.setCreateTime(row.getCreateTime());
        return dto;
    }

    private void scheduleSiteReplyNotification(StationComment comment, StationComment replyTo,
                                               ServicePoint servicePoint, UserDTO sender) {
        if (replyTo == null || replyTo.getUserId() == null || replyTo.getUserId().equals(comment.getUserId())) {
            return;
        }
        Runnable task = () -> sendSiteReplyNotification(comment, replyTo, servicePoint, sender);
        /*
         * 设计说明：回复消息需要等留言事务提交后再进入收件箱 MQ 投递链路。
         * 否则消费者可能先于留言事务提交读取投递任务，造成偶发查不到任务或业务详情不一致。
         */
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private void sendSiteReplyNotification(StationComment comment, StationComment replyTo,
                                           ServicePoint servicePoint, UserDTO sender) {
        try {
            String senderName = StrUtil.blankToDefault(sender.getNickName(), "\u7528\u6237 " + sender.getId());
            String summary = senderName + " \u56de\u590d\u4e86\u4f60\u7684\u7559\u8a00";
            InboxSendRequest request = new InboxSendRequest();
            request.setMessageType(InboxMessageType.SITE_REPLY.getCode());
            request.setTargetType(InboxTargetType.USER.getCode());
            request.setUserIds(Collections.singletonList(replyTo.getUserId()));
            request.setTitle("\u7559\u8a00\u6536\u5230\u65b0\u56de\u590d");
            request.setSummary(summary);
            request.setContent(senderName + "\uff1a" + comment.getContent());
            request.setBusinessType("STATION_COMMENT");
            request.setBusinessId(comment.getId());
            request.setExpireTime(LocalDateTime.now().plusDays(30));
            inboxMessageService.sendInternal(request, comment.getUserId());
        } catch (RuntimeException e) {
            log.warn("send site reply inbox message failed, commentId={}, replyToUserId={}",
                    comment.getId(), replyTo.getUserId(), e);
        }
    }

    private void syncAdminShadow(StationComment comment, StationComment replyTo) {
        if (!isAdminType(comment.getUserType())) {
            return;
        }
        User replyUser = replyTo == null ? null : userMapper.selectById(replyTo.getUserId());
        AdminCommentView view = new AdminCommentView()
                .setCommentId(comment.getId())
                .setStationId(comment.getStationId())
                .setParentId(comment.getParentId())
                .setRootId(comment.getRootId())
                .setFloorNo(comment.getFloorNo())
                .setAdminId(comment.getUserId())
                .setAdminType(comment.getUserType())
                .setContent(comment.getContent())
                .setReplyToCommentId(comment.getReplyToCommentId())
                .setReplyToUserId(comment.getReplyToUserId())
                .setReplyToUserName(replyUser == null ? null : replyUser.getNickName())
                .setReplyToContent(replyTo == null ? null : replyTo.getContent())
                .setStatus(comment.getStatus());
        adminCommentViewMapper.insert(view);
    }

    private void refreshHotScore(Long commentId) {
        StationComment comment = getById(commentId);
        if (comment != null) {
            refreshHotScore(comment);
        }
    }

    private void refreshHotScore(StationComment comment) {
        if (!Integer.valueOf(NORMAL).equals(comment.getStatus()) || !ROOT_PARENT_ID.equals(comment.getParentId())) {
            return;
        }
        stringRedisTemplate.opsForZSet().add(HOT_ZSET_KEY + comment.getStationId(),
                comment.getId().toString(), calculateHotScore(comment));
    }

    private double calculateHotScore(StationComment comment) {
        // Legacy Redis warm-up score; queryHot uses StationCommentMapper#selectHotRootComments as the source of truth.
        return comment.getLikeCount() + comment.getReplyCount() * 2D;
    }

    private StationComment getNormalComment(Long id, Long stationId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<StationComment>()
                .eq(StationComment::getId, id)
                .eq(StationComment::getStationId, stationId)
                .eq(StationComment::getStatus, NORMAL));
    }

    private Long resolveRootId(StationComment comment) {
        return ROOT_PARENT_ID.equals(comment.getParentId()) ? comment.getId() : comment.getRootId();
    }

    private boolean isFloorComment(StationComment comment) {
        return comment.getFloorNo() != null || ROOT_PARENT_ID.equals(comment.getParentId());
    }

    private void incrementReplyCount(Long commentId) {
        update(new LambdaUpdateWrapper<StationComment>()
                .eq(StationComment::getId, commentId)
                .setSql("reply_count = reply_count + 1"));
    }

    private void decrementReplyCount(Long commentId) {
        update(new LambdaUpdateWrapper<StationComment>()
                .eq(StationComment::getId, commentId)
                .setSql("reply_count = GREATEST(reply_count - 1, 0)"));
    }

    private boolean isLikedByCurrentUser(Long commentId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return false;
        }
        Double score = stringRedisTemplate.opsForZSet().score(COMMENT_LIKED_KEY + commentId, user.getId().toString());
        return score != null;
    }

    private List<StationComment> queryNormalByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        return baseMapper.selectList(new LambdaQueryWrapper<StationComment>()
                .in(StationComment::getId, ids)
                .eq(StationComment::getStatus, NORMAL));
    }

    private Map<Long, StationComment> loadCommentMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return baseMapper.selectList(new LambdaQueryWrapper<StationComment>().in(StationComment::getId, ids))
                .stream().collect(Collectors.toMap(StationComment::getId, item -> item));
    }

    private Map<Long, User> loadUserMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(User::getId, item -> item));
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String resolveUserType(UserDTO user) {
        return StrUtil.isBlank(user.getRole()) ? "student" : user.getRole();
    }

    private boolean isAdminType(String userType) {
        return "admin".equals(userType) || "manager".equals(userType);
    }

    private boolean canManageStation(UserDTO user, Long stationId) {
        if (user == null) {
            return false;
        }
        if ("admin".equals(user.getRole())) {
            return true;
        }
        if (!"manager".equals(user.getRole())) {
            return false;
        }
        ServicePoint servicePoint = servicePointMapper.selectById(stationId);
        return servicePoint != null && user.getId().equals(servicePoint.getManagerId());
    }

    private void sendCleanupMessageOrFallback(StationCommentDeleteMessage message, StationCommentCleanupTask oldTask) {
        try {
            Message rabbitMessage = MessageBuilder
                    .withBody(objectMapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build();
            rabbitTemplate.send(StationCommentMqConfig.COMMENT_EXCHANGE,
                    StationCommentMqConfig.COMMENT_DELETE_ROUTING_KEY, rabbitMessage);
            if (oldTask != null) {
                cleanupTaskMapper.update(null, new LambdaUpdateWrapper<StationCommentCleanupTask>()
                        .eq(StationCommentCleanupTask::getId, oldTask.getId())
                        .set(StationCommentCleanupTask::getRetryCount, oldTask.getRetryCount() + 1)
                        .set(StationCommentCleanupTask::getErrorMsg, null));
            }
        } catch (AmqpException | JsonProcessingException e) {
            saveCleanupTask(message, e.getMessage(), oldTask);
        }
    }

    private StationCommentCleanupTask saveCleanupTask(StationCommentDeleteMessage message, String errorMsg, StationCommentCleanupTask oldTask) {
        if (oldTask == null) {
            StationCommentCleanupTask task = new StationCommentCleanupTask()
                    .setMessageId(message.getMessageId())
                    .setStationId(message.getStationId())
                    .setRootCommentId(message.getRootCommentId())
                    .setDeletedBy(message.getDeletedBy())
                    .setStatus(CLEANUP_PENDING)
                    .setRetryCount(0)
                    .setErrorMsg(errorMsg);
            cleanupTaskMapper.insert(task);
            return task;
        }
        cleanupTaskMapper.update(null, new LambdaUpdateWrapper<StationCommentCleanupTask>()
                .eq(StationCommentCleanupTask::getId, oldTask.getId())
                .set(StationCommentCleanupTask::getStatus,
                        oldTask.getRetryCount() + 1 >= 10 ? CLEANUP_FAILED : CLEANUP_PENDING)
                .set(StationCommentCleanupTask::getRetryCount, oldTask.getRetryCount() + 1)
                .set(StationCommentCleanupTask::getErrorMsg, errorMsg));
        return oldTask;
    }

}
