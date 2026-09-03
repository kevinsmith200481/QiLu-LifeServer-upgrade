package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.dto.ai.AiMemoryBuildResult;
import com.qilu.entity.AiMessage;
import com.qilu.entity.AiSessionMemory;

public interface IAiSessionMemoryService extends IService<AiSessionMemory> {

    AiMemoryBuildResult buildMemory(Long sessionId, Long userId);

    void updateAfterAssistantMessage(
            Long sessionId,
            Long userId,
            String turnId,
            AiMessage assistantMessage,
            CampusAssistantResponse response);
}
