package com.qilu.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

@Data
public class InboxBatchActionRequest {

    @NotEmpty(message = "messageIds is required")
    private List<Long> messageIds;

    private String monthKey;
}
