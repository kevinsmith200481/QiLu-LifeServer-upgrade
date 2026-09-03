package com.qilu.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.common.InboxTableRouter;
import com.qilu.ai.api.dto.CampusAppointmentDTO;
import com.qilu.ai.api.dto.CampusAssistantRequest;
import com.qilu.ai.api.dto.CampusServicePointDTO;
import com.qilu.ai.api.dto.CampusTicketDTO;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.ai.api.service.AiPingService;
import com.qilu.ai.api.error.AiFailureCode;
import com.qilu.config.AiCallExecutor;
import com.qilu.config.AiFailureMapper;
import com.qilu.config.AiTelemetry;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.dto.ai.AiMemoryBuildResult;
import com.qilu.dto.ai.AiMessageTurn;
import com.qilu.dto.ai.CampusAssistantChatRequest;
import com.qilu.entity.AiMessage;
import com.qilu.entity.AiSession;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.AppointmentFailureLog;
import com.qilu.entity.InboxUserMessage;
import com.qilu.entity.OperationLog;
import com.qilu.entity.ServiceCategory;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.ServiceTicket;
import com.qilu.entity.StationComment;
import com.qilu.entity.TicketComment;
import com.qilu.entity.User;
import com.qilu.mapper.InboxMessageMapper;
import com.qilu.mapper.InboxUserMessageMapper;
import com.qilu.mapper.StationCommentMapper;
import com.qilu.mapper.TicketCommentMapper;
import com.qilu.metrics.AiMainMetrics;
import com.qilu.service.IAiMessageService;
import com.qilu.service.IAiSessionService;
import com.qilu.service.IAiSessionMemoryService;
import com.qilu.service.IAppointmentFailureLogService;
import com.qilu.service.IAppointmentOrderService;
import com.qilu.service.IAppointmentSlotService;
import com.qilu.service.IOperationLogService;
import com.qilu.service.IServiceCategoryService;
import com.qilu.service.IServicePointService;
import com.qilu.service.IServiceTicketService;
import com.qilu.service.IUserService;
import com.qilu.utils.UserHolder;
import com.qilu.vo.InboxMessageVO;
import gamer.springboot.starter.annotation.RpcReference;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);
    @RpcReference(interfaceClass = AiPingService.class)
    private AiPingService aiPingService;

    @RpcReference(interfaceClass = AiCampusAssistantService.class)
    private AiCampusAssistantService aiCampusAssistantService;

    @Resource
    private AcceptanceFaultInjector acceptanceFaultInjector;

    @Resource
    private AiCallExecutor aiCallExecutor;

    @Resource
    private AiMainMetrics aiMainMetrics;

    @Resource
    private IServicePointService servicePointService;

    @Resource
    private IServiceCategoryService serviceCategoryService;

    @Resource
    private IServiceTicketService serviceTicketService;

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Resource
    private IAppointmentSlotService appointmentSlotService;

    @Resource
    private IAppointmentFailureLogService appointmentFailureLogService;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private IUserService userService;

    @Resource
    private IAiSessionService aiSessionService;

    @Resource
    private IAiMessageService aiMessageService;

    @Resource
    private IAiSessionMemoryService aiSessionMemoryService;

    @Resource
    private StationCommentMapper stationCommentMapper;

    @Resource
    private TicketCommentMapper ticketCommentMapper;

    @Resource
    private InboxTableRouter inboxTableRouter;

    @Resource
    private InboxMessageMapper inboxMessageMapper;

    @Resource
    private InboxUserMessageMapper inboxUserMessageMapper;

    @GetMapping("/ping")
    public Result ping(@RequestParam(value = "message", defaultValue = "hello") String message) {
        return Result.ok(aiPingService.ping(message));
    }

    @PostMapping("/campus/chat")
    public Result chatWithCampusAssistant(@RequestBody CampusAssistantChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            return Result.fail("Question is required");
        }
        Long userId = currentUserId();
        Long sessionId;
        if (request.getSessionId() == null) {
            sessionId = aiSessionService.createCampusSession(userId, request.getQuestion());
        } else if (aiSessionService.canAccessSession(request.getSessionId(), userId)) {
            sessionId = request.getSessionId();
        } else {
            return Result.fail("No permission to use this AI session");
        }
        // 每个 HTTP 请求只生成一次 turnId，本轮 user/assistant 写入必须复用该标识。
        String turnId = newTurnId();
        long memoryBuildStartedNanos = System.nanoTime();
        AiMemoryBuildResult memoryBuild = userId == null
                ? null : aiSessionMemoryService.buildMemory(sessionId, userId);
        // 构建发生在主 Span 建立前，先计时再写入 Span，供阶段 F 区分 Memory 与整链路开销。
        long memoryBuildDurationMillis = (System.nanoTime() - memoryBuildStartedNanos) / 1_000_000L;
        CampusAssistantRequest aiRequest = buildCampusAssistantRequest(
                request,
                sessionId,
                turnId,
                memoryBuild);
        aiMessageService.saveMessage(sessionId, userId, "user", request.getQuestion(), null, null, turnId);
        Span span = AiTelemetry.startSpan("qilu.ai.campus_chat", null);
        try (Scope ignored = span.makeCurrent()) {
            // 开启 OTel 时对外 traceId 与 Collector 的 W3C traceId 完全一致；
            // SDK 关闭时保留本地随机标识，日志关联能力不受影响。
            String traceId = span.getSpanContext().isValid()
                    ? span.getSpanContext().getTraceId()
                    : newTraceId();
            aiRequest.setTraceId(traceId);
            span.setAttribute("ai.trace_id", traceId);
            span.setAttribute("ai.user_id", userId == null ? 0L : userId);
            span.setAttribute("ai.session_id", sessionId == null ? 0L : sessionId);
            span.setAttribute("ai.question_length", request.getQuestion().length());
            span.setAttribute("ai.memory.build_duration_ms", memoryBuildDurationMillis);
            if (aiRequest.getMemory() != null) {
                span.setAttribute("ai.memory.mode", aiRequest.getMemory().getMode());
                span.setAttribute("ai.memory.schema_version", aiRequest.getMemory().getSchemaVersion());
                span.setAttribute("ai.memory.recent_turn_count", aiRequest.getMemory().getRecentTurns().size());
                span.setAttribute("ai.memory.summary_version", aiRequest.getMemory().getSummaryVersion());
                span.setAttribute("ai.memory.estimated_tokens", aiRequest.getMemory().getEstimatedTokens());
                span.setAttribute("ai.memory.truncated", aiRequest.getMemory().getTruncated());
            }
            aiRequest.setTraceParent(AiTelemetry.currentTraceParent());
            log.info("AI campus chat start, traceId={}, userId={}, sessionId={}", traceId, userId, sessionId);
            com.qilu.ai.api.dto.CampusAssistantResponse response;
            try {
                response = aiCallExecutor.execute(() -> {
                    acceptanceFaultInjector.beforeRpcInvocation();
                    return aiCampusAssistantService.chat(aiRequest);
                });
            } catch (Exception e) {
                AiFailureCode failure = AiFailureMapper.from(e);
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, failure.name());
                span.setAttribute("ai.error_code", failure.name());
                span.setAttribute("ai.error_stage", failure.getStage());
                log.warn("AI campus chat fallback, traceId={}, stage={}, errorCode={}",
                        traceId, failure.getStage(), failure.name());
                response = buildLocalCampusAssistantResponse(aiRequest, failure);
            }
            response.setTraceId(traceId);
            if (response.getMemoryDiagnostics() == null && memoryBuild != null) {
                // 阶段 C 仅回填无正文诊断；Agent 的实体解析来源将在后续阶段给出。
                response.setMemoryDiagnostics(memoryBuild.getDiagnostics());
            }
            span.setAttribute("ai.intent", StrUtil.blankToDefault(response.getIntent(), ""));
            span.setAttribute("ai.fallback_reason", StrUtil.blankToDefault(response.getFallbackReason(), ""));
            span.setAttribute("ai.error_code", StrUtil.blankToDefault(response.getErrorCode(), ""));
            span.setAttribute("ai.error_stage", StrUtil.blankToDefault(response.getErrorStage(), ""));
            log.info("AI campus chat finished, traceId={}, serviceStage={}, errorStage={}, errorCode={}, fallbackReason={}",
                    traceId, response.getServiceStage(), response.getErrorStage(),
                    response.getErrorCode(), response.getFallbackReason());
            aiMainMetrics.record(response);
            AiMessage assistantMessage = aiMessageService.saveMessage(
                    sessionId,
                    userId,
                    "assistant",
                    response.getAnswer(),
                    response.getIntent(),
                    JSONUtil.toJsonStr(response),
                    turnId);
            aiSessionMemoryService.updateAfterAssistantMessage(
                    sessionId,
                    userId,
                    turnId,
                    assistantMessage,
                    response);
            aiSessionService.touchSession(sessionId);
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", sessionId);
            result.put("traceId", traceId);
            result.put("response", response);
            return Result.ok(result);
        } finally {
            span.end();
        }
    }

    @PostMapping("/internal/tools/query")
    public Result queryAiReadOnlyTool(
            @RequestHeader(value = "X-AI-TOOL-TOKEN", required = false) String token,
            @RequestHeader(value = "X-AI-TRACE-ID", required = false) String traceHeader,
            @RequestHeader(value = "traceparent", required = false) String traceParentHeader,
            @RequestBody Map<String, Object> request) {
        if (!validToolToken(token)) {
            return Result.fail("Invalid AI tool token");
        }
        Long userId = longValue(request.get("userId"));
        String toolName = stringValue(request.get("toolName"));
        String traceId = StrUtil.blankToDefault(traceHeader, stringValue(request.get("traceId")));
        String traceParent = StrUtil.blankToDefault(traceParentHeader, AiTelemetry.traceParentFromMap(request));
        Map<String, Object> arguments = mapValue(request.get("arguments"));
        if (userId == null || StrUtil.isBlank(toolName)) {
            return Result.fail("userId and toolName are required");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail("User not found");
        }
        boolean admin = isAdminRole(user.getRole());
        long start = System.currentTimeMillis();
        Span span = AiTelemetry.startSpan("qilu.ai.tool." + toolName, traceParent);
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("ai.trace_id", StrUtil.blankToDefault(traceId, ""));
            span.setAttribute("ai.tool_name", toolName);
            span.setAttribute("ai.user_id", userId);
            span.setAttribute("ai.user_role", StrUtil.blankToDefault(user.getRole(), ""));
            Object data = dispatchAiTool(toolName, userId, admin, arguments);
            log.info("AI tool query finished, traceId={}, toolName={}, userId={}, elapsedMs={}", traceId, toolName, userId, System.currentTimeMillis() - start);
            return Result.ok(data);
        } catch (SecurityException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "permission denied");
            log.warn("AI tool query denied, traceId={}, toolName={}, userId={}, reason={}", traceId, toolName, userId, e.getMessage());
            return Result.fail(e.getMessage());
        } finally {
            span.end();
        }
    }

    @DeleteMapping("/campus/sessions")
    public Result clearMyCampusAssistantSessions() {
        return aiSessionService.clearMyCampusSessions(currentUserId());
    }

    @GetMapping("/admin/traces")
    public Result queryAdminAiTraces(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        if (!currentUserCanManage()) {
            return Result.fail("No permission to view AI traces");
        }
        int safeCurrent = Math.max(current == null ? 1 : current, 1);
        int safePageSize = Math.max(1, Math.min(pageSize == null ? 10 : pageSize, 50));
        Page<AiMessage> page = aiMessageService.query()
                .eq("role", "assistant")
                .isNotNull("metadata")
                .orderByDesc("create_time")
                .page(new Page<>(safeCurrent, safePageSize));
        List<Map<String, Object>> records = new ArrayList<>();
        for (AiMessage message : page.getRecords()) {
            AiSession session = message.getSessionId() == null ? null : aiSessionService.getById(message.getSessionId());
            AiMessage question = queryTraceQuestion(message);
            records.add(toAiTraceRecord(message, session, question));
        }
        return Result.ok(records, page.getTotal());
    }

    @GetMapping("/admin/trace-metrics")
    public Result queryAdminAiTraceMetrics() {
        if (!currentUserCanManage()) {
            return Result.fail("No permission to view AI trace metrics");
        }
        List<AiMessage> messages = aiMessageService.query()
                .eq("role", "assistant")
                .isNotNull("metadata")
                .orderByDesc("create_time")
                .last("limit 100")
                .list();
        Map<String, Object> metrics = new LinkedHashMap<>();
        int fallbackCount = 0;
        int permissionDeniedCount = 0;
        int noSourceCount = 0;
        int sourceBackedCount = 0;
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        Map<String, Integer> intents = new LinkedHashMap<>();
        String latestTraceId = null;
        for (AiMessage message : messages) {
            JSONObject metadata = parseAiMetadata(message.getMetadata());
            String traceId = metadata == null ? null : metadata.getStr("traceId");
            if (latestTraceId == null && StrUtil.isNotBlank(traceId)) {
                latestTraceId = traceId;
            }
            String intent = StrUtil.blankToDefault(message.getIntent(), metadata == null ? null : metadata.getStr("intent"));
            if (StrUtil.isNotBlank(intent)) {
                intents.put(intent, intents.getOrDefault(intent, 0) + 1);
            }
            String fallbackReason = metadata == null ? null : metadata.getStr("fallbackReason");
            if (StrUtil.isNotBlank(fallbackReason)) {
                fallbackCount++;
            }
            if ("PERMISSION_DENIED".equals(fallbackReason)) {
                permissionDeniedCount++;
            }
            int sourceCount = jsonArraySize(metadata == null ? null : metadata.get("sources"));
            if (sourceCount > 0) {
                sourceBackedCount++;
            } else {
                noSourceCount++;
            }
            Double confidence = doubleValue(metadata == null ? null : metadata.get("confidence"));
            if (confidence != null) {
                confidenceSum += confidence;
                confidenceCount++;
            }
        }
        metrics.put("totalRecent", messages.size());
        metrics.put("fallbackCount", fallbackCount);
        metrics.put("permissionDeniedCount", permissionDeniedCount);
        metrics.put("noSourceCount", noSourceCount);
        metrics.put("sourceBackedCount", sourceBackedCount);
        metrics.put("averageConfidence", confidenceCount == 0 ? null : Math.round(confidenceSum / confidenceCount * 100.0) / 100.0);
        metrics.put("latestTraceId", latestTraceId);
        metrics.put("intents", intents);
        return Result.ok(metrics);
    }

    private Object dispatchAiTool(String toolName, Long userId, boolean admin, Map<String, Object> arguments) {
        switch (toolName) {
            case "query_service_categories":
                return queryToolServiceCategories(admin);
            case "query_service_points":
                return queryToolServicePoints(admin, arguments);
            case "query_service_point_slots":
                return queryToolServicePointSlots(admin, arguments);
            case "query_my_tickets":
                return queryToolMyTickets(userId, arguments);
            case "query_ticket_detail":
                return queryToolTicketDetail(userId, admin, arguments);
            case "query_my_appointments":
                return queryToolMyAppointments(userId, arguments);
            case "query_appointment_detail":
                return queryToolAppointmentDetail(userId, admin, arguments);
            case "query_inbox_summary":
                return queryToolInboxSummary(userId, arguments);
            case "query_station_comments":
                return queryToolStationComments(arguments);
            case "query_admin_operation_logs":
                requireAdmin(admin);
                return queryToolAdminOperationLogs(arguments);
            case "query_admin_appointment_failure_logs":
                requireAdmin(admin);
                return queryToolAdminAppointmentFailureLogs(arguments);
            default:
                throw new IllegalArgumentException("Unsupported AI tool: " + toolName);
        }
    }

    private List<Map<String, Object>> queryToolServiceCategories(boolean admin) {
        List<ServiceCategory> categories = serviceCategoryService.query()
                .eq(!admin, "status", 1)
                .orderByAsc("sort")
                .orderByAsc("id")
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServiceCategory category : categories) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", category.getId());
            item.put("name", category.getName());
            item.put("status", category.getStatus());
            item.put("sort", category.getSort());
            item.put("servicePointCount", servicePointService.query()
                    .eq("category_id", category.getId())
                    .eq(!admin, "status", 1)
                    .count());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> queryToolServicePoints(boolean admin, Map<String, Object> arguments) {
        Long id = longValue(arguments.get("id"));
        Long categoryId = longValue(arguments.get("categoryId"));
        List<ServicePoint> points = servicePointService.query()
                .eq(id != null, "id", id)
                .eq(categoryId != null, "category_id", categoryId)
                .eq(!admin, "status", 1)
                .orderByAsc("id")
                .last("limit " + limit(arguments, 20))
                .list();
        Map<Long, String> categoryNameMap = queryCategoryNameMap(points);
        Map<Long, Integer> commentCountMap = queryCommentCountMap(points);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServicePoint point : points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", point.getId());
            item.put("name", point.getName());
            item.put("categoryId", point.getCategoryId());
            item.put("categoryName", categoryNameMap.get(point.getCategoryId()));
            item.put("area", point.getArea());
            item.put("address", point.getAddress());
            item.put("openHours", point.getOpenHours());
            item.put("phone", point.getPhone());
            item.put("description", point.getDescription());
            item.put("status", point.getStatus());
            item.put("commentCount", commentCountMap.getOrDefault(point.getId(), 0));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> queryToolServicePointSlots(boolean admin, Map<String, Object> arguments) {
        Long servicePointId = longValue(arguments.get("servicePointId"));
        List<AppointmentSlot> slots = appointmentSlotService.query()
                .eq(servicePointId != null, "service_point_id", servicePointId)
                .eq(!admin, "status", 1)
                .orderByDesc("start_time")
                .last("limit " + limit(arguments, 20))
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppointmentSlot slot : slots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", slot.getId());
            item.put("servicePointId", slot.getServicePointId());
            item.put("title", slot.getTitle());
            item.put("description", slot.getDescription());
            item.put("totalQuota", slot.getTotalQuota());
            item.put("availableQuota", slot.getAvailableQuota());
            item.put("bookedCount", safeInt(slot.getTotalQuota()) - safeInt(slot.getAvailableQuota()));
            item.put("startTime", stringTime(slot.getStartTime()));
            item.put("endTime", stringTime(slot.getEndTime()));
            item.put("status", slot.getStatus());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> queryToolMyTickets(Long userId, Map<String, Object> arguments) {
        Integer status = intValue(arguments.get("status"));
        String title = stringValue(arguments.get("title"));
        List<ServiceTicket> tickets = serviceTicketService.query()
                .eq("user_id", userId)
                .eq("user_hidden", 0)
                .eq("admin_deleted", 0)
                .eq(status != null, "status", status)
                .like(StrUtil.isNotBlank(title), "title", title)
                .orderByDesc("create_time")
                .last("limit " + limit(arguments, 20))
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServiceTicket ticket : tickets) {
            result.add(ticketSummaryMap(ticket));
        }
        return result;
    }

    private Map<String, Object> queryToolTicketDetail(Long userId, boolean admin, Map<String, Object> arguments) {
        Long ticketId = longValue(arguments.get("ticketId"));
        ServiceTicket ticket = ticketId == null ? null : serviceTicketService.getById(ticketId);
        if (ticket == null || Integer.valueOf(1).equals(ticket.getAdminDeleted())) {
            throw new SecurityException("Ticket not found");
        }
        if (!admin && !Objects.equals(ticket.getUserId(), userId)) {
            throw new SecurityException("No permission to view this ticket");
        }
        Map<String, Object> result = ticketSummaryMap(ticket);
        result.put("content", ticket.getContent());
        result.put("detailAddress", ticket.getDetailAddress());
        result.put("contactPhone", ticket.getContactPhone());
        result.put("attachmentName", ticket.getAttachmentName());
        result.put("attachmentUrl", ticket.getAttachmentUrl());
        result.put("comments", ticketComments(ticket.getId()));
        return result;
    }

    private List<Map<String, Object>> queryToolMyAppointments(Long userId, Map<String, Object> arguments) {
        Integer status = intValue(arguments.get("status"));
        List<AppointmentOrder> orders = appointmentOrderService.query()
                .eq("user_id", userId)
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .last("limit " + limit(arguments, 20))
                .list();
        Map<Long, AppointmentSlot> slotMap = queryAppointmentSlotMap(orders);
        Map<Long, ServicePoint> pointMap = queryAppointmentServicePointMap(orders, slotMap);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppointmentOrder order : orders) {
            result.add(appointmentMap(order, slotMap.get(order.getSlotId()), pointMap));
        }
        return result;
    }

    private Map<String, Object> queryToolAppointmentDetail(Long userId, boolean admin, Map<String, Object> arguments) {
        Long appointmentId = longValue(arguments.get("appointmentId"));
        AppointmentOrder order = appointmentId == null ? null : appointmentOrderService.getById(appointmentId);
        if (order == null) {
            throw new SecurityException("Appointment not found");
        }
        if (!admin && !Objects.equals(order.getUserId(), userId)) {
            throw new SecurityException("No permission to view this appointment");
        }
        AppointmentSlot slot = order.getSlotId() == null ? null : appointmentSlotService.getById(order.getSlotId());
        Map<Long, ServicePoint> pointMap = new HashMap<>();
        Long pointId = resolveAppointmentServicePointId(order, slot);
        if (pointId != null) {
            ServicePoint point = servicePointService.getById(pointId);
            if (point != null) {
                pointMap.put(pointId, point);
            }
        }
        return appointmentMap(order, slot, pointMap);
    }

    private Map<String, Object> queryToolInboxSummary(Long userId, Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        String monthKey = inboxTableRouter.normalizeMonthKey(stringValue(arguments.get("monthKey")));
        String messageTable = inboxTableRouter.messageTable(monthKey);
        String userTable = inboxTableRouter.userMessageTable(monthKey);
        try {
            List<InboxUserMessage> unread = inboxUserMessageMapper.selectUnreadUserMessages(userTable, userId);
            List<InboxMessageVO> latest = inboxUserMessageMapper.selectCursorPage(
                    messageTable,
                    userTable,
                    userId,
                    null,
                    null,
                    null,
                    null,
                    limit(arguments, 5));
            result.put("unreadCount", unread.size());
            result.put("latest", latest);
        } catch (RuntimeException e) {
            result.put("unreadCount", 0);
            result.put("latest", Collections.emptyList());
            result.put("message", "Inbox table is not ready");
        }
        return result;
    }

    private List<Map<String, Object>> queryToolStationComments(Map<String, Object> arguments) {
        Long stationId = longValue(arguments.get("stationId"));
        if (stationId == null) {
            return Collections.emptyList();
        }
        List<StationComment> comments = stationCommentMapper.selectHotRootComments(stationId, 0, limit(arguments, 10));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StationComment comment : comments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", comment.getId());
            item.put("stationId", comment.getStationId());
            item.put("userId", comment.getUserId());
            item.put("content", comment.getContent());
            item.put("likeCount", comment.getLikeCount());
            item.put("replyCount", comment.getReplyCount());
            item.put("createTime", stringTime(comment.getCreateTime()));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> queryToolAdminOperationLogs(Map<String, Object> arguments) {
        List<OperationLog> logs = operationLogService.query()
                .orderByDesc("create_time")
                .last("limit " + limit(arguments, 20))
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (OperationLog log : logs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("module", log.getModule());
            item.put("operation", log.getOperation());
            item.put("userId", log.getUserId());
            item.put("userRole", log.getUserRole());
            item.put("businessType", log.getBusinessType());
            item.put("businessId", log.getBusinessId());
            item.put("success", log.getSuccess());
            item.put("createTime", stringTime(log.getCreateTime()));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> queryToolAdminAppointmentFailureLogs(Map<String, Object> arguments) {
        List<AppointmentFailureLog> logs = appointmentFailureLogService.query()
                .orderByDesc("create_time")
                .last("limit " + limit(arguments, 20))
                .list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppointmentFailureLog log : logs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("failureType", log.getFailureType());
            item.put("status", log.getStatus());
            item.put("orderId", log.getOrderId());
            item.put("userId", log.getUserId());
            item.put("slotId", log.getSlotId());
            item.put("servicePointId", log.getServicePointId());
            item.put("reason", log.getReason());
            item.put("createTime", stringTime(log.getCreateTime()));
            result.add(item);
        }
        return result;
    }

    @GetMapping("/session/list")
    public Result querySessions() {
        return aiSessionService.queryMyCampusSessions(currentUserId());
    }

    @GetMapping("/session/{id}/messages")
    public Result querySessionMessages(@PathVariable("id") Long sessionId) {
        return aiMessageService.querySessionMessages(sessionId, currentUserId());
    }

    @PutMapping("/session/{id}/pin")
    public Result updateSessionPinned(
            @PathVariable("id") Long sessionId,
            @RequestParam("pinned") Boolean pinned) {
        return aiSessionService.updatePinned(sessionId, currentUserId(), pinned);
    }

    @DeleteMapping("/session/{id}")
    public Result deleteSession(@PathVariable("id") Long sessionId) {
        return aiSessionService.deleteCampusSession(sessionId, currentUserId());
    }

    private AiMessage queryTraceQuestion(AiMessage assistantMessage) {
        if (assistantMessage == null || assistantMessage.getSessionId() == null) {
            return null;
        }
        return aiMessageService.query()
                .eq("session_id", assistantMessage.getSessionId())
                .eq("role", "user")
                .le(assistantMessage.getCreateTime() != null, "create_time", assistantMessage.getCreateTime())
                .orderByDesc("create_time")
                .last("limit 1")
                .one();
    }

    private Map<String, Object> toAiTraceRecord(AiMessage assistantMessage, AiSession session, AiMessage questionMessage) {
        JSONObject metadata = parseAiMetadata(assistantMessage.getMetadata());
        Map<String, Object> record = new LinkedHashMap<>();
        String fallbackReason = metadata == null ? null : metadata.getStr("fallbackReason");
        int sourceCount = jsonArraySize(metadata == null ? null : metadata.get("sources"));
        int businessCardCount = jsonArraySize(metadata == null ? null : metadata.get("businessCards"));
        int actionDraftCount = jsonArraySize(metadata == null ? null : metadata.get("actionDrafts"));
        record.put("messageId", assistantMessage.getId());
        record.put("sessionId", assistantMessage.getSessionId());
        record.put("sessionTitle", session == null ? null : session.getTitle());
        record.put("userId", session == null ? assistantMessage.getUserId() : session.getUserId());
        record.put("traceId", metadata == null ? null : metadata.getStr("traceId"));
        // 管理端 Trace 只展示长度和治理元数据，不复制问题、答案或工单隐私正文。
        record.put("questionLength", questionMessage == null || questionMessage.getContent() == null
                ? 0 : questionMessage.getContent().length());
        record.put("answerLength", assistantMessage.getContent() == null ? 0 : assistantMessage.getContent().length());
        record.put("intent", StrUtil.blankToDefault(assistantMessage.getIntent(), metadata == null ? null : metadata.getStr("intent")));
        record.put("confidence", doubleValue(metadata == null ? null : metadata.get("confidence")));
        record.put("orchestrator", metadata == null ? null : metadata.getStr("orchestrator"));
        record.put("langGraphNodes", jsonArrayValue(metadata == null ? null : metadata.get("langGraphNodes")));
        record.put("executionRecords", jsonArrayValue(metadata == null ? null : metadata.get("executionRecords")));
        record.put("fallbackRecords", jsonArrayValue(metadata == null ? null : metadata.get("fallbackRecords")));
        record.put("fallbackReason", fallbackReason);
        record.put("serviceStage", metadata == null ? null : metadata.getStr("serviceStage"));
        record.put("errorStage", metadata == null ? null : metadata.getStr("errorStage"));
        record.put("errorCode", metadata == null ? null : metadata.getStr("errorCode"));
        record.put("retriable", metadata == null ? null : metadata.getBool("retriable"));
        record.put("rpcAttempts", metadata == null ? null : metadata.getInt("rpcAttempts"));
        record.put("plannerMode", metadata == null ? null : metadata.getStr("plannerMode"));
        record.put("checkpoint", metadata == null ? null : metadata.get("checkpoint"));
        record.put("sourceCount", sourceCount);
        record.put("businessCardCount", businessCardCount);
        record.put("actionDraftCount", actionDraftCount);
        record.put("permissionDenied", "PERMISSION_DENIED".equals(fallbackReason));
        record.put("status", StrUtil.isBlank(fallbackReason) ? "SUCCESS" : "FALLBACK");
        record.put("createTime", stringTime(assistantMessage.getCreateTime()));
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        safeMetadata.put("traceId", metadata == null ? null : metadata.getStr("traceId"));
        safeMetadata.put("serviceStage", metadata == null ? null : metadata.getStr("serviceStage"));
        safeMetadata.put("errorStage", metadata == null ? null : metadata.getStr("errorStage"));
        safeMetadata.put("errorCode", metadata == null ? null : metadata.getStr("errorCode"));
        safeMetadata.put("fallbackReason", fallbackReason);
        safeMetadata.put("rpcAttempts", metadata == null ? null : metadata.getInt("rpcAttempts"));
        safeMetadata.put("sourceCount", sourceCount);
        safeMetadata.put("businessCardCount", businessCardCount);
        safeMetadata.put("actionDraftCount", actionDraftCount);
        record.put("rawResponse", safeMetadata);
        return record;
    }

    private JSONObject parseAiMetadata(String metadata) {
        if (StrUtil.isBlank(metadata)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(metadata);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int jsonArraySize(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        }
        try {
            return JSONUtil.parseArray(String.valueOf(value)).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private List<Object> jsonArrayValue(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        try {
            return JSONUtil.parseArray(String.valueOf(value)).toList(Object.class);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String && StrUtil.isNotBlank((String) value)) {
            return Double.valueOf((String) value);
        }
        return null;
    }

    private boolean validToolToken(String token) {
        String expected = System.getenv("AI_TOOL_TOKEN");
        return StrUtil.isNotBlank(expected) && StrUtil.isNotBlank(token) && expected.equals(token);
    }

    private boolean isAdminRole(String role) {
        return "admin".equals(role) || "manager".equals(role);
    }

    private void requireAdmin(boolean admin) {
        if (!admin) {
            throw new SecurityException("No permission to use admin AI tool");
        }
    }

    private boolean currentUserCanManage() {
        UserDTO user = UserHolder.getUser();
        return user != null && isAdminRole(user.getRole());
    }

    private Map<String, Object> ticketSummaryMap(ServiceTicket ticket) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ticket.getId());
        item.put("title", ticket.getTitle());
        item.put("status", ticket.getStatus());
        item.put("statusText", formatCampusTicketStatus(ticket.getStatus()));
        item.put("priority", ticket.getPriority());
        item.put("servicePointId", ticket.getServicePointId());
        item.put("categoryId", ticket.getCategoryId());
        item.put("studentReplyRequired", ticket.getStudentReplyRequired());
        item.put("studentReplyTime", stringTime(ticket.getStudentReplyTime()));
        item.put("attachmentName", ticket.getAttachmentName());
        item.put("attachmentUrl", ticket.getAttachmentUrl());
        item.put("createTime", stringTime(ticket.getCreateTime()));
        item.put("acceptTime", stringTime(ticket.getAcceptTime()));
        item.put("finishTime", stringTime(ticket.getFinishTime()));
        return item;
    }

    private List<Map<String, Object>> ticketComments(Long ticketId) {
        List<TicketComment> comments = ticketCommentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TicketComment>()
                        .eq("ticket_id", ticketId)
                        .orderByAsc("create_time"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (TicketComment comment : comments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", comment.getId());
            item.put("userId", comment.getUserId());
            item.put("userType", comment.getUserType());
            item.put("content", comment.getContent());
            item.put("attachmentName", comment.getAttachmentName());
            item.put("attachmentUrl", comment.getAttachmentUrl());
            item.put("createTime", stringTime(comment.getCreateTime()));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> appointmentMap(
            AppointmentOrder order,
            AppointmentSlot slot,
            Map<Long, ServicePoint> pointMap) {
        Long servicePointId = resolveAppointmentServicePointId(order, slot);
        ServicePoint point = servicePointId == null ? null : pointMap.get(servicePointId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", order.getId());
        item.put("status", order.getStatus());
        item.put("statusText", formatCampusAppointmentStatus(order.getStatus()));
        item.put("remark", order.getRemark());
        item.put("servicePointId", servicePointId);
        item.put("servicePointName", point == null ? null : point.getName());
        item.put("servicePointAddress", point == null ? null : point.getAddress());
        item.put("slotId", order.getSlotId());
        item.put("slotTitle", slot == null ? null : slot.getTitle());
        item.put("slotDescription", slot == null ? null : slot.getDescription());
        item.put("startTime", slot == null ? null : stringTime(slot.getStartTime()));
        item.put("endTime", slot == null ? null : stringTime(slot.getEndTime()));
        item.put("createTime", stringTime(order.getCreateTime()));
        item.put("cancelTime", stringTime(order.getCancelTime()));
        item.put("finishTime", stringTime(order.getFinishTime()));
        return item;
    }

    private int limit(Map<String, Object> arguments, int defaultLimit) {
        Integer value = intValue(arguments.get("limit"));
        if (value == null) {
            value = defaultLimit;
        }
        return Math.max(1, Math.min(value, 20));
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String stringTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && StrUtil.isNotBlank((String) value)) {
            return Long.valueOf((String) value);
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && StrUtil.isNotBlank((String) value)) {
            return Integer.valueOf((String) value);
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    private String newTraceId() {
        return "ai-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newTurnId() {
        return "turn-" + UUID.randomUUID().toString().replace("-", "");
    }

    private CampusAssistantRequest buildCampusAssistantRequest(
            CampusAssistantChatRequest request,
            Long sessionId,
            String turnId,
            AiMemoryBuildResult memoryBuild) {
        CampusAssistantRequest aiRequest = new CampusAssistantRequest();
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            aiRequest.setUserId(user.getId());
            aiRequest.setRole(user.getRole());
            aiRequest.setTickets(queryRecentTickets(user.getId()));
            aiRequest.setAppointments(queryRecentAppointments(user.getId()));
        }
        aiRequest.setConversationId(sessionId == null ? null : String.valueOf(sessionId));
        aiRequest.setTurnId(turnId);
        aiRequest.setHistory(queryRecentMemoryHistory(sessionId));
        aiRequest.setLastBusinessContext(request.getLastBusinessContext());
        aiRequest.setMemory(memoryBuild == null ? null : memoryBuild.getMemory());
        aiRequest.setQuestion(request.getQuestion());
        aiRequest.setServicePoints(queryCampusServicePoints(request.getCategoryId()));
        return aiRequest;
    }

    private List<Map<String, Object>> queryRecentMemoryHistory(Long sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        List<AiMessageTurn> completeTurns = aiMessageService.queryRecentCompleteTurns(
                sessionId,
                currentUserId(),
                6);
        List<Map<String, Object>> turns = new ArrayList<>();
        for (AiMessageTurn completeTurn : completeTurns) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("question", completeTurn.getUserMessage().getContent());
            turn.put("answer", completeTurn.getAssistantMessage().getContent());
            turn.put("intent", completeTurn.getAssistantMessage().getIntent());
            JSONObject metadata = parseAiMetadata(completeTurn.getAssistantMessage().getMetadata());
            if (metadata != null) {
                turn.put("sources", metadata.get("sources"));
                turn.put("businessCards", metadata.get("businessCards"));
                turn.put("actionDrafts", metadata.get("actionDrafts"));
            }
            turns.add(turn);
        }
        return turns;
    }

    private List<CampusServicePointDTO> queryCampusServicePoints(Long categoryId) {
        List<ServicePoint> points = servicePointService.query()
                .eq(categoryId != null, "category_id", categoryId)
                .eq("status", 1)
                .orderByAsc("id")
                .list();
        Map<Long, String> categoryNameMap = queryCategoryNameMap(points);
        Map<Long, Integer> commentCountMap = queryCommentCountMap(points);
        return points.stream()
                .map(point -> toCampusServicePointDTO(point, categoryNameMap.get(point.getCategoryId()), commentCountMap.get(point.getId())))
                .collect(Collectors.toList());
    }

    private Map<Long, String> queryCategoryNameMap(List<ServicePoint> points) {
        List<Long> categoryIds = points.stream()
                .map(ServicePoint::getCategoryId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (categoryIds.isEmpty()) {
            return new HashMap<>();
        }
        return serviceCategoryService.listByIds(categoryIds).stream()
                .collect(Collectors.toMap(ServiceCategory::getId, ServiceCategory::getName, (left, right) -> left));
    }

    private Map<Long, Integer> queryCommentCountMap(List<ServicePoint> points) {
        List<Long> pointIds = points.stream()
                .map(ServicePoint::getId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        if (pointIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<Long, Integer> countMap = new HashMap<>();
        for (Map<String, Object> row : stationCommentMapper.selectCommentCountsByStationIds(pointIds)) {
            Object stationId = row.get("stationId");
            Object commentCount = row.get("commentCount");
            if (stationId == null) {
                stationId = row.get("station_id");
            }
            if (commentCount == null) {
                commentCount = row.get("comment_count");
            }
            if (stationId instanceof Number && commentCount instanceof Number) {
                countMap.put(((Number) stationId).longValue(), ((Number) commentCount).intValue());
            }
        }
        return countMap;
    }

    private List<CampusTicketDTO> queryRecentTickets(Long userId) {
        List<ServiceTicket> tickets = serviceTicketService.query()
                .eq("user_id", userId)
                .eq("user_hidden", 0)
                .eq("admin_deleted", 0)
                .orderByDesc("create_time")
                .last("limit 5")
                .list();
        return tickets.stream().map(this::toCampusTicketDTO).collect(Collectors.toList());
    }

    private List<CampusAppointmentDTO> queryRecentAppointments(Long userId) {
        List<AppointmentOrder> orders = appointmentOrderService.query()
                .eq("user_id", userId)
                .eq("status", 1)
                .orderByDesc("create_time")
                .last("limit 5")
                .list();
        Map<Long, AppointmentSlot> slotMap = queryAppointmentSlotMap(orders);
        Map<Long, ServicePoint> pointMap = queryAppointmentServicePointMap(orders, slotMap);
        return orders.stream()
                .map(order -> toCampusAppointmentDTO(order, slotMap.get(order.getSlotId()), pointMap))
                .collect(Collectors.toList());
    }

    private Map<Long, AppointmentSlot> queryAppointmentSlotMap(List<AppointmentOrder> orders) {
        List<Long> slotIds = orders.stream()
                .map(AppointmentOrder::getSlotId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (slotIds.isEmpty()) {
            return new HashMap<>();
        }
        return appointmentSlotService.listByIds(slotIds).stream()
                .collect(Collectors.toMap(AppointmentSlot::getId, slot -> slot, (left, right) -> left));
    }

    private Map<Long, ServicePoint> queryAppointmentServicePointMap(List<AppointmentOrder> orders, Map<Long, AppointmentSlot> slotMap) {
        List<Long> pointIds = orders.stream()
                .map(order -> resolveAppointmentServicePointId(order, slotMap.get(order.getSlotId())))
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (pointIds.isEmpty()) {
            return new HashMap<>();
        }
        return servicePointService.listByIds(pointIds).stream()
                .collect(Collectors.toMap(ServicePoint::getId, point -> point, (left, right) -> left));
    }

    private CampusServicePointDTO toCampusServicePointDTO(ServicePoint point, String categoryName, Integer commentCount) {
        CampusServicePointDTO dto = new CampusServicePointDTO();
        dto.setId(point.getId());
        dto.setName(point.getName());
        dto.setCategoryName(categoryName);
        dto.setArea(point.getArea());
        dto.setAddress(point.getAddress());
        dto.setOpenHours(point.getOpenHours());
        dto.setPhone(point.getPhone());
        dto.setDescription(point.getDescription());
        dto.setCommentCount(commentCount == null ? 0 : commentCount);
        return dto;
    }

    private CampusAppointmentDTO toCampusAppointmentDTO(
            AppointmentOrder order,
            AppointmentSlot slot,
            Map<Long, ServicePoint> pointMap) {
        Long servicePointId = resolveAppointmentServicePointId(order, slot);
        ServicePoint point = servicePointId == null ? null : pointMap.get(servicePointId);
        CampusAppointmentDTO dto = new CampusAppointmentDTO();
        dto.setId(order.getId());
        dto.setServicePointId(servicePointId);
        dto.setServicePointName(point == null ? null : point.getName());
        dto.setServicePointAddress(point == null ? null : point.getAddress());
        dto.setSlotTitle(slot == null ? null : slot.getTitle());
        dto.setSlotDescription(slot == null ? null : slot.getDescription());
        dto.setStartTime(slot == null || slot.getStartTime() == null ? null : slot.getStartTime().toString());
        dto.setEndTime(slot == null || slot.getEndTime() == null ? null : slot.getEndTime().toString());
        dto.setStatus(order.getStatus());
        dto.setStatusText(formatCampusAppointmentStatus(order.getStatus()));
        dto.setRemark(order.getRemark());
        dto.setCreateTime(order.getCreateTime() == null ? null : order.getCreateTime().toString());
        dto.setCancelTime(order.getCancelTime() == null ? null : order.getCancelTime().toString());
        dto.setFinishTime(order.getFinishTime() == null ? null : order.getFinishTime().toString());
        return dto;
    }

    private Long resolveAppointmentServicePointId(AppointmentOrder order, AppointmentSlot slot) {
        if (order.getServicePointId() != null) {
            return order.getServicePointId();
        }
        return slot == null ? null : slot.getServicePointId();
    }

    private CampusTicketDTO toCampusTicketDTO(ServiceTicket ticket) {
        CampusTicketDTO dto = new CampusTicketDTO();
        dto.setId(ticket.getId());
        dto.setTitle(ticket.getTitle());
        dto.setContent(ticket.getContent());
        dto.setStatus(ticket.getStatus());
        dto.setStatusText(formatCampusTicketStatus(ticket.getStatus()));
        dto.setPriority(ticket.getPriority());
        dto.setStudentReplyRequired(ticket.getStudentReplyRequired());
        dto.setStudentReplyTime(ticket.getStudentReplyTime() == null ? null : ticket.getStudentReplyTime().toString());
        dto.setAttachmentName(ticket.getAttachmentName());
        dto.setAttachmentUrl(ticket.getAttachmentUrl());
        return dto;
    }

    private String formatCampusAppointmentStatus(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        switch (status) {
            case 1:
                return "RESERVED";
            case 2:
                return "CANCELLED";
            case 3:
                return "FINISHED";
            case 4:
                return "EXPIRED";
            case 5:
                return "NO_SHOW";
            default:
                return "UNKNOWN";
        }
    }

    private String formatCampusTicketStatus(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        switch (status) {
            case 0:
                return "PENDING";
            case 1:
                return "ACCEPTED";
            case 2:
                return "PROCESSING";
            case 3:
                return "FINISHED";
            case 4:
                return "CLOSED";
            case 5:
                return "REJECTED";
            default:
                return "UNKNOWN";
        }
    }

    private com.qilu.ai.api.dto.CampusAssistantResponse buildLocalCampusAssistantResponse(
            CampusAssistantRequest request,
            AiFailureCode failure) {
        com.qilu.ai.api.dto.CampusAssistantResponse response = new com.qilu.ai.api.dto.CampusAssistantResponse();
        response.setIntent("fallback");
        response.setTraceId(request.getTraceId());
        response.setRecommendedServicePoints(request.getServicePoints());
        response.setNeedCreateTicket(true);
        response.setConfidence(0.2);
        response.setServiceStage("main");
        response.setErrorStage(failure.getStage());
        response.setErrorCode(failure.name());
        response.setRetriable(failure.isRetriable());
        response.setFallbackMessage(failure.getFallbackMessage());
        response.setFallbackReason(failure.name());
        response.setSources(Collections.emptyList());
        response.setBusinessCards(Collections.emptyList());
        response.setActionDrafts(Collections.emptyList());
        response.setOrchestrator("local_fallback");
        response.setLangGraphNodes(Collections.emptyList());
        response.setExecutionRecords(Collections.emptyList());
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("reason", failure.name());
        fallback.put("stage", failure.getStage());
        fallback.put("detail", Collections.singletonMap("errorType", failure.name()));
        response.setFallbackRecords(Collections.singletonList(fallback));
        response.setAnswer(failure.getFallbackMessage());
        return response;
    }

}
