package com.qilu.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.ServiceTicket;
import com.qilu.entity.TicketComment;
import com.qilu.entity.User;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.ServiceTicketMapper;
import com.qilu.mapper.TicketCommentMapper;
import com.qilu.service.IInboxMessageService;
import com.qilu.service.IServicePointService;
import com.qilu.service.ITicketCommentService;
import com.qilu.service.IUserService;
import com.qilu.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class TicketCommentServiceImpl extends ServiceImpl<TicketCommentMapper, TicketComment> implements ITicketCommentService {

    @Resource
    private ServiceTicketMapper serviceTicketMapper;

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IInboxMessageService inboxMessageService;

    @Resource
    private IUserService userService;

    @Override
    public Result addComment(TicketComment comment) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        ServiceTicket ticket = serviceTicketMapper.selectById(comment.getTicketId());
        if (ticket == null || !canComment(user, ticket)) {
            return Result.fail("Ticket not found");
        }
        comment.setUserId(user.getId());
        if (comment.getUserType() == null) {
            comment.setUserType(isAdmin(user) || isManager(user) ? 1 : 0);
        }
        comment.setCreateTime(LocalDateTime.now());
        save(comment);
        clearStudentReplyRequired(user, ticket);
        sendStudentReplyMessage(user, ticket, comment);
        return Result.ok(comment.getId());
    }

    private void sendStudentReplyMessage(UserDTO user, ServiceTicket ticket, TicketComment comment) {
        if (isAdmin(user) || isManager(user) || !user.getId().equals(ticket.getUserId())) {
            return;
        }
        List<Long> receiverIds = queryTicketReceiverIds(ticket, user.getId());
        if (receiverIds.isEmpty()) {
            return;
        }
        InboxSendRequest request = new InboxSendRequest();
        request.setMessageType(InboxMessageType.SITE_REPLY.getCode());
        request.setTargetType(InboxTargetType.USER.getCode());
        request.setTitle("学生已回复工单");
        request.setContent(buildStudentReplyContent(ticket, comment));
        request.setSummary(buildReplySummary(comment.getContent()));
        request.setBusinessType("SERVICE_TICKET");
        request.setBusinessId(ticket.getId());
        request.setUserIds(receiverIds);
        try {
            Result result = inboxMessageService.sendInternal(request, user.getId());
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                log.warn("send ticket student reply inbox message failed, ticketId={}, error={}",
                        ticket.getId(), result.getErrorMsg());
            }
        } catch (RuntimeException e) {
            log.warn("send ticket student reply inbox message failed, ticketId={}", ticket.getId(), e);
        }
    }

    private List<Long> queryTicketReceiverIds(ServiceTicket ticket, Long studentUserId) {
        Set<Long> receiverIds = new LinkedHashSet<>();
        if (ticket.getAssigneeId() != null) {
            receiverIds.add(ticket.getAssigneeId());
        }
        ServicePoint servicePoint = servicePointService.getById(ticket.getServicePointId());
        if (servicePoint != null && servicePoint.getManagerId() != null) {
            receiverIds.add(servicePoint.getManagerId());
        }
        List<User> admins = userService.query().select("id").eq("role", "admin").list();
        for (User admin : admins) {
            if (admin.getId() != null) {
                receiverIds.add(admin.getId());
            }
        }
        receiverIds.remove(studentUserId);
        return new ArrayList<>(receiverIds);
    }

    private String buildStudentReplyContent(ServiceTicket ticket, TicketComment comment) {
        return "学生已回复工单「" + safe(ticket.getTitle()) + "」。\n回复内容：" + safe(comment.getContent());
    }

    private String buildReplySummary(String content) {
        String value = safe(content);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void clearStudentReplyRequired(UserDTO user, ServiceTicket ticket) {
        if (isAdmin(user) || isManager(user) || !user.getId().equals(ticket.getUserId())) {
            return;
        }
        if (!Integer.valueOf(1).equals(ticket.getStudentReplyRequired())) {
            return;
        }
        serviceTicketMapper.update(null, new UpdateWrapper<ServiceTicket>()
                .set("student_reply_required", 0)
                .set("student_reply_time", LocalDateTime.now())
                .eq("id", ticket.getId())
                .eq("user_id", user.getId())
                .eq("student_reply_required", 1));
    }

    private boolean canComment(UserDTO user, ServiceTicket ticket) {
        if (isAdmin(user)) {
            return true;
        }
        if (isManager(user)) {
            ServicePoint servicePoint = servicePointService.getById(ticket.getServicePointId());
            return servicePoint != null && user.getId().equals(servicePoint.getManagerId());
        }
        return user.getId().equals(ticket.getUserId());
    }

    private boolean isAdmin(UserDTO user) {
        return "admin".equals(user.getRole());
    }

    private boolean isManager(UserDTO user) {
        return "manager".equals(user.getRole());
    }
}
