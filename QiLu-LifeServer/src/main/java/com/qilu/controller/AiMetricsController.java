package com.qilu.controller;

import com.qilu.metrics.AiMainMetrics;
import com.qilu.config.AiCallExecutor;
import gamer.server.tcp.VertxTcpClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 仅暴露聚合计数，不包含用户、问题、工具参数或其他敏感内容。 */
@RestController
@RequestMapping("/ai/metrics")
public class AiMetricsController {

    private final AiMainMetrics metrics;
    private final AiCallExecutor callExecutor;

    public AiMetricsController(AiMainMetrics metrics, AiCallExecutor callExecutor) {
        this.metrics = metrics;
        this.callExecutor = callExecutor;
    }

    @GetMapping
    public Map<String, Long> metrics() {
        return metrics.snapshot();
    }

    @GetMapping(value = "/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return metrics.prometheus();
    }

    @GetMapping("/resources")
    public Map<String, Object> resources() {
        Map<String, Object> resources = new java.util.LinkedHashMap<>();
        resources.put("executorActive", callExecutor.activeCount());
        resources.put("executorPoolSize", callExecutor.poolSize());
        resources.put("executorQueueSize", callExecutor.queueSize());
        resources.put("executorMaxPoolSize", callExecutor.getProperties().getMaxPoolSize());
        resources.put("rpcActiveSockets", VertxTcpClient.activeSocketCount());
        resources.put("rpcCreatedClients", VertxTcpClient.createdClientCount());
        return resources;
    }
}
