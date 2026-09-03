package com.qilu.ai.api.error;

/**
 * AI 链路的稳定故障契约。每个错误码只对应一个发生阶段、重试语义和安全降级文案，
 * 避免 Controller、Provider 与 Agent 对同一故障给出不同解释。
 */
public enum AiFailureCode {

    RPC_DISCOVERY_EMPTY("rpc_discovery", true, "AI 服务节点暂不可用，请稍后重试。"),
    RPC_CONNECT_TIMEOUT("rpc_client", true, "连接 AI 服务超时，请稍后重试。"),
    RPC_REQUEST_TIMEOUT("rpc_client", true, "AI 服务响应超时，请稍后重试。"),
    RPC_REMOTE_ERROR("rpc_server", false, "AI 服务处理失败，请稍后重试。"),
    RATE_LIMITED("provider_governance", true, "请求较多，请稍后重试。"),
    CIRCUIT_OPEN("provider_governance", true, "AI 服务正在恢复，请稍后重试。"),
    MEMORY_PAYLOAD_TOO_LARGE("provider_governance", false, "会话上下文超过安全限制，请新建会话后重试。"),
    AGENT_CONNECT_TIMEOUT("agent_http_connect", true, "连接智能体超时，请稍后重试。"),
    AGENT_READ_TIMEOUT("agent_http_read", true, "智能体响应超时，请稍后重试。"),
    AGENT_HTTP_ERROR("agent_http", true, "智能体暂时不可用，请稍后重试。"),
    CHECKPOINT_THREAD_CONFLICT("checkpoint_concurrency", true, "当前 AI 会话正在处理另一请求，请稍后重试。"),
    AGENT_INVALID_RESPONSE("agent_decode", false, "智能体响应格式异常，请稍后重试。"),
    AGENT_UNAVAILABLE("agent_http", true, "智能体暂时不可用，请稍后重试。"),
    MODEL_TIMEOUT("model", true, "模型响应超时，已切换到规则回答。"),
    MODEL_UNAVAILABLE("model", true, "模型暂不可用，已切换到规则回答。"),
    TOOL_TIMEOUT("tool", true, "业务数据查询超时，请稍后重试。"),
    TOOL_UNAVAILABLE("tool", true, "业务数据暂时无法读取，请稍后重试。"),
    PERMISSION_DENIED("permission", false, "当前账号无权查看该数据。"),
    KNOWLEDGE_NOT_SYNCED("knowledge", true, "知识库尚未同步，请稍后重试。"),
    NO_SOURCE("generation", false, "暂未找到可靠来源，无法给出确定答案。"),
    UNKNOWN("unknown", false, "AI 服务暂时不可用，请稍后重试。");

    private final String stage;
    private final boolean retriable;
    private final String fallbackMessage;

    AiFailureCode(String stage, boolean retriable, String fallbackMessage) {
        this.stage = stage;
        this.retriable = retriable;
        this.fallbackMessage = fallbackMessage;
    }

    public String getStage() {
        return stage;
    }

    public boolean isRetriable() {
        return retriable;
    }

    public String getFallbackMessage() {
        return fallbackMessage;
    }

    public static AiFailureCode fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
