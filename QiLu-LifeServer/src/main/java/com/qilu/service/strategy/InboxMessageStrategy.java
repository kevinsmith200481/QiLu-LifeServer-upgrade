package com.qilu.service.strategy;

import com.qilu.dto.InboxSendRequest;
import com.qilu.entity.InboxMessage;
import com.qilu.enums.InboxMessageType;
import com.qilu.vo.InboxRealtimePayload;

public interface InboxMessageStrategy {

    InboxMessageType supportType();

    void validate(InboxSendRequest request);

    InboxMessage buildMessage(InboxSendRequest request, Long senderId, String messageNo, String monthKey);

    InboxRealtimePayload renderPushPayload(InboxMessage message, Long userId);
}
