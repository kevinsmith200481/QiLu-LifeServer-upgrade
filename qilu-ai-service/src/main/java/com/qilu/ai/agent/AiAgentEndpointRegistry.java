package com.qilu.ai.agent;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AiAgentEndpointRegistry {

    private static final String DEFAULT_AGENT_BASE_URL = "http://localhost:8001";

    @Value("${ai.agent.base-urls:}")
    private String configuredAgentBaseUrls;

    public List<String> baseUrls() {
        String value = System.getenv("AI_AGENT_BASE_URLS");
        if (StrUtil.isBlank(value)) {
            value = configuredAgentBaseUrls;
        }
        if (StrUtil.isBlank(value)) {
            value = System.getenv("AI_AGENT_BASE_URL");
        }
        if (StrUtil.isBlank(value)) {
            value = DEFAULT_AGENT_BASE_URL;
        }

        List<String> result = new ArrayList<String>();
        String[] parts = value.split(",");
        for (String part : parts) {
            String url = normalizeBaseUrl(part);
            if (StrUtil.isNotBlank(url) && !result.contains(url)) {
                result.add(url);
            }
        }
        if (result.isEmpty()) {
            result.add(DEFAULT_AGENT_BASE_URL);
        }
        return Collections.unmodifiableList(result);
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
