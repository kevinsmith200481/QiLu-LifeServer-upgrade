package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.dto.TicketReplyRequest;
import com.qilu.dto.UserDTO;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.ServiceTicket;
import com.qilu.entity.TicketComment;
import com.qilu.entity.User;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import com.qilu.mapper.ServiceTicketMapper;
import com.qilu.service.IInboxMessageService;
import com.qilu.service.IServicePointService;
import com.qilu.service.IServiceTicketService;
import com.qilu.service.ITicketCommentService;
import com.qilu.service.IUserService;
import com.qilu.utils.SystemConstants;
import com.qilu.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ServiceTicketServiceImpl extends ServiceImpl<ServiceTicketMapper, ServiceTicket> implements IServiceTicketService {

    @Resource
    private ITicketCommentService ticketCommentService;

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IInboxMessageService inboxMessageService;

    @Resource
    private IUserService userService;

    @Override
    public Result createTicket(ServiceTicket ticket) {
        if (ticket.getServicePointId() == null) {
            return Result.fail("service point is required");
        }
        ServicePoint servicePoint = servicePointService.getById(ticket.getServicePointId());
        if (servicePoint == null || !Integer.valueOf(1).equals(servicePoint.getStatus())) {
            return Result.fail("service point is not enabled");
        }
        if (ticket.getCategoryId() == null) {
            return Result.fail("ticket category is required");
        }
        if (StrUtil.isBlank(ticket.getContactPhone())) {
            return Result.fail("contact phone is required");
        }
        if (StrUtil.isBlank(ticket.getDetailAddress())) {
            return Result.fail("detail address is required");
        }
        if (StrUtil.isNotBlank(ticket.getAttachmentName()) && ticket.getAttachmentName().length() > 255) {
            return Result.fail("attachment name is too long");
        }
        if (StrUtil.isNotBlank(ticket.getAttachmentUrl()) && ticket.getAttachmentUrl().length() > 512) {
            return Result.fail("attachment url is too long");
        }
        ticket.setUserId(UserHolder.getUser().getId());
        ticket.setStatus(0);
        ticket.setPriority(1);
        ticket.setUserHidden(0);
        ticket.setAdminDeleted(0);
        ticket.setAiSummary(null);
        ticket.setAiCategory(null);
        ticket.setCreateTime(LocalDateTime.now());
        save(ticket);
        return Result.ok(ticket.getId());
    }

    @Override
    public Result queryMyTickets(Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<ServiceTicket> page = query()
                .eq("user_id", userId)
                .eq("user_hidden", 0)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result queryTicketDetail(Long id) {
        ServiceTicket ticket = getById(id);
        if (ticket == null || !canViewTicket(ticket)) {
            return Result.fail("Ticket not found");
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("ticket", ticket);
        detail.put("comments", ticketCommentService.query().eq("ticket_id", id).orderByAsc("create_time").list());
        return Result.ok(detail);
    }

    @Override
    public Result queryTicketPage(
            Integer current,
            Integer status,
            Long servicePointId,
            String requester,
            String startTimeText,
            String endTimeText,
            String sortOrder,
            Integer studentReplyRequired
    ) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        LocalDateTime startTime;
        LocalDateTime endTime;
        try {
            startTime = parseQueryTime(startTimeText);
            endTime = parseQueryTime(endTimeText);
        } catch (IllegalArgumentException e) {
            return Result.fail("invalid time range");
        }
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            return Result.fail("invalid time range");
        }
        List<Long> requesterIds = queryRequesterIds(requester);
        if (requesterIds != null && requesterIds.isEmpty()) {
            return Result.ok(Collections.emptyList(), 0L);
        }
        if (isManager(user)) {
            List<Long> servicePointIds = queryManagedServicePointIds(user.getId());
            if (servicePointIds.isEmpty()) {
                return Result.ok(Collections.emptyList(), 0L);
            }
            if (servicePointId != null && !servicePointIds.contains(servicePointId)) {
                return Result.ok(Collections.emptyList(), 0L);
            }
            Page<ServiceTicket> page = pageManagedTickets(
                    current,
                    status,
                    servicePointId,
                    servicePointId == null ? servicePointIds : null,
                    requesterIds,
                    startTime,
                    endTime,
                    sortOrder,
                    studentReplyRequired
            );
            return Result.ok(page.getRecords(), page.getTotal());
        }
        if (!isAdmin(user)) {
            return Result.fail("No permission to query tickets");
        }
        Page<ServiceTicket> page = pageManagedTickets(
                current,
                status,
                servicePointId,
                null,
                requesterIds,
                startTime,
                endTime,
                sortOrder,
                studentReplyRequired
        );
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result hideMyTicket(Long id) {
        Long userId = UserHolder.getUser().getId();
        boolean success = update()
                .set("user_hidden", 1)
                .eq("id", id)
                .eq("user_id", userId)
                .update();
        ServiceTicket ticket = getById(id);
        if (success || (ticket != null && userId.equals(ticket.getUserId()) && Integer.valueOf(1).equals(ticket.getUserHidden()))) {
            return Result.ok();
        }
        return Result.fail("Ticket not found");
    }

    @Override
    public Result deleteManagedTicket(Long id, String remark) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        if (StrUtil.isBlank(remark)) {
            return Result.fail("delete remark is required");
        }
        if (remark.length() > 512) {
            return Result.fail("delete remark is too long");
        }
        UserDTO user = UserHolder.getUser();
        boolean success = update()
                .set("admin_deleted", 1)
                .set("user_hidden", 0)
                .set("delete_remark", remark)
                .set("deleted_by", user.getId())
                .set("delete_time", LocalDateTime.now())
                .eq("id", id)
                .update();
        ServiceTicket ticket = getById(id);
        if (success || (ticket != null && Integer.valueOf(1).equals(ticket.getAdminDeleted()))) {
            return Result.ok();
        }
        return Result.fail("Delete ticket failed");
    }

    @Override
    public Result acceptTicket(Long id) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        ServiceTicket ticket = getById(id);
        Result result = updateTicketStatus(id, 0, 1, "accept_time", LocalDateTime.now());
        if (Boolean.TRUE.equals(result.getSuccess()) && ticket != null && Integer.valueOf(0).equals(ticket.getStatus())) {
            sendTicketStudentMessage(ticket, "工单已受理", "你的工单「" + safe(ticket.getTitle()) + "」已受理。", InboxMessageType.BUSINESS_REMINDER);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result replyTicket(Long id, TicketReplyRequest request) {
        Result validation = validateTicketReplyRequest(request);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            return validation;
        }
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        ServiceTicket ticket = getById(id);
        if (ticket == null || isTerminalStatus(ticket.getStatus())) {
            return Result.fail("Ticket cannot be replied");
        }
        UserDTO user = UserHolder.getUser();
        LocalDateTime now = LocalDateTime.now();
        TicketComment comment = new TicketComment();
        comment.setTicketId(id);
        comment.setUserId(user.getId());
        comment.setUserType(1);
        comment.setContent(request.getRemark());
        comment.setAttachmentName(request.getAttachmentName());
        comment.setAttachmentUrl(request.getAttachmentUrl());
        comment.setAttachmentSize(request.getAttachmentSize());
        comment.setAttachmentType(request.getAttachmentType());
        comment.setCreateTime(now);
        ticketCommentService.save(comment);
        boolean needStudentReply = Boolean.TRUE.equals(request.getNeedStudentReply());
        boolean updated = update()
                .set("status", 1)
                .set("student_reply_required", needStudentReply ? 1 : 0)
                .set(ticket.getAcceptTime() == null, "accept_time", now)
                .eq("id", id)
                .notIn("status", 3, 4, 5)
                .update();
        ServiceTicket updatedTicket = getById(id);
        if (!updated && (updatedTicket == null || !Integer.valueOf(1).equals(updatedTicket.getStatus()))) {
            return Result.fail("Reply ticket failed");
        }
        sendTicketReplyMessage(ticket, request.getRemark(), needStudentReply, user.getId());
        return Result.ok(comment.getId());
    }

    @Override
    public Result assignTicket(Long id, Long assigneeId) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        if (!canAssignTicket(id, assigneeId)) {
            return Result.fail("No permission to assign this ticket to target assignee");
        }
        boolean success = update()
                .set("assignee_id", assigneeId)
                .set("status", 2)
                .eq("id", id)
                .in("status", 0, 1)
                .update();
        ServiceTicket ticket = getById(id);
        if (success || (ticket != null && Integer.valueOf(2).equals(ticket.getStatus()) && assigneeId.equals(ticket.getAssigneeId()))) {
            return Result.ok();
        }
        return Result.fail("Assign ticket failed");
    }

    @Override
    public Result finishTicket(Long id) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        ServiceTicket ticket = getById(id);
        Result result = updateTicketStatus(id, Arrays.asList(1, 2), 3, "finish_time", LocalDateTime.now());
        if (Boolean.TRUE.equals(result.getSuccess()) && ticket != null && Arrays.asList(1, 2).contains(ticket.getStatus())) {
            sendTicketStudentMessage(ticket, "工单已完成", "你的工单「" + safe(ticket.getTitle()) + "」已完成。", InboxMessageType.BUSINESS_REMINDER);
        }
        return result;
    }

    @Override
    public Result closeTicket(Long id) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        ServiceTicket before = getById(id);
        boolean success = update().set("status", 4).eq("id", id).ne("status", 4).update();
        ServiceTicket ticket = getById(id);
        if (success || (ticket != null && Integer.valueOf(4).equals(ticket.getStatus()))) {
            if (success && before != null && !Integer.valueOf(4).equals(before.getStatus())) {
                sendTicketStudentMessage(before, "工单已关闭", "你的工单「" + safe(before.getTitle()) + "」已关闭。", InboxMessageType.BUSINESS_REMINDER);
            }
            return Result.ok();
        }
        return Result.fail("Close ticket failed");
    }

    @Override
    public Result rejectTicket(Long id) {
        if (!canManageTicket(id)) {
            return Result.fail("No permission to manage this ticket");
        }
        ServiceTicket ticket = getById(id);
        Result result = updateTicketStatus(id, Arrays.asList(0, 1), 5, "finish_time", LocalDateTime.now());
        if (Boolean.TRUE.equals(result.getSuccess()) && ticket != null && Arrays.asList(0, 1).contains(ticket.getStatus())) {
            sendTicketStudentMessage(ticket, "工单已拒绝", "你的工单「" + safe(ticket.getTitle()) + "」已被拒绝。", InboxMessageType.BUSINESS_REMINDER);
        }
        return result;
    }

    @Override
    public Result evaluateTicket(Long id, Integer rating, String evaluation) {
        if (rating == null || rating < 1 || rating > 5) {
            return Result.fail("Rating must be between 1 and 5");
        }
        Long userId = UserHolder.getUser().getId();
        ServiceTicket ticket = getById(id);
        if (ticket == null || !userId.equals(ticket.getUserId())) {
            return Result.fail("Ticket not found");
        }
        if (ticket.getStatus() == null || ticket.getStatus() != 3) {
            return Result.fail("Only finished tickets can be evaluated");
        }
        if (ticket.getRating() != null) {
            return Result.fail("Ticket has already been evaluated");
        }
        boolean success = update()
                .set("rating", rating)
                .set("evaluation", evaluation)
                .set("evaluate_time", LocalDateTime.now())
                .eq("id", id)
                .isNull("rating")
                .update();
        ServiceTicket updated = getById(id);
        if (success || (updated != null && rating.equals(updated.getRating()))) {
            return Result.ok();
        }
        return Result.fail("Evaluate ticket failed");
    }

    private Page<ServiceTicket> pageManagedTickets(
            Integer current,
            Integer status,
            Long servicePointId,
            List<Long> servicePointIds,
            List<Long> requesterIds,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String sortOrder,
            Integer studentReplyRequired
    ) {
        QueryChainWrapper<ServiceTicket> ticketQuery = query()
                .eq(status != null, "status", status)
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .in(servicePointIds != null && !servicePointIds.isEmpty(), "service_point_id", servicePointIds)
                .in(requesterIds != null && !requesterIds.isEmpty(), "user_id", requesterIds)
                .ge(startTime != null, "create_time", startTime)
                .le(endTime != null, "create_time", endTime)
                .eq(studentReplyRequired != null, "student_reply_required", studentReplyRequired)
                .eq("admin_deleted", 0);
        if ("asc".equalsIgnoreCase(sortOrder)) {
            ticketQuery.orderByAsc("create_time");
        } else {
            ticketQuery.orderByDesc("create_time");
        }
        return ticketQuery.page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
    }

    private LocalDateTime parseQueryTime(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return LocalDateTime.parse(value.trim());
    }

    private List<Long> queryRequesterIds(String requester) {
        if (StrUtil.isBlank(requester)) {
            return null;
        }
        String keyword = requester.trim();
        List<Long> userIds = new ArrayList<>();
        appendNumericUserId(userIds, keyword);
        List<User> users = userService.query()
                .select("id")
                .and(wrapper -> wrapper.like("phone", keyword).or().like("nick_name", keyword))
                .last("limit 200")
                .list();
        for (User user : users) {
            if (user.getId() != null && !userIds.contains(user.getId())) {
                userIds.add(user.getId());
            }
        }
        return userIds;
    }

    private void appendNumericUserId(List<Long> userIds, String keyword) {
        if (!keyword.matches("\\d+")) {
            return;
        }
        try {
            userIds.add(Long.valueOf(keyword));
        } catch (NumberFormatException ignored) {
        }
    }

    private Result updateTicketStatus(Long id, Integer fromStatus, Integer toStatus, String timeField, LocalDateTime time) {
        return updateTicketStatus(id, Collections.singletonList(fromStatus), toStatus, timeField, time);
    }

    private Result updateTicketStatus(Long id, List<Integer> fromStatuses, Integer toStatus, String timeField, LocalDateTime time) {
        boolean success = update()
                .set("status", toStatus)
                .set(timeField, time)
                .eq("id", id)
                .in("status", fromStatuses)
                .update();
        ServiceTicket ticket = getById(id);
        if (success || (ticket != null && toStatus.equals(ticket.getStatus()))) {
            return Result.ok();
        }
        return Result.fail("Update ticket status failed");
    }

    private Result validateTicketReplyRequest(TicketReplyRequest request) {
        if (request == null || StrUtil.isBlank(request.getRemark())) {
            return Result.fail("reply remark is required");
        }
        if (request.getRemark().length() > 1024) {
            return Result.fail("reply remark is too long");
        }
        if (StrUtil.isNotBlank(request.getAttachmentName()) && request.getAttachmentName().length() > 255) {
            return Result.fail("attachment name is too long");
        }
        if (StrUtil.isNotBlank(request.getAttachmentUrl()) && request.getAttachmentUrl().length() > 512) {
            return Result.fail("attachment url is too long");
        }
        if (StrUtil.isNotBlank(request.getAttachmentType()) && request.getAttachmentType().length() > 128) {
            return Result.fail("attachment type is too long");
        }
        return Result.ok();
    }

    private void sendTicketReplyMessage(ServiceTicket ticket, String remark, boolean needStudentReply, Long senderId) {
        String title = needStudentReply ? "工单需要你回复" : "工单有新回复";
        String content = needStudentReply
                ? "你的工单「" + safe(ticket.getTitle()) + "」需要回复。\n回复内容：" + safe(remark)
                : "你的工单「" + safe(ticket.getTitle()) + "」有新回复。\n回复内容：" + safe(remark);
        sendTicketStudentMessage(ticket, title, content, InboxMessageType.SITE_REPLY, senderId, buildReplySummary(remark));
    }

    private void sendTicketStudentMessage(ServiceTicket ticket, String title, String content, InboxMessageType messageType) {
        UserDTO user = UserHolder.getUser();
        sendTicketStudentMessage(ticket, title, content, messageType, user == null ? null : user.getId(), content);
    }

    private void sendTicketStudentMessage(ServiceTicket ticket, String title, String content, InboxMessageType messageType, Long senderId, String summary) {
        if (ticket == null || ticket.getUserId() == null) {
            return;
        }
        InboxSendRequest request = new InboxSendRequest();
        request.setMessageType(messageType.getCode());
        request.setTargetType(InboxTargetType.USER.getCode());
        request.setTitle(title);
        request.setContent(content);
        request.setSummary(buildReplySummary(summary));
        request.setBusinessType("SERVICE_TICKET");
        request.setBusinessId(ticket.getId());
        request.setUserIds(Collections.singletonList(ticket.getUserId()));
        Result result = inboxMessageService.sendInternal(request, senderId);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException(result.getErrorMsg());
        }
    }

    private String buildReplySummary(String remark) {
        String value = safe(remark);
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private boolean isTerminalStatus(Integer status) {
        return Integer.valueOf(3).equals(status)
                || Integer.valueOf(4).equals(status)
                || Integer.valueOf(5).equals(status);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean canViewTicket(ServiceTicket ticket) {
        UserDTO user = UserHolder.getUser();
        if (user == null || ticket == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (isManager(user)) {
            return isManagedServicePoint(user.getId(), ticket.getServicePointId());
        }
        return user.getId().equals(ticket.getUserId());
    }

    private boolean canManageTicket(Long ticketId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (!isManager(user)) {
            return false;
        }
        ServiceTicket ticket = getById(ticketId);
        return ticket != null && isManagedServicePoint(user.getId(), ticket.getServicePointId());
    }

    private boolean canAssignTicket(Long ticketId, Long assigneeId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || assigneeId == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (!isManager(user)) {
            return false;
        }
        ServiceTicket ticket = getById(ticketId);
        if (ticket == null || !isManagedServicePoint(user.getId(), ticket.getServicePointId())) {
            return false;
        }
        ServicePoint servicePoint = servicePointService.getById(ticket.getServicePointId());
        return user.getId().equals(assigneeId)
                || (servicePoint != null && assigneeId.equals(servicePoint.getManagerId()));
    }

    private boolean isManagedServicePoint(Long managerId, Long servicePointId) {
        if (managerId == null || servicePointId == null) {
            return false;
        }
        ServicePoint servicePoint = servicePointService.getById(servicePointId);
        return servicePoint != null && managerId.equals(servicePoint.getManagerId());
    }

    private List<Long> queryManagedServicePointIds(Long managerId) {
        if (managerId == null) {
            return Collections.emptyList();
        }
        return servicePointService.query()
                .eq("manager_id", managerId)
                .list()
                .stream()
                .map(ServicePoint::getId)
                .collect(Collectors.toList());
    }

    private boolean isAdmin(UserDTO user) {
        return "admin".equals(user.getRole());
    }

    private boolean isManager(UserDTO user) {
        return "manager".equals(user.getRole());
    }
}
