package com.qilu.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.qilu.ai.acceptance.AcceptanceFaultInjector;
import com.qilu.ai.config.AiTelemetry;
import com.qilu.ai.api.dto.CampusAppointmentDTO;
import com.qilu.ai.api.dto.CampusAssistantRequest;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusMemoryDTO;
import com.qilu.ai.api.dto.CampusMemoryDiagnosticsDTO;
import com.qilu.ai.api.dto.CampusMemoryEntitiesDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryRequestDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryResponseDTO;
import com.qilu.ai.api.dto.CampusServicePointDTO;
import com.qilu.ai.api.dto.CampusTicketDTO;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.ai.api.error.AiFailureCode;
import com.qilu.ai.agent.AiAgentCallException;
import com.qilu.ai.agent.AiAgentEndpointRegistry;
import com.qilu.ai.agent.AiCheckpointClient;
import com.qilu.ai.agent.AiMemorySummaryClient;
import com.qilu.ai.governance.AiCallPermit;
import com.qilu.ai.governance.AiGovernanceManager;
import com.qilu.ai.governance.AiTokenCost;
import com.qilu.ai.metrics.AiProviderMetrics;
import gamer.springboot.starter.annotation.RpcService;
import gamer.context.RpcInvocationContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RpcService(interfaceClass = AiCampusAssistantService.class)
public class AiCampusAssistantServiceImpl implements AiCampusAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiCampusAssistantServiceImpl.class);
    private static final int MAX_RECOMMENDED_POINTS = 3;
    private static final int MAX_MEMORY_JSON_CHARS = 64 * 1024;

    private final AiProviderMetrics metrics;
    private final AiGovernanceManager governanceManager;
    private final AcceptanceFaultInjector acceptanceFaultInjector;
    private final RestTemplate agentRestTemplate;
    private final AiAgentEndpointRegistry endpointRegistry;
    private final AiCheckpointClient checkpointClient;
    private final AiMemorySummaryClient memorySummaryClient;

    public AiCampusAssistantServiceImpl(AiProviderMetrics metrics,
                                        AiGovernanceManager governanceManager,
                                        AcceptanceFaultInjector acceptanceFaultInjector,
                                        @Qualifier("aiAgentRestTemplate") RestTemplate agentRestTemplate,
                                        AiAgentEndpointRegistry endpointRegistry,
                                        AiCheckpointClient checkpointClient,
                                        AiMemorySummaryClient memorySummaryClient) {
        this.metrics = metrics;
        this.governanceManager = governanceManager;
        this.acceptanceFaultInjector = acceptanceFaultInjector;
        this.agentRestTemplate = agentRestTemplate;
        this.endpointRegistry = endpointRegistry;
        this.checkpointClient = checkpointClient;
        this.memorySummaryClient = memorySummaryClient;
    }

    @Override
    public boolean deleteCheckpoint(Long userId, String conversationId) {
        return checkpointClient.deleteThread(userId, conversationId);
    }

    @Override
    public boolean deleteUserCheckpoints(Long userId) {
        return checkpointClient.deleteUser(userId);
    }

    @Override
    public CampusMemorySummaryResponseDTO summarizeMemory(CampusMemorySummaryRequestDTO request) {
        try {
            CampusMemorySummaryResponseDTO response = memorySummaryClient.summarize(request);
            return response == null ? failedSummary("SUMMARY_INVALID_RESPONSE") : response;
        } catch (ResourceAccessException error) {
            return failedSummary(classifyResourceFailure(error) == AiFailureCode.AGENT_READ_TIMEOUT
                    ? "SUMMARY_TIMEOUT" : "SUMMARY_UNAVAILABLE");
        } catch (RestClientException error) {
            return failedSummary("SUMMARY_INVALID_RESPONSE");
        } catch (RuntimeException error) {
            return failedSummary("SUMMARY_UNAVAILABLE");
        }
    }

    private CampusMemorySummaryResponseDTO failedSummary(String errorCode) {
        CampusMemorySummaryResponseDTO response = new CampusMemorySummaryResponseDTO();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        return response;
    }

    @Override
    public CampusAssistantResponse chat(CampusAssistantRequest request) {
        acceptanceFaultInjector.beforeProviderBusiness();
        String operation = "campus.chat";
        long start = System.currentTimeMillis();
        String traceId = request == null ? null : request.getTraceId();
        // RPC server span 已经是当前上下文，Provider span 必须成为它的直接子节点。
        Span span = AiTelemetry.startSpan("qilu.ai.provider.chat", null);
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("ai.trace_id", StrUtil.blankToDefault(traceId, ""));
            span.setAttribute("ai.operation", operation);
            AiCallPermit permit = governanceManager.tryAcquire(operation);
            if (!permit.isAllowed()) {
                AiFailureCode failure = AiFailureCode.fromCode(permit.getReason());
                markFailure(span, failure);
                log.warn("AI provider rejected request, traceId={}, stage={}, errorCode={}",
                        traceId, failure.getStage(), failure.name());
                metrics.recordRejected(operation, failure.name());
                return buildFallbackResponse(request, failure);
            }
            log.info("AI provider chat start, traceId={}", traceId);
            CampusAssistantResponse agentResponse = callPythonAgent(request);
            if (agentResponse != null) {
                agentResponse.setTraceId(traceId);
                span.setAttribute("ai.intent", StrUtil.blankToDefault(agentResponse.getIntent(), ""));
                span.setAttribute("ai.fallback_reason", StrUtil.blankToDefault(agentResponse.getFallbackReason(), ""));
                span.setAttribute("ai.error_code", StrUtil.blankToDefault(agentResponse.getErrorCode(), ""));
                span.setAttribute("ai.error_stage", StrUtil.blankToDefault(agentResponse.getErrorStage(), ""));
                if (StrUtil.isBlank(agentResponse.getServiceStage())) {
                    agentResponse.setServiceStage("agent");
                }
                agentResponse.setRpcAttempts(RpcInvocationContext.attempt());
                if (StrUtil.isNotBlank(agentResponse.getErrorCode())) {
                    metrics.recordFailureCode(operation, agentResponse.getErrorCode());
                }
                governanceManager.recordSuccess(operation);
                metrics.record(operation, System.currentTimeMillis() - start, true,
                        StrUtil.isNotBlank(agentResponse.getFallbackReason()), null);
                recordTokenUsage(operation, request, agentResponse);
                log.info("AI provider chat success, traceId={}, intent={}, fallbackReason={}", traceId, agentResponse.getIntent(), agentResponse.getFallbackReason());
                return agentResponse;
            }
            throw new AiAgentCallException(AiFailureCode.AGENT_INVALID_RESPONSE);
        } catch (AiAgentCallException error) {
            AiFailureCode failure = error.getFailureCode();
            markFailure(span, failure);
            governanceManager.recordFailure(operation);
            metrics.record(operation, System.currentTimeMillis() - start, false, true, error);
            metrics.recordFailureCode(operation, failure.name());
            log.warn("AI provider chat fallback, traceId={}, stage={}, errorCode={}",
                    traceId, failure.getStage(), failure.name());
            return buildFallbackResponse(request, failure);
        } finally {
            span.end();
        }
    }

    private void recordTokenUsage(String operation, CampusAssistantRequest request, CampusAssistantResponse response) {
        AiTokenCost cost = governanceManager.estimateCost(JSONUtil.toJsonStr(request), response == null ? null : response.getAnswer());
        metrics.recordTokenUsage(operation, cost.getInputTokens(), cost.getOutputTokens(), cost.getEstimatedCostUsd());
    }

    private CampusAssistantResponse callPythonAgent(CampusAssistantRequest request) {
        try {
            if (request != null && request.getMemory() != null
                    && JSONUtil.toJsonStr(request.getMemory()).length() > MAX_MEMORY_JSON_CHARS) {
                throw new AiAgentCallException(AiFailureCode.MEMORY_PAYLOAD_TOO_LARGE);
            }
            HttpHeaders headers = new HttpHeaders();
            AiTelemetry.inject(headers);
            if (request != null) {
                request.setTraceParent(AiTelemetry.currentTraceParent());
            }
            CampusAssistantResponse response = agentRestTemplate.postForObject(
                    endpointRegistry.baseUrls().get(0) + "/agent/chat",
                    new HttpEntity<>(request, headers),
                    CampusAssistantResponse.class);
            if (response == null) {
                throw new AiAgentCallException(AiFailureCode.AGENT_INVALID_RESPONSE);
            }
            return response;
        } catch (AiAgentCallException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            Span.current().recordException(e);
            AiFailureCode failure = e.getStatusCode() == HttpStatus.CONFLICT
                    ? AiFailureCode.CHECKPOINT_THREAD_CONFLICT
                    : AiFailureCode.AGENT_HTTP_ERROR;
            throw new AiAgentCallException(failure, e);
        } catch (ResourceAccessException e) {
            Span.current().recordException(e);
            throw new AiAgentCallException(classifyResourceFailure(e), e);
        } catch (RestClientException e) {
            Span.current().recordException(e);
            // HTTP 已返回但无法反序列化时，必须区别于连接和读取超时。
            throw new AiAgentCallException(AiFailureCode.AGENT_INVALID_RESPONSE, e);
        }
    }

    private AiFailureCode classifyResourceFailure(ResourceAccessException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                String message = String.valueOf(current.getMessage()).toLowerCase();
                return message.contains("connect")
                        ? AiFailureCode.AGENT_CONNECT_TIMEOUT
                        : AiFailureCode.AGENT_READ_TIMEOUT;
            }
            current = current.getCause();
        }
        return AiFailureCode.AGENT_UNAVAILABLE;
    }

    private CampusAssistantResponse buildFallbackResponse(CampusAssistantRequest request, AiFailureCode failure) {
        CampusAssistantResponse response = new CampusAssistantResponse();
        String question = request == null ? null : request.getQuestion();
        String intent = detectIntent(question);
        List<CampusServicePointDTO> recommendedPoints = recommendServicePoints(intent, request);
        response.setIntent(intent);
        response.setTraceId(request == null ? null : request.getTraceId());
        response.setRecommendedServicePoints(recommendedPoints);
        response.setNeedCreateTicket(needCreateTicket(intent, recommendedPoints));
        response.setConfidence(0.35);
        response.setSources(Collections.emptyList());
        response.setBusinessCards(Collections.emptyList());
        response.setActionDrafts(Collections.emptyList());
        response.setOrchestrator("local_fallback");
        response.setLangGraphNodes(Collections.emptyList());
        response.setExecutionRecords(Collections.emptyList());
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("reason", failure.name());
        record.put("stage", failure.getStage());
        record.put("detail", Collections.singletonMap("errorType", failure.name()));
        response.setFallbackRecords(Collections.singletonList(record));
        response.setFallbackReason(failure.name());
        response.setServiceStage("provider");
        response.setErrorStage(failure.getStage());
        response.setErrorCode(failure.name());
        response.setRetriable(failure.isRetriable());
        response.setFallbackMessage(failure.getFallbackMessage());
        response.setRpcAttempts(RpcInvocationContext.attempt());
        response.setMemoryDiagnostics(fallbackMemoryDiagnostics(request, failure));
        response.setAnswer(failure.getFallbackMessage() + " "
                + buildAnswer(intent, question, recommendedPoints, request));
        return response;
    }

    /** Provider 降级只回传计数和枚举，不复制摘要、轮次正文或实体 ID。 */
    private CampusMemoryDiagnosticsDTO fallbackMemoryDiagnostics(
            CampusAssistantRequest request,
            AiFailureCode failure) {
        CampusMemoryDTO memory = request == null ? null : request.getMemory();
        if (memory == null) {
            return null;
        }
        CampusMemoryDiagnosticsDTO diagnostics = new CampusMemoryDiagnosticsDTO();
        diagnostics.setMode(memory.getMode());
        diagnostics.setSchemaVersion(memory.getSchemaVersion());
        diagnostics.setRecentTurnCount(memory.getRecentTurns() == null ? 0 : memory.getRecentTurns().size());
        diagnostics.setSummaryVersion(memory.getSummaryVersion());
        diagnostics.setEntityTypes(entityTypes(memory.getEntities()));
        diagnostics.setResolutionSource("provider_fallback");
        diagnostics.setDegraded(true);
        diagnostics.setDegradedReason(failure.name());
        return diagnostics;
    }

    private List<String> entityTypes(CampusMemoryEntitiesDTO entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        List<String> types = new ArrayList<String>();
        if (entities.getTickets() != null && !entities.getTickets().isEmpty()) {
            types.add("ticket");
        }
        if (entities.getAppointments() != null && !entities.getAppointments().isEmpty()) {
            types.add("appointment");
        }
        if (entities.getServicePoints() != null && !entities.getServicePoints().isEmpty()) {
            types.add("service_point");
        }
        return types;
    }

    private void markFailure(Span span, AiFailureCode failure) {
        span.setStatus(StatusCode.ERROR, failure.name());
        span.setAttribute("ai.error_code", failure.name());
        span.setAttribute("ai.error_stage", failure.getStage());
        span.setAttribute("ai.retriable", failure.isRetriable());
    }

    private String detectIntent(String question) {
        if (StrUtil.isBlank(question)) {
            return "general";
        }
        String text = question.toLowerCase();
        if (containsAny(text, "repair", "fix", "broken", "leak", "维修", "报修", "坏", "漏水")) {
            return "repair";
        }
        if (containsAny(text, "print", "printer", "打印", "复印")) {
            return "printing";
        }
        if (containsAny(text, "express", "parcel", "package", "快递", "取件")) {
            return "express";
        }
        if (containsAny(text, "career", "resume", "job", "简历", "就业")) {
            return "consultation";
        }
        if (containsAny(text, "appointment", "reservation", "预约", "预约单")) {
            return "appointment_status";
        }
        if (containsAny(text, "ticket", "status", "progress", "工单", "进度")) {
            return "ticket_status";
        }
        if (containsAny(text,
                "most comments", "comment count", "comment ranking", "which station has comments",
                "which service point has comments", "留言最多", "评论最多", "留言数", "评论数",
                "哪个网点有留言", "哪些网点有留言", "哪个服务点有留言", "哪些服务点有留言",
                "哪个网点有评论", "哪些网点有评论", "网点留言排行", "服务点留言排行")) {
            return "service_point_comment_ranking";
        }
        return "general";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<CampusServicePointDTO> recommendServicePoints(String intent, CampusAssistantRequest request) {
        List<CampusServicePointDTO> result = new ArrayList<>();
        if (request == null || request.getServicePoints() == null) {
            return result;
        }
        if ("service_point_comment_ranking".equals(intent)) {
            List<CampusServicePointDTO> points = new ArrayList<>(request.getServicePoints());
            points.sort((left, right) -> Integer.compare(safeCommentCount(right), safeCommentCount(left)));
            for (CampusServicePointDTO point : points) {
                if (result.size() >= MAX_RECOMMENDED_POINTS) {
                    break;
                }
                result.add(point);
            }
            return result;
        }
        for (CampusServicePointDTO point : request.getServicePoints()) {
            if (result.size() >= MAX_RECOMMENDED_POINTS) {
                break;
            }
            if (matchesIntent(intent, point)) {
                result.add(point);
            }
        }
        if (result.isEmpty()) {
            for (CampusServicePointDTO point : request.getServicePoints()) {
                if (result.size() >= MAX_RECOMMENDED_POINTS) {
                    break;
                }
                result.add(point);
            }
        }
        return result;
    }

    private boolean matchesIntent(String intent, CampusServicePointDTO point) {
        String text = safe(point.getName()) + " " + safe(point.getCategoryName()) + " " + safe(point.getDescription());
        text = text.toLowerCase();
        if ("repair".equals(intent)) {
            return containsAny(text, "repair", "维修");
        }
        if ("printing".equals(intent)) {
            return containsAny(text, "print", "打印");
        }
        if ("express".equals(intent)) {
            return containsAny(text, "express", "快递");
        }
        if ("consultation".equals(intent)) {
            return containsAny(text, "consult", "career", "咨询", "就业");
        }
        return false;
    }

    private boolean needCreateTicket(String intent, List<CampusServicePointDTO> recommendedPoints) {
        return "repair".equals(intent) || ("general".equals(intent) && recommendedPoints.isEmpty());
    }

    private String buildAnswer(String intent, String question, List<CampusServicePointDTO> points, CampusAssistantRequest request) {
        if ("ticket_status".equals(intent)) {
            return buildTicketStatusAnswer(request);
        }
        if ("appointment_status".equals(intent)) {
            return buildAppointmentStatusAnswer(request);
        }
        if ("service_point_comment_ranking".equals(intent)) {
            return buildServicePointCommentRankingAnswer(request);
        }
        StringBuilder answer = new StringBuilder();
        if (points.isEmpty()) {
            answer.append("暂未匹配到可用的校园服务点。你可以创建校园服务工单，由工作人员跟进处理。");
            return answer.toString();
        }
        CampusServicePointDTO point = points.get(0);
        answer.append("建议前往：").append(StrUtil.blankToDefault(point.getName(), "未命名服务点"));
        if (StrUtil.isNotBlank(point.getAddress())) {
            answer.append("，地址：").append(point.getAddress());
        }
        if (StrUtil.isNotBlank(point.getOpenHours())) {
            answer.append("，开放时间：").append(point.getOpenHours());
        }
        if (StrUtil.isNotBlank(point.getPhone())) {
            answer.append("，联系电话：").append(point.getPhone());
        }
        answer.append("。");
        if ("repair".equals(intent)) {
            answer.append("如果问题需要工作人员上门或进一步处理，请提交维修工单。");
        }
        return answer.toString();
    }

    private String buildServicePointCommentRankingAnswer(CampusAssistantRequest request) {
        if (request == null || request.getServicePoints() == null || request.getServicePoints().isEmpty()) {
            return "暂无可用服务点，无法判断哪个网点留言最多。";
        }
        List<CampusServicePointDTO> points = new ArrayList<>(request.getServicePoints());
        points.sort((left, right) -> Integer.compare(safeCommentCount(right), safeCommentCount(left)));
        List<CampusServicePointDTO> commented = new ArrayList<>();
        for (CampusServicePointDTO point : points) {
            if (safeCommentCount(point) > 0) {
                commented.add(point);
            }
        }
        if (commented.isEmpty()) {
            return "当前所有可用网点的留言数都是 0，暂时没有网点收到留言。";
        }
        CampusServicePointDTO top = commented.get(0);
        int topCount = safeCommentCount(top);
        StringBuilder ranking = new StringBuilder();
        int size = Math.min(MAX_RECOMMENDED_POINTS, commented.size());
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                ranking.append("；");
            }
            CampusServicePointDTO point = commented.get(i);
            ranking.append(StrUtil.blankToDefault(point.getName(), "未命名"))
                    .append(" ")
                    .append(safeCommentCount(point))
                    .append("条");
        }
        return "当前有留言的网点共 " + commented.size() + " 个：" + ranking + "。留言最多的是「"
                + StrUtil.blankToDefault(top.getName(), "未命名") + "」，共 " + topCount + " 条。";
    }

    private String buildTicketStatusAnswer(CampusAssistantRequest request) {
        if (request == null || request.getTickets() == null || request.getTickets().isEmpty()) {
            return "暂未查到你的最近工单。如果问题仍需处理，可以新建校园服务工单。";
        }
        CampusTicketDTO ticket = request.getTickets().get(0);
        StringBuilder answer = new StringBuilder();
        answer.append("你最近的工单是「")
                .append(StrUtil.blankToDefault(ticket.getTitle(), "未命名"))
                .append("」，当前状态：")
                .append(formatTicketStatus(ticket))
                .append("。");
        if (Integer.valueOf(1).equals(ticket.getStudentReplyRequired())) {
            answer.append("该工单需要你补充回复，请进入工单详情处理。");
        } else if (StrUtil.isNotBlank(ticket.getStudentReplyTime())) {
            answer.append("系统已记录你的最近回复时间：").append(ticket.getStudentReplyTime()).append("。");
        }
        if (StrUtil.isNotBlank(ticket.getAttachmentName())) {
            answer.append("工单附件：").append(ticket.getAttachmentName()).append("。");
        }
        return answer.toString();
    }

    private String buildAppointmentStatusAnswer(CampusAssistantRequest request) {
        if (request == null || request.getAppointments() == null || request.getAppointments().isEmpty()) {
            return "暂未查到你的最近预约。如果需要办理校园服务，可以在对应服务点选择可用时段进行预约。";
        }
        CampusAppointmentDTO appointment = request.getAppointments().get(0);
        StringBuilder answer = new StringBuilder();
        answer.append("你最近的预约是「")
                .append(StrUtil.blankToDefault(appointment.getSlotTitle(), "未命名预约"))
                .append("」，当前状态：")
                .append(formatAppointmentStatus(appointment))
                .append("。");
        if (StrUtil.isNotBlank(appointment.getServicePointName())) {
            answer.append("服务点：").append(appointment.getServicePointName()).append("。");
        }
        if (StrUtil.isNotBlank(appointment.getServicePointAddress())) {
            answer.append("地址：").append(appointment.getServicePointAddress()).append("。");
        }
        if (StrUtil.isNotBlank(appointment.getStartTime())) {
            answer.append("开始时间：").append(appointment.getStartTime()).append("。");
        }
        if (StrUtil.isNotBlank(appointment.getEndTime())) {
            answer.append("结束时间：").append(appointment.getEndTime()).append("。");
        }
        return answer.toString();
    }

    private String formatAppointmentStatus(CampusAppointmentDTO appointment) {
        if (appointment == null) {
            return "未知状态";
        }
        if (StrUtil.isNotBlank(appointment.getStatusText())) {
            return appointment.getStatusText();
        }
        Integer status = appointment.getStatus();
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "已预约";
            case 2:
                return "已取消";
            case 3:
                return "已完成";
            case 4:
                return "已过期";
            case 5:
                return "已爽约";
            default:
                return "未知状态";
        }
    }

    private String formatTicketStatus(CampusTicketDTO ticket) {
        if (ticket == null) {
            return "未知状态";
        }
        if (StrUtil.isNotBlank(ticket.getStatusText())) {
            return ticket.getStatusText();
        }
        Integer status = ticket.getStatus();
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 0:
                return "待受理";
            case 1:
                return "已受理";
            case 2:
                return "处理中";
            case 3:
                return "已完成";
            case 4:
                return "已关闭";
            case 5:
                return "已驳回";
            default:
                return "未知状态";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int safeCommentCount(CampusServicePointDTO point) {
        return point == null || point.getCommentCount() == null ? 0 : point.getCommentCount();
    }
}
