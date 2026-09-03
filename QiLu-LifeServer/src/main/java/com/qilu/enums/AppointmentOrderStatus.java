package com.qilu.enums;

import java.util.Arrays;

public enum AppointmentOrderStatus {

    RESERVED(1, "Reserved"),
    CANCELED(2, "Canceled"),
    FINISHED(3, "Finished"),
    EXPIRED(4, "Expired"),
    NO_SHOW(5, "No show");

    private final int code;
    private final String desc;

    AppointmentOrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static AppointmentOrderStatus of(Integer code) {
        return Arrays.stream(values())
                .filter(item -> Integer.valueOf(item.code).equals(code))
                .findFirst()
                .orElse(null);
    }
}
