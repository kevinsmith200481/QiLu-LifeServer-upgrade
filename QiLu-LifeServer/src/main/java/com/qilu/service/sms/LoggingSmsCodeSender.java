package com.qilu.service.sms;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingSmsCodeSender implements SmsCodeSender {

    @Override
    public SmsSendResult sendCode(String phone, String code) {
        log.info("SMS code generated for phone={}, code={}", maskPhone(phone), code);
        return SmsSendResult.ok();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
