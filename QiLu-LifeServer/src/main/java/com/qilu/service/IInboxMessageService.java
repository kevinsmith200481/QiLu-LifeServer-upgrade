package com.qilu.service;

import com.qilu.dto.InboxBatchActionRequest;
import com.qilu.dto.InboxQueryRequest;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;

public interface IInboxMessageService {

    Result send(InboxSendRequest request);

    Result sendInternal(InboxSendRequest request, Long senderId);

    Result queryMyMessages(InboxQueryRequest request);

    Result detail(String monthKey, Long messageId);

    Result markRead(InboxBatchActionRequest request);

    Result markAllRead(String monthKey);

    Result markUnread(InboxBatchActionRequest request);

    Result star(InboxBatchActionRequest request);

    Result unstar(InboxBatchActionRequest request);

    Result delete(InboxBatchActionRequest request);

    Result unreadCounts();

    Result queryActiveSentMessages(String monthKey, Integer pageSize);

    Result batchRevoke(InboxBatchActionRequest request);

    Result revoke(String monthKey, Long messageId);
}
