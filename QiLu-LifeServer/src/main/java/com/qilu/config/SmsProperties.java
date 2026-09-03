package com.qilu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "campus.sms")
public class SmsProperties {

    private String mode = "logging";
    private String endpoint;
    private String authHeaderName;
    private String authHeaderValue;
    private String accessKey;
    private String secretKey;
    private String templateId;
    private String signName;
    private String successKeyword;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 5000;
}
