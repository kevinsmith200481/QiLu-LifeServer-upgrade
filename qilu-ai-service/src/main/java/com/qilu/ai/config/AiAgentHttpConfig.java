package com.qilu.ai.config;

import com.qilu.ai.metrics.AiAgentHttpClientMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provider 进程复用同一个受控 HTTP client，避免每次 Agent 调用重建客户端。 */
@Configuration
public class AiAgentHttpConfig {

    private static final Logger log = LoggerFactory.getLogger(AiAgentHttpConfig.class);

    @Bean("aiAgentRestTemplate")
    public RestTemplate aiAgentRestTemplate(
            @Value("${ai.agent.connect-timeout-ms:1500}") int connectTimeoutMs,
            @Value("${ai.agent.read-timeout-ms:50000}") int readTimeoutMs,
            AiAgentHttpClientMetrics metrics) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= connectTimeoutMs) {
            throw new IllegalStateException("Agent read timeout must be greater than connect timeout");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        // Startup evidence makes the effective outer budget auditable; relying
        // on a command-line value alone would not prove Spring applied it.
        log.info("AI Agent HTTP client configured, connectTimeoutMs={}, readTimeoutMs={}",
                connectTimeoutMs, readTimeoutMs);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add((request, body, execution) -> {
            metrics.begin();
            try {
                return execution.execute(request, body);
            } finally {
                metrics.finish();
            }
        });
        return restTemplate;
    }
}
