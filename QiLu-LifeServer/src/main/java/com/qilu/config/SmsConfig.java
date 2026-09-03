package com.qilu.config;

import com.qilu.service.sms.HttpSmsCodeSender;
import com.qilu.service.sms.LoggingSmsCodeSender;
import com.qilu.service.sms.SmsCodeSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfig {

    @Bean
    public SmsCodeSender smsCodeSender(SmsProperties properties, RestTemplateBuilder restTemplateBuilder) {
        if ("http".equalsIgnoreCase(properties.getMode())) {
            RestTemplate restTemplate = restTemplateBuilder
                    .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                    .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                    .build();
            return new HttpSmsCodeSender(properties, restTemplate);
        }
        return new LoggingSmsCodeSender();
    }
}
