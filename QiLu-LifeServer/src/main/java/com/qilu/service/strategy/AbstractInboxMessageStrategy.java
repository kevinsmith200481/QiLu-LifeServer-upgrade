package com.qilu.service.strategy;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.qilu.dto.InboxSendRequest;
import com.qilu.entity.InboxMessage;
import com.qilu.enums.InboxMessageStatus;
import com.qilu.enums.InboxTargetType;
import com.qilu.vo.InboxRealtimePayload;

import java.time.LocalDateTime;

public abstract class AbstractInboxMessageStrategy implements InboxMessageStrategy {

    @Override
    public void validate(InboxSendRequest request) {
        InboxTargetType targetType = InboxTargetType.of(request.getTargetType());
        if (targetType == InboxTargetType.USER && CollUtil.isEmpty(request.getUserIds())) {
            throw new IllegalArgumentException("target user list is required");
        }
        if (targetType == InboxTargetType.ROLE && CollUtil.isEmpty(request.getRoles())) {
            throw new IllegalArgumentException("target role list is required");
        }
        if (request.getExpireTime() != null && request.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("expireTime must be future");
        }
    }

    @Override
    public InboxMessage buildMessage(InboxSendRequest request, Long senderId, String messageNo, String monthKey) {
        InboxMessage message = new InboxMessage();
        message.setMonthKey(monthKey);
        message.setMessageNo(messageNo);
        message.setMessageType(supportType().getCode());
        message.setTargetType(request.getTargetType());
        message.setTitle(request.getTitle());
        message.setContent(request.getContent());
        message.setSummary(StrUtil.blankToDefault(request.getSummary(), buildSummary(request.getContent())));
        message.setBusinessType(request.getBusinessType());
        message.setBusinessId(request.getBusinessId());
        message.setTargetRoles(CollUtil.isEmpty(request.getRoles()) ? null : String.join(",", request.getRoles()));
        message.setStatus(InboxMessageStatus.NORMAL.getCode());
        message.setSenderId(senderId);
        message.setExpireTime(request.getExpireTime());
        return message;
    }

    @Override
    public InboxRealtimePayload renderPushPayload(InboxMessage message, Long userId) {
        InboxRealtimePayload payload = new InboxRealtimePayload();
        payload.setUserId(userId);
        payload.setMessageId(message.getId());
        payload.setMessageType(message.getMessageType());
        payload.setTitle(message.getTitle());
        payload.setSummary(message.getSummary());
        payload.setCreateTime(message.getCreateTime());
        return payload;
    }

    private String buildSummary(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        return content.length() <= 120 ? content : content.substring(0, 120);
    }
}
