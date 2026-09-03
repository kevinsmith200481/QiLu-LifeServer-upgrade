package com.qilu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.dto.Result;
import com.qilu.entity.AiSession;
import com.qilu.mapper.AiSessionMapper;
import com.qilu.mapper.AiSessionMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionServiceImplTest {

    private AiSessionMapper mapper;
    private AiCampusAssistantService assistantService;
    private AiSessionMemoryMapper memoryMapper;
    private AiSessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        mapper = mock(AiSessionMapper.class);
        assistantService = mock(AiCampusAssistantService.class);
        memoryMapper = mock(AiSessionMemoryMapper.class);
        sessionService = new AiSessionServiceImpl();
        ReflectionTestUtils.setField(sessionService, "baseMapper", mapper);
        ReflectionTestUtils.setField(sessionService, "aiCampusAssistantService", assistantService);
        ReflectionTestUtils.setField(sessionService, "aiSessionMemoryMapper", memoryMapper);
    }

    @Test
    void deletesCheckpointBeforeSoftDeletingSession() {
        AiSession session = activeSession(17L, 2006L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(session);
        when(assistantService.deleteCheckpoint(2006L, "17")).thenReturn(true);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        Result result = sessionService.deleteCampusSession(17L, 2006L);

        assertThat(result.getSuccess()).isTrue();
        verify(assistantService).deleteCheckpoint(2006L, "17");
        verify(mapper).update(isNull(), any(Wrapper.class));
        verify(memoryMapper).delete(any(Wrapper.class));
    }

    @Test
    void keepsSessionActiveWhenCheckpointCleanupFails() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(activeSession(17L, 2006L));
        when(assistantService.deleteCheckpoint(2006L, "17")).thenReturn(false);

        Result result = sessionService.deleteCampusSession(17L, 2006L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("AI session checkpoint cleanup failed");
        verify(mapper, never()).update(isNull(), any(Wrapper.class));
        verify(memoryMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void clearsUserCheckpointsBeforeClearingSessions() {
        when(assistantService.deleteUserCheckpoints(2006L)).thenReturn(true);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        Result result = sessionService.clearMyCampusSessions(2006L);

        assertThat(result.getSuccess()).isTrue();
        verify(assistantService).deleteUserCheckpoints(2006L);
        verify(mapper).update(isNull(), any(Wrapper.class));
        verify(memoryMapper).delete(any(Wrapper.class));
    }

    @Test
    void keepsSessionListQueryAvailable() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(java.util.Collections.emptyList());

        Result result = sessionService.queryMyCampusSessions(2006L);

        assertThat(result.getSuccess()).isTrue();
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    void keepsPinnedUpdateAvailable() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(activeSession(17L, 2006L).setPinned(0));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        Result result = sessionService.updatePinned(17L, 2006L, true);

        assertThat(result.getSuccess()).isTrue();
        verify(mapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    private AiSession activeSession(Long id, Long userId) {
        return new AiSession()
                .setId(id)
                .setUserId(userId)
                .setScene("campus_assistant")
                .setStatus(1);
    }
}
