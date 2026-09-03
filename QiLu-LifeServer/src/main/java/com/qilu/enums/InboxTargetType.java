package com.qilu.enums;

import java.util.Arrays;

public enum InboxTargetType {

    ALL("ALL"),
    USER("USER"),
    ROLE("ROLE");

    private final String code;

    InboxTargetType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static InboxTargetType of(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported target type: " + code));
    }
}
