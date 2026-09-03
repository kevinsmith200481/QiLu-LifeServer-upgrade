package com.qilu.service.sms;

import lombok.Getter;

@Getter
public class SmsSendResult {

    private final boolean success;
    private final String message;

    private SmsSendResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static SmsSendResult ok() {
        return new SmsSendResult(true, "ok");
    }

    public static SmsSendResult fail(String message) {
        return new SmsSendResult(false, message);
    }
}
