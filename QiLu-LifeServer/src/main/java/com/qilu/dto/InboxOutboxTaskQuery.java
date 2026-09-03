package com.qilu.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class InboxOutboxTaskQuery {

    @Min(1)
    private long current = 1L;

    @Min(1)
    @Max(100)
    private long pageSize = 20L;

    private String publishStatus;
    private String deliveryStatus;
}
