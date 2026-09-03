package com.qilu.ai.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 复用 Agent HTTP client 的资源计数；用于证明超时后没有遗留在途连接。 */
@Component
public class AiAgentHttpClientMetrics {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();

    public void begin() {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
    }

    public void finish() {
        inFlight.decrementAndGet();
        completed.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("clientInstances", 1);
        result.put("inFlight", inFlight.get());
        result.put("maxInFlight", maxInFlight.get());
        result.put("completed", completed.get());
        return result;
    }
}
