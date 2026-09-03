package com.qilu.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class InboxUserMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String monthKey;
    private Long messageId;
    private String messageNo;
    private Long userId;
    private String messageType;
    private Integer readStatus;
    private Integer starStatus;
    private Integer deleted;
    private LocalDateTime readTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
