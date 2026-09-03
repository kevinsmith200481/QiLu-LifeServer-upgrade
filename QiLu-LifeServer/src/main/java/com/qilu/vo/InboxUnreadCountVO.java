package com.qilu.vo;

import lombok.Data;

import java.util.Map;

@Data
public class InboxUnreadCountVO {

    private Long total;
    private Map<String, Long> typeCounts;
}
