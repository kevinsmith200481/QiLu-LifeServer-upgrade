package com.qilu.ai.api.dto;

import java.io.Serializable;

/** 待确认草稿的最小引用，不保存正文或可直接执行的写入参数。 */
public class CampusMemoryActionDraftDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private String targetType;
    private Long targetId;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }
}
