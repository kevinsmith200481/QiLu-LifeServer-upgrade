package com.qilu.acceptance;

import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.ServiceTicket;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IServicePointService;
import com.qilu.service.IServiceTicketService;
import com.qilu.utils.RedisConstants;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.manager-scope", matches = "true")
class ManagerDataScopeAcceptanceTest {

    private static final long MANAGER_A_ID = 9_100_001L;
    private static final long MANAGER_B_ID = 9_100_002L;
    private static final long OUTSIDER_ID = 9_100_003L;
    private static final long STUDENT_ID = 9_100_004L;

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IServiceTicketService serviceTicketService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final List<Long> createdTicketIds = new ArrayList<>();
    private final List<Long> createdSlotIds = new ArrayList<>();
    private final List<Long> createdServicePointIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
        for (Long ticketId : createdTicketIds) {
            serviceTicketService.removeById(ticketId);
        }
        for (Long slotId : createdSlotIds) {
            appointmentSlotService.removeById(slotId);
            stringRedisTemplate.delete(RedisConstants.APPOINTMENT_QUOTA_KEY + slotId);
        }
        for (Long servicePointId : createdServicePointIds) {
            servicePointService.removeById(servicePointId);
        }
    }

    @Test
    void managerCanOnlyQueryOwnServicePoints() {
        ServicePoint ownPoint = createServicePoint("manager-scope-own", MANAGER_A_ID);
        ServicePoint otherPoint = createServicePoint("manager-scope-other", MANAGER_B_ID);

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = servicePointService.queryAdminPage(1, null, "manager-scope");

        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        List<?> records = (List<?>) result.getData();
        assertTrue(records.stream().anyMatch(item -> ((ServicePoint) item).getId().equals(ownPoint.getId())));
        assertFalse(records.stream().anyMatch(item -> ((ServicePoint) item).getId().equals(otherPoint.getId())));
    }

    @Test
    void managerUpdateRequiresAdminApprovalBeforeEnable() {
        ServicePoint ownPoint = createServicePoint("manager-scope-review", MANAGER_A_ID);
        ServicePoint update = new ServicePoint();
        update.setId(ownPoint.getId());
        update.setName("manager-scope-review-updated");
        update.setCategoryId(ownPoint.getCategoryId());
        update.setManagerId(MANAGER_A_ID);
        update.setArea(ownPoint.getArea());
        update.setAddress(ownPoint.getAddress());
        update.setX(ownPoint.getX());
        update.setY(ownPoint.getY());
        update.setOpenHours(ownPoint.getOpenHours());
        update.setDescription("Updated by manager and waiting for admin approval");
        update.setStatus(1);
        update.setScore(ownPoint.getScore());
        update.setServiceCount(ownPoint.getServiceCount());

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result updateResult = servicePointService.updateServicePoint(update);
        Result enableResult = servicePointService.enableServicePoint(ownPoint.getId());
        Result disableResult = servicePointService.disableServicePoint(ownPoint.getId());
        Result deleteResult = servicePointService.deleteServicePoint(ownPoint.getId());

        ServicePoint pendingPoint = servicePointService.getById(ownPoint.getId());
        assertTrue(Boolean.TRUE.equals(updateResult.getSuccess()));
        assertEquals(2, pendingPoint.getStatus());
        assertEquals(Boolean.FALSE, enableResult.getSuccess());
        assertEquals("Only admin can enable service point", enableResult.getErrorMsg());
        assertEquals(Boolean.FALSE, disableResult.getSuccess());
        assertEquals("Only admin can disable service point", disableResult.getErrorMsg());
        assertEquals(Boolean.FALSE, deleteResult.getSuccess());
        assertEquals("Only admin can delete service point", deleteResult.getErrorMsg());
        assertEquals(2, servicePointService.getById(ownPoint.getId()).getStatus());

        UserHolder.saveUser(user(OUTSIDER_ID, "admin"));
        Result approveResult = servicePointService.approveServicePoint(ownPoint.getId());

        assertTrue(Boolean.TRUE.equals(approveResult.getSuccess()));
        assertEquals(1, servicePointService.getById(ownPoint.getId()).getStatus());
    }

    @Test
    void managerCannotMoveAppointmentSlotToUnmanagedServicePoint() {
        ServicePoint ownPoint = createServicePoint("manager-scope-slot-own", MANAGER_A_ID);
        ServicePoint otherPoint = createServicePoint("manager-scope-slot-other", MANAGER_B_ID);
        AppointmentSlot slot = createSlot(ownPoint.getId());

        AppointmentSlot update = new AppointmentSlot();
        update.setId(slot.getId());
        update.setServicePointId(otherPoint.getId());
        update.setTitle("illegal move");
        update.setTotalQuota(5);
        update.setAvailableQuota(5);
        update.setStartTime(LocalDateTime.now().plusDays(3));
        update.setEndTime(LocalDateTime.now().plusDays(3).plusHours(1));
        update.setStatus(1);

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = appointmentSlotService.updateAppointmentSlot(update);

        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals("No permission to move this appointment slot to target service point", result.getErrorMsg());
        assertEquals(ownPoint.getId(), appointmentSlotService.getById(slot.getId()).getServicePointId());
    }

    @Test
    void managerCannotAssignTicketToUnrelatedUser() {
        ServicePoint ownPoint = createServicePoint("manager-scope-ticket-own", MANAGER_A_ID);
        ServiceTicket ticket = createTicket(ownPoint.getId());

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = serviceTicketService.assignTicket(ticket.getId(), OUTSIDER_ID);

        assertEquals(Boolean.FALSE, result.getSuccess());
        assertEquals("No permission to assign this ticket to target assignee", result.getErrorMsg());
        assertEquals(0, serviceTicketService.getById(ticket.getId()).getStatus());
    }

    @Test
    void managerCanAssignOwnServicePointTicketToSelf() {
        ServicePoint ownPoint = createServicePoint("manager-scope-ticket-self", MANAGER_A_ID);
        ServiceTicket ticket = createTicket(ownPoint.getId());

        UserHolder.saveUser(user(MANAGER_A_ID, "manager"));
        Result result = serviceTicketService.assignTicket(ticket.getId(), MANAGER_A_ID);

        ServiceTicket updated = serviceTicketService.getById(ticket.getId());
        assertTrue(Boolean.TRUE.equals(result.getSuccess()));
        assertEquals(2, updated.getStatus());
        assertEquals(MANAGER_A_ID, updated.getAssigneeId());
    }

    private ServicePoint createServicePoint(String name, Long managerId) {
        ServicePoint point = new ServicePoint();
        point.setName(name);
        point.setCategoryId(4L);
        point.setManagerId(managerId);
        point.setArea("Acceptance");
        point.setAddress(name + " address");
        point.setX(117.11);
        point.setY(36.68);
        point.setOpenHours("09:00-18:00");
        point.setDescription("Created by manager data scope acceptance test");
        point.setStatus(1);
        point.setScore(45);
        point.setServiceCount(0);
        servicePointService.save(point);
        createdServicePointIds.add(point.getId());
        return point;
    }

    private AppointmentSlot createSlot(Long servicePointId) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setServicePointId(servicePointId);
        slot.setTitle("manager-scope-slot");
        slot.setDescription("Created by manager data scope acceptance test");
        slot.setTotalQuota(3);
        slot.setAvailableQuota(3);
        slot.setStartTime(LocalDateTime.now().plusDays(2));
        slot.setEndTime(LocalDateTime.now().plusDays(2).plusHours(1));
        slot.setStatus(1);
        appointmentSlotService.save(slot);
        createdSlotIds.add(slot.getId());
        return slot;
    }

    private ServiceTicket createTicket(Long servicePointId) {
        ServiceTicket ticket = new ServiceTicket();
        ticket.setUserId(STUDENT_ID);
        ticket.setServicePointId(servicePointId);
        ticket.setCategoryId(4L);
        ticket.setTitle("manager-scope-ticket");
        ticket.setContent("Created by manager data scope acceptance test");
        ticket.setPriority(1);
        ticket.setStatus(0);
        serviceTicketService.save(ticket);
        createdTicketIds.add(ticket.getId());
        return ticket;
    }

    private UserDTO user(Long id, String role) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setNickName("manager-scope-" + id);
        user.setRole(role);
        return user;
    }
}
