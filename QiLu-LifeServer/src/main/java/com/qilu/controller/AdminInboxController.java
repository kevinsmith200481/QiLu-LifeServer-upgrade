package com.qilu.controller;

import com.qilu.dto.InboxBatchActionRequest;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.InboxOutboxTaskQuery;
import com.qilu.dto.Result;
import com.qilu.service.IInboxMessageService;
import com.qilu.service.IInboxOutboxAdminService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/admin/inbox")
public class AdminInboxController {

    @Resource
    private IInboxMessageService inboxMessageService;

    @Resource
    private IInboxOutboxAdminService inboxOutboxAdminService;

    @PostMapping("/messages")
    public Result send(@Valid @RequestBody InboxSendRequest request) {
        return inboxMessageService.send(request);
    }

    @GetMapping("/messages/active")
    public Result activeMessages(@RequestParam(required = false) String monthKey,
                                 @RequestParam(defaultValue = "50") Integer pageSize) {
        return inboxMessageService.queryActiveSentMessages(monthKey, pageSize);
    }

    @PatchMapping("/messages/revoke")
    public Result batchRevoke(@Valid @RequestBody InboxBatchActionRequest request) {
        return inboxMessageService.batchRevoke(request);
    }

    @PatchMapping("/messages/{monthKey}/{messageId}/revoke")
    public Result revoke(@PathVariable String monthKey, @PathVariable Long messageId) {
        return inboxMessageService.revoke(monthKey, messageId);
    }

    @GetMapping("/outbox/tasks")
    public Result outboxTasks(@Valid InboxOutboxTaskQuery query) {
        return inboxOutboxAdminService.pageTasks(query);
    }

    @PostMapping("/outbox/tasks/{taskId}/retry")
    public Result retryOutboxTask(@PathVariable Long taskId) {
        return inboxOutboxAdminService.retryDeadTask(taskId);
    }
}
