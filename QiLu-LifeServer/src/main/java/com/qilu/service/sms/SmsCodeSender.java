package com.qilu.service.sms;

public interface SmsCodeSender {

    SmsSendResult sendCode(String phone, String code);
}
