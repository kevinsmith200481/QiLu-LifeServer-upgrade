package gamer.telemetry;

import gamer.model.RpcRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

/** RPC 的 API-only Trace 桥接；未安装 SDK 时所有操作自然退化为 no-op。 */
public final class RpcTelemetry {

    private static final String TRACEPARENT = "traceparent";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private static final TextMapSetter<Map<String, String>> SETTER = new TextMapSetter<Map<String, String>>() {
        @Override
        public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null) {
                carrier.put(key, value);
            }
        }
    };

    private RpcTelemetry() {
    }

    public static Span startClientSpan(String service, String method, String traceParent) {
        return spanBuilder("rpc.client " + simpleName(service) + "." + method, traceParent)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
    }

    public static Span startServerSpan(RpcRequest request) {
        String service = request == null ? "unknown" : request.getServiceName();
        String method = request == null ? "unknown" : request.getMethodName();
        return spanBuilder("rpc.server " + simpleName(service) + "." + method,
                request == null ? null : request.getTraceParent())
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
    }

    public static String currentTraceParent() {
        Map<String, String> carrier = new HashMap<String, String>();
        telemetry().getPropagators().getTextMapPropagator().inject(Context.current(), carrier, SETTER);
        return carrier.get(TRACEPARENT);
    }

    public static String currentTraceId() {
        return Span.current().getSpanContext().isValid()
                ? Span.current().getSpanContext().getTraceId()
                : null;
    }

    public static void markError(Span span, Throwable error, String errorCode) {
        if (error != null) {
            span.recordException(error);
        }
        span.setStatus(StatusCode.ERROR, errorCode == null ? "RPC_ERROR" : errorCode);
        span.setAttribute("rpc.error_code", errorCode == null ? "RPC_ERROR" : errorCode);
    }

    private static io.opentelemetry.api.trace.SpanBuilder spanBuilder(String name, String traceParent) {
        Tracer tracer = telemetry().getTracer("qilu-rpc-core");
        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
        if (traceParent == null || traceParent.trim().isEmpty()) {
            return builder;
        }
        Map<String, String> carrier = new HashMap<String, String>();
        carrier.put(TRACEPARENT, traceParent);
        Context parent = telemetry().getPropagators().getTextMapPropagator()
                .extract(Context.current(), carrier, GETTER);
        return builder.setParent(parent);
    }

    private static OpenTelemetry telemetry() {
        // 延迟读取 Global，确保宿主进程有机会先注册 SDK。
        return GlobalOpenTelemetry.get();
    }

    private static String simpleName(String service) {
        if (service == null) {
            return "unknown";
        }
        int index = service.lastIndexOf('.');
        return index < 0 ? service : service.substring(index + 1);
    }
}
