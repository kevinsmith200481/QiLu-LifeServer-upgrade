package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.AiSession;

public interface IAiSessionService extends IService<AiSession> {

    Long createCampusSession(Long userId, String question);

    Result queryMyCampusSessions(Long userId);

    boolean canAccessSession(Long sessionId, Long userId);

    void touchSession(Long sessionId);

    Result updatePinned(Long sessionId, Long userId, Boolean pinned);

    Result deleteCampusSession(Long sessionId, Long userId);

    Result clearMyCampusSessions(Long userId);
}
