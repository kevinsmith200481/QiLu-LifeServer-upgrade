package com.qilu.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class InboxDeliveryEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long taskId;
    private String taskNo;
    private String monthKey;
    private Long messageId;
}
