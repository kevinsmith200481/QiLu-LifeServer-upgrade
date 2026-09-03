package com.qilu.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentOrderVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private Long userId;
    private Long slotId;
    private Long servicePointId;
    private Integer status;
    private String statusText;
    private String remark;
    private String internalRemark;
    private String slotTitle;
    private String slotDescription;
    private Integer slotStatus;
    private String servicePointName;
    private String servicePointAddress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime cancelTime;
    private LocalDateTime finishTime;
}
