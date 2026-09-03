package com.qilu.config;

import cn.hutool.core.util.StrUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.HashMap;
import java.util.Map;

public final class AiTelemetry {

    private static final String TRACEPARENT = "traceparent";
    private static final OpenTelemetry OPEN_TELEMETRY = buildOpenTelemetry("qilu-life-server");
    private static final Tracer TRACER = OPEN_TELEMETRY.getTracer("qilu-ai");

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = new TextMapSetter<Map<String, String>>() {
        @Override
        public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null) {
                carrier.put(key, value);
            }
        }
    };

    private AiTelemetry() {
    }

    /** 在 RPC 组件首次使用 GlobalOpenTelemetry 前完成宿主 SDK 注册。 */
    public static void initialize() {
        // 触发静态字段初始化即可。
    }

    public static Span startSpan(String name, String traceParent) {
        if (StrUtil.isBlank(traceParent)) {
            return TRACER.spanBuilder(name).startSpan();
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, traceParent);
        Context parent = OPEN_TELEMETRY.getPropagators().getTextMapPropagator()
                .extract(Context.current(), carrier, MAP_GETTER);
        return TRACER.spanBuilder(name).setParent(parent).startSpan();
    }

    public static String currentTraceParent() {
        Map<String, String> carrier = new HashMap<>();
        OPEN_TELEMETRY.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, MAP_SETTER);
        return carrier.get(TRACEPARENT);
    }

    public static String traceParentFromMap(Map<String, Object> request) {
        Object value = request == null ? null : request.get("traceParent");
        return value == null ? null : String.valueOf(value);
    }

    private static OpenTelemetry buildOpenTelemetry(String defaultServiceName) {
        if (!enabled()) {
            return OpenTelemetry.noop();
        }
        SpanExporter exporter = buildExporter();
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"),
                StrUtil.blankToDefault(System.getenv("OTEL_SERVICE_NAME"), defaultServiceName)
        )));
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }

    private static boolean enabled() {
        if ("true".equalsIgnoreCase(System.getenv("OTEL_SDK_DISABLED"))) {
            return false;
        }
        return truthy(System.getenv("AI_OTEL_ENABLED"))
                || StrUtil.isNotBlank(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
                || StrUtil.isNotBlank(System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
                || StrUtil.isNotBlank(System.getenv("OTEL_TRACES_EXPORTER"));
    }

    private static SpanExporter buildExporter() {
        String endpoint = StrUtil.blankToDefault(
                System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
                System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
        );
        if (StrUtil.isNotBlank(endpoint)) {
            return OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();
        }
        return LoggingSpanExporter.create();
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }
}
