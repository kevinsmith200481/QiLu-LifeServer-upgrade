package com.qilu.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InboxOutboxTaskVO {

    private Long id;
    private String taskNo;
    private String monthKey;
    private Long messageId;
    private String targetType;
    private String publishStatus;
    private Integer publishAttempts;
    private LocalDateTime nextPublishTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime lastPublishTime;
    private String lastPublishError;
    private String deliveryStatus;
    private Integer deliveryAttempts;
    private LocalDateTime nextDeliveryTime;
    private String lastDeliveryError;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
