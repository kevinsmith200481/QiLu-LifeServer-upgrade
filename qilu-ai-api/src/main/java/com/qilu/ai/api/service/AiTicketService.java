package com.qilu.ai.api.service;

import com.qilu.ai.api.dto.TicketAiRequest;
import com.qilu.ai.api.dto.TicketCategoryDTO;
import com.qilu.ai.api.dto.TicketSummaryDTO;

public interface AiTicketService {

    TicketSummaryDTO summarize(TicketAiRequest request);

    TicketCategoryDTO classify(TicketAiRequest request);
}
