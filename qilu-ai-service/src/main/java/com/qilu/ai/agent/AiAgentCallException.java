package com.qilu.ai.agent;

import com.qilu.ai.api.error.AiFailureCode;

/** Provider 调用 Python Agent 时的类型化失败，禁止再折叠成单一 AGENT_UNAVAILABLE。 */
public class AiAgentCallException extends RuntimeException {

    private final AiFailureCode failureCode;

    public AiAgentCallException(AiFailureCode failureCode, Throwable cause) {
        super(failureCode.name(), cause);
        this.failureCode = failureCode;
    }

    public AiAgentCallException(AiFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }

    public AiFailureCode getFailureCode() {
        return failureCode;
    }
}
