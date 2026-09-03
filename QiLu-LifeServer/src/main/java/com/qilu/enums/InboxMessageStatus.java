package com.qilu.enums;

public enum InboxMessageStatus {

    NORMAL(1),
    REVOKED(2),
    EXPIRED(3);

    private final Integer code;

    InboxMessageStatus(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
