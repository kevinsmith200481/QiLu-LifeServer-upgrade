package com.qilu.controller;

import com.qilu.dto.InboxBatchActionRequest;
import com.qilu.dto.InboxQueryRequest;
import com.qilu.dto.Result;
import com.qilu.service.IInboxMessageService;
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
@RequestMapping("/inbox")
public class InboxController {

    @Resource
    private IInboxMessageService inboxMessageService;

    @GetMapping("/messages")
    public Result queryMyMessages(@Valid InboxQueryRequest request) {
        return inboxMessageService.queryMyMessages(request);
    }

    @GetMapping("/messages/{monthKey}/{messageId}")
    public Result detail(@PathVariable String monthKey, @PathVariable Long messageId) {
        return inboxMessageService.detail(monthKey, messageId);
    }

    @GetMapping("/unread-counts")
    public Result unreadCounts() {
        return inboxMessageService.unreadCounts();
    }

    @PatchMapping("/messages/read")
    public Result markRead(@Valid @RequestBody InboxBatchActionRequest request) {
        return inboxMessageService.markRead(request);
    }

    @PatchMapping("/messages/read-all")
    public Result markAllRead(@RequestParam(required = false) String monthKey) {
        return inboxMessageService.markAllRead(monthKey);
    }

    @PatchMapping("/messages/star")
    public Result star(@Valid @RequestBody InboxBatchActionRequest request) {
        return inboxMessageService.star(request);
    }

    @PatchMapping("/messages/unstar")
    public Result unstar(@Valid @RequestBody InboxBatchActionRequest request) {
        return inboxMessageService.unstar(request);
    }

    @PostMapping("/messages/delete")
    public Result delete(@Valid @RequestBody InboxBatchActionRequest request) {
        return inboxMessageService.delete(request);
    }
}
