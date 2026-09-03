package com.qilu.controller;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.qilu.acceptance.AcceptanceFaultInjector;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.config.AiCallExecutor;
import com.qilu.dto.Result;
import com.qilu.dto.ai.CampusAssistantChatRequest;
import com.qilu.entity.ServicePoint;
import com.qilu.metrics.AiMainMetrics;
import com.qilu.service.IAiMessageService;
import com.qilu.service.IAiSessionService;
import com.qilu.service.IAiSessionMemoryService;
import com.qilu.service.IServicePointService;
import com.qilu.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerMemoryTurnTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void reusesOneTurnIdForUserAndAssistantAfterHistoryIsBuilt() throws Exception {
        AiController controller = new AiController();
        IAiSessionService sessionService = mock(IAiSessionService.class);
        IAiMessageService messageService = mock(IAiMessageService.class);
        IAiSessionMemoryService memoryService = mock(IAiSessionMemoryService.class);
        IServicePointService pointService = mock(IServicePointService.class);
        @SuppressWarnings("unchecked")
        QueryChainWrapper<ServicePoint> pointQuery = mock(QueryChainWrapper.class);
        AiCampusAssistantService assistantService = mock(AiCampusAssistantService.class);
        AcceptanceFaultInjector faultInjector = mock(AcceptanceFaultInjector.class);
        AiCallExecutor callExecutor = mock(AiCallExecutor.class);
        AiMainMetrics metrics = mock(AiMainMetrics.class);

        ReflectionTestUtils.setField(controller, "aiSessionService", sessionService);
        ReflectionTestUtils.setField(controller, "aiMessageService", messageService);
        ReflectionTestUtils.setField(controller, "aiSessionMemoryService", memoryService);
        ReflectionTestUtils.setField(controller, "servicePointService", pointService);
        ReflectionTestUtils.setField(controller, "aiCampusAssistantService", assistantService);
        ReflectionTestUtils.setField(controller, "acceptanceFaultInjector", faultInjector);
        ReflectionTestUtils.setField(controller, "aiCallExecutor", callExecutor);
        ReflectionTestUtils.setField(controller, "aiMainMetrics", metrics);

        when(sessionService.createCampusSession(isNull(), any())).thenReturn(3001L);
        when(pointService.query()).thenReturn(pointQuery);
        when(pointQuery.eq(false, "category_id", null)).thenReturn(pointQuery);
        when(pointQuery.eq("status", 1)).thenReturn(pointQuery);
        when(pointQuery.orderByAsc("id")).thenReturn(pointQuery);
        when(pointQuery.list()).thenReturn(Collections.emptyList());
        when(messageService.queryRecentCompleteTurns(anyLong(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(callExecutor.execute(any())).thenAnswer(invocation -> {
            Callable<?> task = invocation.getArgument(0);
            return task.call();
        });
        CampusAssistantResponse response = new CampusAssistantResponse();
        response.setAnswer("answer");
        response.setIntent("campus_service");
        response.setServiceStage("main");
        when(assistantService.chat(any())).thenReturn(response);

        CampusAssistantChatRequest request = new CampusAssistantChatRequest();
        request.setQuestion("question");
        Result result = controller.chatWithCampusAssistant(request);

        assertThat(result.getSuccess()).isTrue();
        InOrder persistenceOrder = inOrder(messageService);
        persistenceOrder.verify(messageService).queryRecentCompleteTurns(3001L, null, 6);
        persistenceOrder.verify(messageService).saveMessage(
                eq(3001L),
                isNull(),
                eq("user"),
                eq("question"),
                isNull(),
                isNull(),
                any());

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> turnIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService, times(2)).saveMessage(
                anyLong(),
                isNull(),
                roleCaptor.capture(),
                any(),
                nullable(String.class),
                nullable(String.class),
                turnIdCaptor.capture());
        assertThat(roleCaptor.getAllValues()).containsExactly("user", "assistant");
        assertThat(turnIdCaptor.getAllValues()).hasSize(2).allMatch(value -> value.startsWith("turn-"));
        assertThat(turnIdCaptor.getAllValues().get(0)).isEqualTo(turnIdCaptor.getAllValues().get(1));
    }
}
