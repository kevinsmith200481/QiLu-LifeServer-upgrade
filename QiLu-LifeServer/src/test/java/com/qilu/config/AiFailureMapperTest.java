package com.qilu.config;

import com.qilu.ai.api.error.AiFailureCode;
import gamer.exception.RpcRemoteException;
import gamer.exception.RpcTransportException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.lang.reflect.UndeclaredThrowableException;

import static org.assertj.core.api.Assertions.assertThat;

class AiFailureMapperTest {

    @Test
    void mapsRpcFailuresToStablePublicCodes() {
        assertThat(AiFailureMapper.from(new RpcTransportException(
                "RPC_DISCOVERY_EMPTY", "empty", true, 1L)))
                .isEqualTo(AiFailureCode.RPC_DISCOVERY_EMPTY);
        assertThat(AiFailureMapper.from(new ExecutionException(new RpcTransportException(
                "RPC_REQUEST_TIMEOUT", "timeout", true, 2L))))
                .isEqualTo(AiFailureCode.RPC_REQUEST_TIMEOUT);
        assertThat(AiFailureMapper.from(new RpcRemoteException(
                "RPC_PROVIDER_ERROR", "IllegalStateException", "failed", false, 3L)))
                .isEqualTo(AiFailureCode.RPC_REMOTE_ERROR);
        assertThat(AiFailureMapper.from(new ExecutionException(
                new UndeclaredThrowableException(new RpcTransportException(
                        "RPC_CONNECT_FAILED", "down", true, 4L)))))
                .isEqualTo(AiFailureCode.RPC_CONNECT_TIMEOUT);
    }
}
