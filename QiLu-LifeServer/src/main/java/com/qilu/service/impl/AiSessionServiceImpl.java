package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.entity.AiSession;
import com.qilu.entity.AiSessionMemory;
import com.qilu.mapper.AiSessionMapper;
import com.qilu.mapper.AiSessionMemoryMapper;
import com.qilu.service.IAiSessionService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import gamer.springboot.starter.annotation.RpcReference;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;

@Service
public class AiSessionServiceImpl extends ServiceImpl<AiSessionMapper, AiSession> implements IAiSessionService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionServiceImpl.class);

    @RpcReference(interfaceClass = AiCampusAssistantService.class)
    private AiCampusAssistantService aiCampusAssistantService;

    @Resource
    private AiSessionMemoryMapper aiSessionMemoryMapper;

    @Override
    public Long createCampusSession(Long userId, String question) {
        AiSession session = new AiSession();
        session.setUserId(userId);
        session.setTitle(buildTitle(question));
        session.setScene("campus_assistant");
        session.setPinned(0);
        session.setStatus(1);
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());
        save(session);
        return session.getId();
    }

    @Override
    public Result queryMyCampusSessions(Long userId) {
        return Result.ok(query()
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .eq("status", 1)
                .orderByDesc("pinned")
                .orderByDesc("update_time")
                .orderByDesc("create_time")
                .last("limit 20")
                .list());
    }

    @Override
    public boolean canAccessSession(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return false;
        }
        return query()
                .eq("id", sessionId)
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .eq("status", 1)
                .count() > 0;
    }

    @Override
    public void touchSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        update().eq("id", sessionId).set("update_time", LocalDateTime.now()).update();
    }

    @Override
    public Result updatePinned(Long sessionId, Long userId, Boolean pinned) {
        AiSession session = queryOwnedCampusSession(sessionId, userId);
        if (session == null || !Integer.valueOf(1).equals(session.getStatus())) {
            return Result.fail("No permission to update this AI session");
        }
        int nextPinned = Boolean.TRUE.equals(pinned) ? 1 : 0;
        if (Integer.valueOf(nextPinned).equals(session.getPinned())) {
            return Result.ok();
        }
        boolean success = update()
                .eq("id", sessionId)
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .eq("status", 1)
                .set("pinned", nextPinned)
                .update();
        if (success) {
            return Result.ok();
        }
        AiSession latest = queryOwnedCampusSession(sessionId, userId);
        return latest != null && Integer.valueOf(nextPinned).equals(latest.getPinned())
                ? Result.ok()
                : Result.fail("Update AI session failed");
    }

    @Override
    @Transactional
    public Result deleteCampusSession(Long sessionId, Long userId) {
        AiSession session = queryOwnedCampusSession(sessionId, userId);
        if (session == null) {
            return Result.fail("No permission to delete this AI session");
        }
        if (!Integer.valueOf(1).equals(session.getStatus())) {
            deleteSessionMemory(sessionId, userId);
            return Result.ok();
        }
        if (!deleteCheckpoint(userId, String.valueOf(sessionId))) {
            return Result.fail("AI session checkpoint cleanup failed");
        }
        boolean success = update()
                .eq("id", sessionId)
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .eq("status", 1)
                .set("status", 0)
                .set("update_time", LocalDateTime.now())
                .update();
        if (success) {
            deleteSessionMemory(sessionId, userId);
            return Result.ok();
        }
        AiSession latest = queryOwnedCampusSession(sessionId, userId);
        return latest != null && !Integer.valueOf(1).equals(latest.getStatus())
                ? Result.ok()
                : Result.fail("Delete AI session failed");
    }

    @Override
    @Transactional
    public Result clearMyCampusSessions(Long userId) {
        if (userId == null) {
            return Result.fail("User is required");
        }
        if (!deleteUserCheckpoints(userId)) {
            return Result.fail("AI session checkpoint cleanup failed");
        }
        update()
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .eq("status", 1)
                .set("status", 0)
                .set("update_time", LocalDateTime.now())
                .update();
        aiSessionMemoryMapper.delete(new QueryWrapper<AiSessionMemory>()
                .eq("user_id", userId));
        return Result.ok();
    }

    /** Memory 与会话同生命周期；按 session 和 user 双条件避免误删其他用户记录。 */
    private void deleteSessionMemory(Long sessionId, Long userId) {
        aiSessionMemoryMapper.delete(new QueryWrapper<AiSessionMemory>()
                .eq("session_id", sessionId)
                .eq("user_id", userId));
    }

    private boolean deleteCheckpoint(Long userId, String conversationId) {
        try {
            return aiCampusAssistantService.deleteCheckpoint(userId, conversationId);
        } catch (RuntimeException error) {
            // Keep the MySQL session active when graph cleanup cannot be confirmed;
            // the user can retry without leaving a recoverable orphan checkpoint.
            log.warn("Delete AI checkpoint failed, userId={}, conversationId={}, errorType={}",
                    userId, conversationId, error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean deleteUserCheckpoints(Long userId) {
        try {
            return aiCampusAssistantService.deleteUserCheckpoints(userId);
        } catch (RuntimeException error) {
            log.warn("Delete user AI checkpoints failed, userId={}, errorType={}",
                    userId, error.getClass().getSimpleName());
            return false;
        }
    }

    private AiSession queryOwnedCampusSession(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        return query()
                .eq("id", sessionId)
                .eq("user_id", userId)
                .eq("scene", "campus_assistant")
                .one();
    }

    private String buildTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return "Campus assistant chat";
        }
        String text = question.trim();
        return text.length() <= 30 ? text : text.substring(0, 30);
    }
}
