package com.qilu.ai.api.dto;

import java.io.Serializable;

/** 受控业务实体引用，只保存 ID 与最近一次可信出现位置。 */
public class CampusMemoryEntityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String lastSeenTurnId;
    private Long lastSeenMessageId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLastSeenTurnId() {
        return lastSeenTurnId;
    }

    public void setLastSeenTurnId(String lastSeenTurnId) {
        this.lastSeenTurnId = lastSeenTurnId;
    }

    public Long getLastSeenMessageId() {
        return lastSeenMessageId;
    }

    public void setLastSeenMessageId(Long lastSeenMessageId) {
        this.lastSeenMessageId = lastSeenMessageId;
    }
}
