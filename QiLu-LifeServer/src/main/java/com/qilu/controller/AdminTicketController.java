package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.dto.TicketReplyRequest;
import com.qilu.service.IServiceTicketService;
import cn.hutool.core.util.StrUtil;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/ticket")
public class AdminTicketController {

    @Resource
    private IServiceTicketService serviceTicketService;

    @GetMapping("/page")
    public Result queryTicketPage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "servicePointId", required = false) Long servicePointId,
            @RequestParam(value = "requester", required = false) String requester,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            @RequestParam(value = "studentReplyRequired", required = false) Integer studentReplyRequired) {
        return serviceTicketService.queryTicketPage(
                current,
                status,
                servicePointId,
                requester,
                startTime,
                endTime,
                sortOrder,
                studentReplyRequired
        );
    }

    @PutMapping("/{id}/accept")
    @Log(module = "Ticket", operation = "Accept ticket")
    public Result acceptTicket(@PathVariable("id") Long id) {
        return serviceTicketService.acceptTicket(id);
    }

    @PostMapping("/{id}/reply")
    @Log(module = "Ticket", operation = "Reply ticket")
    public Result replyTicket(@PathVariable("id") Long id, @RequestBody TicketReplyRequest request) {
        return serviceTicketService.replyTicket(id, request);
    }

    @PutMapping("/{id}/assign")
    @Log(module = "Ticket", operation = "Assign ticket")
    public Result assignTicket(@PathVariable("id") Long id, @RequestParam("assigneeId") Long assigneeId) {
        return serviceTicketService.assignTicket(id, assigneeId);
    }

    @PutMapping("/{id}/finish")
    @Log(module = "Ticket", operation = "Finish ticket")
    public Result finishTicket(@PathVariable("id") Long id) {
        return serviceTicketService.finishTicket(id);
    }

    @PutMapping("/{id}/close")
    @Log(module = "Ticket", operation = "Close ticket")
    public Result closeTicket(@PathVariable("id") Long id) {
        return serviceTicketService.closeTicket(id);
    }

    @PutMapping("/{id}/reject")
    @Log(module = "Ticket", operation = "Reject ticket")
    public Result rejectTicket(@PathVariable("id") Long id) {
        return serviceTicketService.rejectTicket(id);
    }

    @DeleteMapping("/{id}")
    @Log(module = "Ticket", operation = "Delete ticket")
    public Result deleteTicket(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body) {
        String remark = body == null ? "" : body.get("remark");
        if (StrUtil.isBlank(remark)) {
            return Result.fail("delete remark is required");
        }
        return serviceTicketService.deleteManagedTicket(id, remark);
    }
}
