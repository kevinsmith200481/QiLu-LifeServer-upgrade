package com.qilu.ai.api.dto;

import java.io.Serializable;

/** 模型摘要封闭响应；失败只返回稳定错误码，不返回异常原文。 */
public class CampusMemorySummaryResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean success;
    private String rollingSummary;
    private String errorCode;

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getRollingSummary() {
        return rollingSummary;
    }

    public void setRollingSummary(String rollingSummary) {
        this.rollingSummary = rollingSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
