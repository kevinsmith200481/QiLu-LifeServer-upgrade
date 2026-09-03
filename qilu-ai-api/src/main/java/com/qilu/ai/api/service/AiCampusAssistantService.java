package com.qilu.ai.api.service;

import com.qilu.ai.api.dto.CampusAssistantRequest;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusMemorySummaryRequestDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryResponseDTO;

public interface AiCampusAssistantService {

    CampusAssistantResponse chat(CampusAssistantRequest request);

    /** 可选模型摘要增强；调用方必须在用户响应路径之外执行。 */
    CampusMemorySummaryResponseDTO summarizeMemory(CampusMemorySummaryRequestDTO request);

    /** Delete one user's graph state after the matching MySQL session is removed. */
    boolean deleteCheckpoint(Long userId, String conversationId);

    /** Delete all graph states when the user clears all campus AI sessions. */
    boolean deleteUserCheckpoints(Long userId);
}
