package com.qilu.service;

import com.qilu.dto.InboxOutboxTaskQuery;
import com.qilu.dto.Result;

public interface IInboxOutboxAdminService {

    Result pageTasks(InboxOutboxTaskQuery query);

    Result retryDeadTask(Long taskId);
}
