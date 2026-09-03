package com.qilu.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class InboxQueryRequest {

    private String messageType;
    private Integer readStatus;
    private Integer starStatus;
    private Long cursor;
    private String monthKey;

    @Min(value = 1, message = "pageSize must be positive")
    @Max(value = 100, message = "pageSize is too large")
    private Integer pageSize = 20;
}
