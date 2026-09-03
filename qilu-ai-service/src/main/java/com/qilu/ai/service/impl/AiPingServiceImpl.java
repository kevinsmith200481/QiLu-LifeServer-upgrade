package com.qilu.ai.service.impl;

import com.qilu.ai.api.service.AiPingService;
import gamer.springboot.starter.annotation.RpcService;

@RpcService(interfaceClass = AiPingService.class)
public class AiPingServiceImpl implements AiPingService {

    @Override
    public String ping(String message) {
        return "qilu-ai-service pong: " + message;
    }
}
