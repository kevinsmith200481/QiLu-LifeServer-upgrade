package com.qilu.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class InboxMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String monthKey;
    private String messageNo;
    private String messageType;
    private String targetType;
    private String title;
    private String content;
    private String summary;
    private String businessType;
    private Long businessId;
    private String targetRoles;
    private Integer status;
    private Long senderId;
    private LocalDateTime expireTime;
    private LocalDateTime revokeTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
