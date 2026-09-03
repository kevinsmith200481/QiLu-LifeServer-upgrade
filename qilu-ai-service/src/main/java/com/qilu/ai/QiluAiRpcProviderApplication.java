package com.qilu.ai;

/**
 * Backward-compatible entry point for the AI provider.
 *
 * The Spring Boot application owns both the HTTP metrics surface and the RPC
 * provider registration, so keep this class as a thin delegate.
 */

public class QiluAiRpcProviderApplication {

    public static void main(String[] args) {
        QiluAiServiceApplication.main(args);
    }
}
