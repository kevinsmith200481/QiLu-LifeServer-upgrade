package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.dto.ai.AiMessageTurn;
import com.qilu.entity.AiMessage;

import java.util.List;

public interface IAiMessageService extends IService<AiMessage> {

    void saveMessage(Long sessionId, Long userId, String role, String content, String intent);

    void saveMessage(Long sessionId, Long userId, String role, String content, String intent, String metadata);

    AiMessage saveMessage(
            Long sessionId,
            Long userId,
            String role,
            String content,
            String intent,
            String metadata,
            String turnId);

    List<AiMessageTurn> queryRecentCompleteTurns(Long sessionId, Long userId, int maxTurns);

    Result querySessionMessages(Long sessionId, Long userId);
}
