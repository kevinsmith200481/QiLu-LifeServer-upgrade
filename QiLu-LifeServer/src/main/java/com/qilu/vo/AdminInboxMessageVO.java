package com.qilu.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminInboxMessageVO {

    private String monthKey;
    private Long messageId;
    private String messageNo;
    private String messageType;
    private String targetType;
    private String title;
    private String summary;
    private String businessType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long businessId;
    private Long senderId;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
