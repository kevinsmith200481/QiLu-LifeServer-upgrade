package com.qilu.dto;

import lombok.Data;

@Data
public class TicketReplyRequest {

    private String remark;
    private String attachmentName;
    private String attachmentUrl;
    private Long attachmentSize;
    private String attachmentType;
    private Boolean needStudentReply;
}
