package com.qilu;

import com.qilu.config.AiTelemetry;
import gamer.springboot.starter.annotation.EnableRpc;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableRpc(needServer = false)
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.qilu.mapper")
@SpringBootApplication
public class QLApplication {

    public static void main(String[] args) {
        AiTelemetry.initialize();
        SpringApplication.run(QLApplication.class, args);
    }

}
