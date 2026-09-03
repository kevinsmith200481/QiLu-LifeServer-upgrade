package com.qilu.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InboxMessageVO {

    private Long cursorId;
    private Long messageId;
    private String messageNo;
    private String messageType;
    private String title;
    private String summary;
    private String content;
    private String businessType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long businessId;
    private Integer readStatus;
    private Integer starStatus;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
}
