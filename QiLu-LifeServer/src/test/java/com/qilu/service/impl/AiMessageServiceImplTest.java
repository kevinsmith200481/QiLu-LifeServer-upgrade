package com.qilu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qilu.dto.Result;
import com.qilu.dto.ai.AiMessageTurn;
import com.qilu.entity.AiMessage;
import com.qilu.mapper.AiMessageMapper;
import com.qilu.service.IAiSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMessageServiceImplTest {

    private static final Long SESSION_ID = 3001L;
    private static final Long USER_ID = 2006L;

    private AiMessageMapper mapper;
    private IAiSessionService sessionService;
    private AiMessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        mapper = mock(AiMessageMapper.class);
        sessionService = mock(IAiSessionService.class);
        messageService = new AiMessageServiceImpl();
        ReflectionTestUtils.setField(messageService, "baseMapper", mapper);
        ReflectionTestUtils.setField(messageService, "aiSessionService", sessionService);
    }

    @Test
    void returnsTwentyCompleteTurnsInStableIdOrderWhenTimestampsMatch() {
        LocalDateTime sameSecond = LocalDateTime.of(2026, 7, 27, 10, 30, 0);
        List<AiMessage> candidates = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            long userMessageId = index * 2L - 1L;
            candidates.add(message(userMessageId, USER_ID, "turn-" + index, "user", "q" + index, null, sameSecond));
            candidates.add(message(userMessageId + 1L, USER_ID, "turn-" + index, "assistant", "a" + index,
                    "intent-" + index, sameSecond));
        }
        candidates.sort(Comparator.comparing(AiMessage::getId).reversed());
        when(sessionService.canAccessSession(SESSION_ID, USER_ID)).thenReturn(true);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(candidates);

        List<AiMessageTurn> turns = messageService.queryRecentCompleteTurns(SESSION_ID, USER_ID, 20);

        assertThat(turns).hasSize(20);
        assertThat(turns).extracting(turn -> turn.getUserMessage().getContent())
                .containsExactly("q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10",
                        "q11", "q12", "q13", "q14", "q15", "q16", "q17", "q18", "q19", "q20");
        assertThat(turns).extracting(AiMessageTurn::getCompletionMessageId).isSorted();

        ArgumentCaptor<Wrapper<AiMessage>> queryCaptor = wrapperCaptor();
        verify(mapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .containsIgnoringCase("ORDER BY id DESC")
                .containsIgnoringCase("limit 80");
    }

    @Test
    void discardsBoundaryFragmentsIncompleteTurnsDuplicateRolesAndForeignMessages() {
        List<AiMessage> candidates = List.of(
                message(15L, USER_ID, "turn-valid-new", "assistant", "a15", "new", null),
                message(14L, USER_ID, "turn-valid-new", "user", "q14", null, null),
                message(13L, USER_ID + 1L, "turn-foreign", "assistant", "foreign-a", null, null),
                message(12L, USER_ID + 1L, "turn-foreign", "user", "foreign-q", null, null),
                message(11L, USER_ID, null, "assistant", "orphan", null, null),
                message(10L, USER_ID, null, "assistant", "legacy-a", "legacy", null),
                message(9L, USER_ID, null, "user", "legacy-q", null, null),
                message(8L, USER_ID, null, "user", "abandoned-q", null, null),
                message(7L, USER_ID, "turn-incomplete", "user", "incomplete", null, null),
                message(6L, USER_ID, "turn-duplicate", "assistant", "duplicate-a2", null, null),
                message(5L, USER_ID, "turn-duplicate", "assistant", "duplicate-a1", null, null),
                message(4L, USER_ID, "turn-duplicate", "user", "duplicate-q", null, null),
                message(3L, USER_ID, "turn-valid-old", "assistant", "a3", "old", null),
                message(2L, USER_ID, "turn-valid-old", "user", "q2", null, null),
                message(1L, USER_ID, null, "assistant", "cut-boundary-assistant", null, null));
        when(sessionService.canAccessSession(SESSION_ID, USER_ID)).thenReturn(true);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(candidates);

        List<AiMessageTurn> turns = messageService.queryRecentCompleteTurns(SESSION_ID, USER_ID, 10);

        assertThat(turns).hasSize(3);
        assertThat(turns).extracting(turn -> turn.getUserMessage().getContent())
                .containsExactly("q2", "legacy-q", "q14");
        assertThat(turns).extracting(turn -> turn.getAssistantMessage().getContent())
                .containsExactly("a3", "legacy-a", "a15");
    }

    @Test
    void returnsOnlyLatestRequestedCompleteTurns() {
        List<AiMessage> candidates = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            candidates.add(message(index * 2L - 1L, USER_ID, "turn-" + index, "user", "q" + index, null, null));
            candidates.add(message(index * 2L, USER_ID, "turn-" + index, "assistant", "a" + index, null, null));
        }
        candidates.sort(Comparator.comparing(AiMessage::getId).reversed());
        when(sessionService.canAccessSession(SESSION_ID, USER_ID)).thenReturn(true);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(candidates);

        List<AiMessageTurn> turns = messageService.queryRecentCompleteTurns(SESSION_ID, USER_ID, 6);

        assertThat(turns).extracting(turn -> turn.getUserMessage().getContent())
                .containsExactly("q3", "q4", "q5", "q6", "q7", "q8");
    }

    @Test
    void rejectsRecentTurnQueryWithoutSessionOwnership() {
        when(sessionService.canAccessSession(SESSION_ID, USER_ID)).thenReturn(false);

        assertThat(messageService.queryRecentCompleteTurns(SESSION_ID, USER_ID, 6)).isEmpty();

        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void persistsSameTurnAndRoleOnlyOnce() {
        AiMessage existing = message(99L, USER_ID, "turn-idempotent", "user", "same question", null, null);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);
        when(mapper.insert(any(AiMessage.class))).thenReturn(1);

        AiMessage inserted = messageService.saveMessage(
                SESSION_ID, USER_ID, "user", "same question", null, null, "turn-idempotent");
        AiMessage replayed = messageService.saveMessage(
                SESSION_ID, USER_ID, "user", "same question", null, null, "turn-idempotent");

        assertThat(inserted.getTurnId()).isEqualTo("turn-idempotent");
        assertThat(replayed).isSameAs(existing);
        verify(mapper, times(1)).insert(any(AiMessage.class));
    }

    @Test
    void convergesToExistingMessageAfterConcurrentUniqueKeyConflict() {
        AiMessage winner = message(100L, USER_ID, "turn-race", "assistant", "answer", "intent", null);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, winner);
        when(mapper.insert(any(AiMessage.class))).thenThrow(new DuplicateKeyException("duplicate turn role"));

        AiMessage result = messageService.saveMessage(
                SESSION_ID, USER_ID, "assistant", "answer", "intent", null, "turn-race");

        assertThat(result).isSameAs(winner);
        verify(mapper).insert(any(AiMessage.class));
    }

    @Test
    void keepsSessionMessageQueryAccessibleAndDeterministicallyOrdered() {
        when(sessionService.canAccessSession(SESSION_ID, USER_ID)).thenReturn(true);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        Result result = messageService.querySessionMessages(SESSION_ID, USER_ID);

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<Wrapper<AiMessage>> queryCaptor = wrapperCaptor();
        verify(mapper).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .containsIgnoringCase("ORDER BY create_time ASC,id ASC");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Wrapper<AiMessage>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private AiMessage message(
            Long id,
            Long userId,
            String turnId,
            String role,
            String content,
            String intent,
            LocalDateTime createTime) {
        return new AiMessage()
                .setId(id)
                .setSessionId(SESSION_ID)
                .setTurnId(turnId)
                .setUserId(userId)
                .setRole(role)
                .setContent(content)
                .setIntent(intent)
                .setCreateTime(createTime == null ? LocalDateTime.of(2026, 7, 27, 10, 30, 0) : createTime);
    }
}
