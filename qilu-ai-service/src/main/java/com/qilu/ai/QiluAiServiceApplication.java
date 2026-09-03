package com.qilu.ai;

import com.qilu.ai.config.AiTelemetry;
import gamer.springboot.starter.annotation.EnableRpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableRpc
@SpringBootApplication
public class QiluAiServiceApplication {

    public static void main(String[] args) {
        AiTelemetry.initialize();
        SpringApplication.run(QiluAiServiceApplication.class, args);
    }
}
