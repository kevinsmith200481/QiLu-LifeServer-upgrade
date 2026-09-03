package com.qilu.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class InboxRealtimePayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long messageId;
    private String messageType;
    private String title;
    private String summary;
    private LocalDateTime createTime;
}
