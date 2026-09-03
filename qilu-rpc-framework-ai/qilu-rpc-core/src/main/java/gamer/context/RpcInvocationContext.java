package gamer.context;

import gamer.model.RpcRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider 业务方法读取当前 RPC attempt 等安全框架元数据的只读上下文。 */
public final class RpcInvocationContext {

    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<Map<String, String>>();

    private RpcInvocationContext() {
    }

    public static Scope open(RpcRequest request) {
        Map<String, String> previous = CURRENT.get();
        Map<String, String> source = request == null ? null : request.getAttachments();
        Map<String, String> safe = new LinkedHashMap<String, String>();
        if (source != null && source.get("rpc.attempt") != null) {
            safe.put("rpc.attempt", source.get("rpc.attempt"));
        }
        CURRENT.set(Collections.unmodifiableMap(safe));
        return new Scope(previous);
    }

    public static int attempt() {
        Map<String, String> values = CURRENT.get();
        if (values == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(values.get("rpc.attempt")));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
