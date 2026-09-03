package com.qilu.enums;

import java.util.Arrays;

public enum InboxMessageType {

    SYSTEM_NOTICE("SYSTEM_NOTICE", "系统公告", true),
    BUSINESS_REMINDER("BUSINESS_REMINDER", "业务提醒", false),
    APPROVAL_NOTICE("APPROVAL_NOTICE", "审批通知", false),
    EXCEPTION_ALERT("EXCEPTION_ALERT", "异常告警", false),
    SITE_REPLY("SITE_REPLY", "站内回复", false);

    private final String code;
    private final String desc;
    private final boolean hotCache;

    InboxMessageType(String code, String desc, boolean hotCache) {
        this.code = code;
        this.desc = desc;
        this.hotCache = hotCache;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public boolean isHotCache() {
        return hotCache;
    }

    public static InboxMessageType of(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported message type: " + code));
    }
}
