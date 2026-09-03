package gamer.context;

import gamer.model.RpcRequest;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RpcInvocationContextTest {

    @Test
    public void exposesOnlyWhitelistedAttemptMetadata() {
        Map<String, String> attachments = new LinkedHashMap<String, String>();
        attachments.put("rpc.attempt", "2");
        attachments.put("token", "must-not-propagate");
        RpcRequest request = RpcRequest.builder().attachments(attachments).build();

        try (RpcInvocationContext.Scope ignored = RpcInvocationContext.open(request)) {
            assertEquals(2, RpcInvocationContext.attempt());
        }
        assertEquals(1, RpcInvocationContext.attempt());
    }
}
