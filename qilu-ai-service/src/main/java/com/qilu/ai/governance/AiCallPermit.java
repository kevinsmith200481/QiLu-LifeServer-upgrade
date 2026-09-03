package com.qilu.ai.governance;

public class AiCallPermit {

    private final boolean allowed;
    private final String reason;

    private AiCallPermit(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static AiCallPermit allowed() {
        return new AiCallPermit(true, "allowed");
    }

    public static AiCallPermit rejected(String reason) {
        return new AiCallPermit(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }
}
