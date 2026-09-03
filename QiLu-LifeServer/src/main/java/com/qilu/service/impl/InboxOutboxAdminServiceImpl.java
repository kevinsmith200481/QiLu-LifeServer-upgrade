package com.qilu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qilu.common.CurrentUserContext;
import com.qilu.dto.InboxOutboxTaskQuery;
import com.qilu.dto.Result;
import com.qilu.entity.InboxDeliveryTask;
import com.qilu.enums.InboxDeliveryStatus;
import com.qilu.enums.InboxPublishStatus;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.service.IInboxOutboxAdminService;
import com.qilu.service.InboxOutboxRelayService;
import com.qilu.vo.InboxOutboxTaskVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class InboxOutboxAdminServiceImpl implements IInboxOutboxAdminService {

    private final CurrentUserContext currentUserContext;
    private final InboxDeliveryTaskMapper taskMapper;
    private final InboxOutboxRelayService relayService;

    public InboxOutboxAdminServiceImpl(CurrentUserContext currentUserContext,
                                       InboxDeliveryTaskMapper taskMapper,
                                       InboxOutboxRelayService relayService) {
        this.currentUserContext = currentUserContext;
        this.taskMapper = taskMapper;
        this.relayService = relayService;
    }

    @Override
    public Result pageTasks(InboxOutboxTaskQuery query) {
        checkAdmin();
        String publishStatus = normalizePublishStatus(query.getPublishStatus());
        String deliveryStatus = normalizeDeliveryStatus(query.getDeliveryStatus());
        LambdaQueryWrapper<InboxDeliveryTask> wrapper = new LambdaQueryWrapper<InboxDeliveryTask>()
                .eq(publishStatus != null, InboxDeliveryTask::getPublishStatus, publishStatus)
                .eq(deliveryStatus != null, InboxDeliveryTask::getDeliveryStatus, deliveryStatus)
                .orderByDesc(InboxDeliveryTask::getId);
        Page<InboxDeliveryTask> page = taskMapper.selectPage(
                new Page<>(query.getCurrent(), query.getPageSize()), wrapper
        );
        List<InboxOutboxTaskVO> records = page.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    @Override
    public Result retryDeadTask(Long taskId) {
        checkAdmin();
        InboxDeliveryTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return Result.fail("Inbox Outbox task not found");
        }
        int updated = taskMapper.resetDeadForManualRetry(taskId);
        if (updated != 1) {
            return Result.fail("Only DEAD task can be retried manually");
        }
        relayService.triggerImmediate(taskId);
        return Result.ok(taskId);
    }

    private String normalizePublishStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return InboxPublishStatus.valueOf(status.trim().toUpperCase()).name();
    }

    private String normalizeDeliveryStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return InboxDeliveryStatus.valueOf(status.trim().toUpperCase()).name();
    }

    private InboxOutboxTaskVO toVo(InboxDeliveryTask task) {
        InboxOutboxTaskVO vo = new InboxOutboxTaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setMonthKey(task.getMonthKey());
        vo.setMessageId(task.getMessageId());
        vo.setTargetType(task.getTargetType());
        vo.setPublishStatus(task.getPublishStatus());
        vo.setPublishAttempts(task.getPublishAttempts());
        vo.setNextPublishTime(task.getNextPublishTime());
        vo.setLeaseOwner(task.getLeaseOwner());
        vo.setLeaseUntil(task.getLeaseUntil());
        vo.setLastPublishTime(task.getLastPublishTime());
        vo.setLastPublishError(task.getLastPublishError());
        vo.setDeliveryStatus(task.getDeliveryStatus());
        vo.setDeliveryAttempts(task.getDeliveryAttempts());
        vo.setNextDeliveryTime(task.getNextDeliveryTime());
        vo.setLastDeliveryError(task.getLastDeliveryError());
        vo.setVersion(task.getVersion());
        vo.setCreateTime(task.getCreateTime());
        vo.setUpdateTime(task.getUpdateTime());
        return vo;
    }

    private void checkAdmin() {
        if (!currentUserContext.isAdmin()) {
            throw new IllegalStateException("Admin role is required for Inbox Outbox management");
        }
    }
}
