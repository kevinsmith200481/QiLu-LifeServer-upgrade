package com.qilu.service.sms;

import cn.hutool.core.util.StrUtil;
import com.qilu.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class HttpSmsCodeSender implements SmsCodeSender {

    private final SmsProperties properties;
    private final RestTemplate restTemplate;

    public HttpSmsCodeSender(SmsProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @Override
    public SmsSendResult sendCode(String phone, String code) {
        if (StrUtil.isBlank(properties.getEndpoint())) {
            return SmsSendResult.fail("SMS endpoint is not configured");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StrUtil.isNotBlank(properties.getAuthHeaderName()) && StrUtil.isNotBlank(properties.getAuthHeaderValue())) {
                headers.set(properties.getAuthHeaderName(), properties.getAuthHeaderValue());
            }

            Map<String, Object> body = new HashMap<>();
            body.put("phone", phone);
            body.put("code", code);
            body.put("templateId", properties.getTemplateId());
            body.put("signName", properties.getSignName());
            body.put("accessKey", properties.getAccessKey());
            body.put("secretKey", properties.getSecretKey());

            ResponseEntity<String> response = restTemplate.postForEntity(
                    properties.getEndpoint(),
                    new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return SmsSendResult.fail("SMS gateway returned status " + response.getStatusCodeValue());
            }
            String successKeyword = properties.getSuccessKeyword();
            if (StrUtil.isNotBlank(successKeyword)
                    && (response.getBody() == null || !response.getBody().contains(successKeyword))) {
                return SmsSendResult.fail("SMS gateway response did not contain success keyword");
            }
            return SmsSendResult.ok();
        } catch (Exception e) {
            log.warn("SMS gateway call failed: {}", e.getMessage());
            return SmsSendResult.fail("SMS gateway call failed");
        }
    }
}
