package com.qilu.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AppointmentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String eventType;
    private Long orderId;
    private Long userId;
    private Long slotId;
    private Long servicePointId;
    private Long managerId;
    private String slotTitle;
    private String servicePointName;
    private String servicePointAddress;
    private String remark;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime occurTime;
    private Integer retryCount;
    private String errorMsg;
}
