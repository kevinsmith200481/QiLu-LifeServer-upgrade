package com.qilu.acceptance;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qilu.dto.Result;
import com.qilu.dto.TicketReplyRequest;
import com.qilu.dto.UserDTO;
import com.qilu.entity.ServiceTicket;
import com.qilu.entity.TicketComment;
import com.qilu.service.IServiceTicketService;
import com.qilu.service.ITicketCommentService;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.ticket-flow", matches = "true")
class ServiceTicketStatusFlowAcceptanceTest {

    private static final long ADMIN_ID = 9_200_001L;
    private static final long STUDENT_ID = 9_200_002L;
    private static final long OTHER_STUDENT_ID = 9_200_003L;
    private static final long ASSIGNEE_ID = 9_200_004L;
    private static final long DEFAULT_SERVICE_POINT_ID = 4L;
    private static final long DEFAULT_CATEGORY_ID = 4L;

    @Resource
    private IServiceTicketService serviceTicketService;

    @Resource
    private ITicketCommentService ticketCommentService;

    private final List<Long> createdTicketIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        for (Long ticketId : createdTicketIds) {
            ticketCommentService.remove(new QueryWrapper<TicketComment>().eq("ticket_id", ticketId));
            serviceTicketService.removeById(ticketId);
        }
        createdTicketIds.clear();
    }

    @Test
    void adminCanAcceptAssignAndFinishTicket() {
        ServiceTicket ticket = createTicket(0);

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result accept = serviceTicketService.acceptTicket(ticket.getId());
        Result assign = serviceTicketService.assignTicket(ticket.getId(), ASSIGNEE_ID);
        Result finish = serviceTicketService.finishTicket(ticket.getId());

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertTrue(Boolean.TRUE.equals(accept.getSuccess()));
        assertTrue(Boolean.TRUE.equals(assign.getSuccess()));
        assertTrue(Boolean.TRUE.equals(finish.getSuccess()));
        assertEquals(3, updated.getStatus());
        assertEquals(ASSIGNEE_ID, updated.getAssigneeId());
        assertNotNull(updated.getAcceptTime());
        assertNotNull(updated.getFinishTime());
    }

    @Test
    void adminCanFinishAcceptedTicketWithoutAssignment() {
        ServiceTicket ticket = createTicket(0);

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result accept = serviceTicketService.acceptTicket(ticket.getId());
        Result finish = serviceTicketService.finishTicket(ticket.getId());

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertTrue(Boolean.TRUE.equals(accept.getSuccess()));
        assertTrue(Boolean.TRUE.equals(finish.getSuccess()));
        assertEquals(3, updated.getStatus());
        assertNotNull(updated.getAcceptTime());
        assertNotNull(updated.getFinishTime());
    }

    @Test
    void finishPendingTicketIsRejected() {
        ServiceTicket ticket = createTicket(0);

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result result = serviceTicketService.finishTicket(ticket.getId());

        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals("Update ticket status failed", result.getErrorMsg());
        assertEquals(0, serviceTicketService.getById(ticket.getId()).getStatus());
    }

    @Test
    void adminReplyTicketMovesToAcceptedAndStoresComment() {
        ServiceTicket ticket = createTicket(0);
        TicketReplyRequest request = new TicketReplyRequest();
        request.setRemark("We are checking this ticket.");
        request.setAttachmentName("note.txt");
        request.setAttachmentUrl("/ticket/attachment/note.txt");
        request.setAttachmentSize(12L);
        request.setAttachmentType("text/plain");

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result result = serviceTicketService.replyTicket(ticket.getId(), request);

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        TicketComment comment = ticketCommentService.query().eq("ticket_id", ticket.getId()).one();
        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(1, updated.getStatus());
        assertEquals(0, updated.getStudentReplyRequired());
        assertNotNull(updated.getAcceptTime());
        assertEquals("We are checking this ticket.", comment.getContent());
        assertEquals("note.txt", comment.getAttachmentName());
        assertEquals("/ticket/attachment/note.txt", comment.getAttachmentUrl());
    }

    @Test
    void blankAdminReplyIsRejected() {
        ServiceTicket ticket = createTicket(0);
        TicketReplyRequest request = new TicketReplyRequest();
        request.setRemark(" ");

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result result = serviceTicketService.replyTicket(ticket.getId(), request);

        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals("reply remark is required", result.getErrorMsg());
        assertEquals(0, serviceTicketService.getById(ticket.getId()).getStatus());
    }

    @Test
    void studentCommentClearsReplyRequiredFlag() {
        ServiceTicket ticket = createTicket(1);
        serviceTicketService.update()
                .set("student_reply_required", 1)
                .eq("id", ticket.getId())
                .update();
        TicketComment comment = new TicketComment();
        comment.setTicketId(ticket.getId());
        comment.setContent("Here is the extra information.");

        UserHolder.saveUser(user(STUDENT_ID, "student"));
        Result result = ticketCommentService.addComment(comment);

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(0, updated.getStudentReplyRequired());
        assertNotNull(updated.getStudentReplyTime());
    }

    @Test
    void closeTicketIsIdempotentAfterFirstClose() {
        ServiceTicket ticket = createTicket(1);

        UserHolder.saveUser(user(ADMIN_ID, "admin"));
        Result first = serviceTicketService.closeTicket(ticket.getId());
        Result second = serviceTicketService.closeTicket(ticket.getId());

        assertTrue(Boolean.TRUE.equals(first.getSuccess()));
        assertTrue(Boolean.TRUE.equals(second.getSuccess()));
        assertEquals(4, serviceTicketService.getById(ticket.getId()).getStatus());
    }

    @Test
    void studentCanEvaluateOnlyFinishedOwnTicketOnce() {
        ServiceTicket ticket = createTicket(3);

        UserHolder.saveUser(user(STUDENT_ID, "student"));
        Result first = serviceTicketService.evaluateTicket(ticket.getId(), 5, "Handled quickly");
        Result second = serviceTicketService.evaluateTicket(ticket.getId(), 4, "Duplicate evaluation");

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertTrue(Boolean.TRUE.equals(first.getSuccess()));
        assertEquals(Boolean.FALSE, second.getSuccess());
        assertEquals("Ticket has already been evaluated", second.getErrorMsg());
        assertEquals(5, updated.getRating());
        assertEquals("Handled quickly", updated.getEvaluation());
        assertNotNull(updated.getEvaluateTime());
    }

    @Test
    void studentCannotEvaluateUnfinishedOrOthersTicket() {
        ServiceTicket unfinished = createTicket(1);
        ServiceTicket finishedByOther = createTicket(3);

        UserHolder.saveUser(user(STUDENT_ID, "student"));
        Result unfinishedResult = serviceTicketService.evaluateTicket(unfinished.getId(), 5, "Too early");

        UserHolder.saveUser(user(OTHER_STUDENT_ID, "student"));
        Result otherResult = serviceTicketService.evaluateTicket(finishedByOther.getId(), 5, "Not mine");

        assertEquals(Boolean.FALSE, unfinishedResult.getSuccess());
        assertEquals("Only finished tickets can be evaluated", unfinishedResult.getErrorMsg());
        assertEquals(Boolean.FALSE, otherResult.getSuccess());
        assertEquals("Ticket not found", otherResult.getErrorMsg());
    }

    @Test
    void invalidRatingIsRejectedBeforeTicketMutation() {
        ServiceTicket ticket = createTicket(3);

        UserHolder.saveUser(user(STUDENT_ID, "student"));
        Result result = serviceTicketService.evaluateTicket(ticket.getId(), 6, "Invalid rating");

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals("Rating must be between 1 and 5", result.getErrorMsg());
        assertNull(updated.getRating());
        assertNull(updated.getEvaluation());
    }

    private ServiceTicket createTicket(Integer status) {
        ServiceTicket ticket = new ServiceTicket();
        ticket.setUserId(STUDENT_ID);
        ticket.setServicePointId(DEFAULT_SERVICE_POINT_ID);
        ticket.setCategoryId(DEFAULT_CATEGORY_ID);
        ticket.setTitle("ticket-flow-test");
        ticket.setContent("Created by service ticket status flow acceptance test");
        ticket.setPriority(1);
        ticket.setStatus(status);
        ticket.setCreateTime(LocalDateTime.now());
        serviceTicketService.save(ticket);
        createdTicketIds.add(ticket.getId());
        return ticket;
    }

    private UserDTO user(Long id, String role) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setNickName("ticket-flow-" + id);
        user.setRole(role);
        return user;
    }
}
