package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.dto.ai.AiMessageTurn;
import com.qilu.entity.AiMessage;
import com.qilu.mapper.AiMessageMapper;
import com.qilu.service.IAiSessionService;
import com.qilu.service.IAiMessageService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage> implements IAiMessageService {

    private static final int MIN_CANDIDATE_MESSAGES = 24;
    private static final int MAX_CANDIDATE_MESSAGES = 400;
    private static final int CANDIDATE_MESSAGES_PER_TURN = 4;
    private static final int MAX_RECENT_TURNS = 100;

    @Resource
    private IAiSessionService aiSessionService;

    @Override
    public void saveMessage(Long sessionId, Long userId, String role, String content, String intent) {
        saveMessage(sessionId, userId, role, content, intent, null);
    }

    @Override
    public void saveMessage(Long sessionId, Long userId, String role, String content, String intent, String metadata) {
        saveMessage(sessionId, userId, role, content, intent, metadata, null);
    }

    @Override
    public AiMessage saveMessage(
            Long sessionId,
            Long userId,
            String role,
            String content,
            String intent,
            String metadata,
            String turnId) {
        String normalizedTurnId = StrUtil.blankToDefault(turnId, null);
        if (normalizedTurnId != null) {
            AiMessage existing = queryByTurnAndRole(sessionId, normalizedTurnId, role);
            if (existing != null) {
                return existing;
            }
        }

        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setTurnId(normalizedTurnId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setIntent(intent);
        message.setMetadata(metadata);
        message.setCreateTime(LocalDateTime.now());
        try {
            save(message);
            return message;
        } catch (DuplicateKeyException duplicate) {
            // 并发请求可能同时通过首次查询；唯一键冲突后读取胜出的记录，实现幂等收敛。
            AiMessage existing = queryByTurnAndRole(sessionId, normalizedTurnId, role);
            if (existing != null) {
                return existing;
            }
            throw duplicate;
        }
    }

    @Override
    public List<AiMessageTurn> queryRecentCompleteTurns(Long sessionId, Long userId, int maxTurns) {
        if (sessionId == null || userId == null || maxTurns <= 0
                || !aiSessionService.canAccessSession(sessionId, userId)) {
            return new ArrayList<>();
        }
        int turnLimit = Math.min(maxTurns, MAX_RECENT_TURNS);
        int candidateLimit = Math.min(
                MAX_CANDIDATE_MESSAGES,
                Math.max(MIN_CANDIDATE_MESSAGES, turnLimit * CANDIDATE_MESSAGES_PER_TURN));

        // 候选集由数据库按主键倒序并限量，确保长会话不会被完整加载到 JVM。
        List<AiMessage> candidates = query()
                .eq("session_id", sessionId)
                .orderByDesc("id")
                .last("limit " + candidateLimit)
                .list();
        List<AiMessage> ownedCandidates = candidates.stream()
                .filter(message -> message.getUserId() == null || Objects.equals(userId, message.getUserId()))
                .collect(Collectors.toList());

        List<AiMessageTurn> completeTurns = new ArrayList<>();
        completeTurns.addAll(pairTurnsWithId(ownedCandidates));
        completeTurns.addAll(pairLegacyTurns(ownedCandidates));
        completeTurns.sort(Comparator.comparing(AiMessageTurn::getCompletionMessageId));

        int fromIndex = Math.max(0, completeTurns.size() - turnLimit);
        return new ArrayList<>(completeTurns.subList(fromIndex, completeTurns.size()));
    }

    @Override
    public Result querySessionMessages(Long sessionId, Long userId) {
        if (!aiSessionService.canAccessSession(sessionId, userId)) {
            return Result.fail("No permission to view this AI session");
        }
        return Result.ok(query()
                .eq("session_id", sessionId)
                .orderByAsc("create_time")
                .orderByAsc("id")
                .list());
    }

    private AiMessage queryByTurnAndRole(Long sessionId, String turnId, String role) {
        if (sessionId == null || turnId == null || StrUtil.isBlank(role)) {
            return null;
        }
        return query()
                .eq("session_id", sessionId)
                .eq("turn_id", turnId)
                .eq("role", role)
                .one();
    }

    private List<AiMessageTurn> pairTurnsWithId(List<AiMessage> candidates) {
        Map<String, TurnAccumulator> turnsById = new HashMap<>();
        for (AiMessage message : candidates) {
            if (StrUtil.isBlank(message.getTurnId())) {
                continue;
            }
            TurnAccumulator turn = turnsById.computeIfAbsent(message.getTurnId(), key -> new TurnAccumulator());
            turn.accept(message);
        }
        return turnsById.values().stream()
                .filter(TurnAccumulator::isComplete)
                .map(turn -> new AiMessageTurn(turn.userMessage, turn.assistantMessage))
                .collect(Collectors.toList());
    }

    private List<AiMessageTurn> pairLegacyTurns(List<AiMessage> candidates) {
        List<AiMessage> legacyMessages = candidates.stream()
                .filter(message -> StrUtil.isBlank(message.getTurnId()))
                .sorted(Comparator.comparing(AiMessage::getId))
                .collect(Collectors.toList());
        List<AiMessageTurn> completeTurns = new ArrayList<>();
        AiMessage pendingUser = null;
        for (AiMessage message : legacyMessages) {
            if ("user".equals(message.getRole())) {
                // 连续 user 只保留较新的一个，旧的未完成问题不能与后续 assistant 错配。
                pendingUser = message;
            } else if ("assistant".equals(message.getRole()) && pendingUser != null) {
                completeTurns.add(new AiMessageTurn(pendingUser, message));
                pendingUser = null;
            }
        }
        return completeTurns;
    }

    private static class TurnAccumulator {

        private AiMessage userMessage;
        private AiMessage assistantMessage;
        private boolean duplicateRole;

        private void accept(AiMessage message) {
            if ("user".equals(message.getRole())) {
                duplicateRole = duplicateRole || userMessage != null;
                userMessage = userMessage == null ? message : userMessage;
            } else if ("assistant".equals(message.getRole())) {
                duplicateRole = duplicateRole || assistantMessage != null;
                assistantMessage = assistantMessage == null ? message : assistantMessage;
            }
        }

        private boolean isComplete() {
            return !duplicateRole
                    && userMessage != null
                    && assistantMessage != null
                    && userMessage.getId() < assistantMessage.getId();
        }
    }
}
