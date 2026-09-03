package com.qilu.vo;

import lombok.Data;

@Data
public class AppointmentOrderStatsVO {

    private Long pending;
    private Long today;
    private Long finished;
    private Long abnormal;
}
