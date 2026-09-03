package com.qilu.ai.api.dto;

import java.io.Serializable;

public class CampusTicketDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private String content;
    private Integer status;
    private String statusText;
    private Integer priority;
    private Integer studentReplyRequired;
    private String studentReplyTime;
    private String attachmentName;
    private String attachmentUrl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getStudentReplyRequired() {
        return studentReplyRequired;
    }

    public void setStudentReplyRequired(Integer studentReplyRequired) {
        this.studentReplyRequired = studentReplyRequired;
    }

    public String getStudentReplyTime() {
        return studentReplyTime;
    }

    public void setStudentReplyTime(String studentReplyTime) {
        this.studentReplyTime = studentReplyTime;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public void setAttachmentUrl(String attachmentUrl) {
        this.attachmentUrl = attachmentUrl;
    }
}
