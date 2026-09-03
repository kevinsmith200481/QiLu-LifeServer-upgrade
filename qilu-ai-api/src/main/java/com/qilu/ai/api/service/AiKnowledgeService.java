package com.qilu.ai.api.service;

import com.qilu.ai.api.dto.KnowledgeReloadRequest;
import com.qilu.ai.api.dto.KnowledgeReloadResponse;

public interface AiKnowledgeService {

    KnowledgeReloadResponse reloadKnowledge(KnowledgeReloadRequest request);
}
