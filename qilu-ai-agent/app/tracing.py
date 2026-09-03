from __future__ import annotations

import os
from contextlib import contextmanager
from typing import Dict, Iterator, Optional

try:
    from opentelemetry import propagate, trace
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import ConsoleSpanExporter, SimpleSpanProcessor
except ImportError:  # OpenTelemetry is optional at local runtime.
    propagate = None
    trace = None
    OTLPSpanExporter = None
    Resource = None
    TracerProvider = None
    ConsoleSpanExporter = None
    SimpleSpanProcessor = None


def _truthy(value: Optional[str]) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _enabled() -> bool:
    if _truthy(os.getenv("OTEL_SDK_DISABLED")):
        return False
    return (
        _truthy(os.getenv("AI_OTEL_ENABLED"))
        or bool(os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
        or bool(os.getenv("OTEL_TRACES_EXPORTER"))
    )


def _configure_tracer():
    if not _enabled() or trace is None or TracerProvider is None or SimpleSpanProcessor is None:
        return None
    resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "qilu-ai-agent")})
    provider = TracerProvider(resource=resource)
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint and OTLPSpanExporter is not None:
        exporter = OTLPSpanExporter(endpoint=endpoint, insecure=_truthy(os.getenv("OTEL_EXPORTER_OTLP_INSECURE", "true")))
    elif ConsoleSpanExporter is not None:
        exporter = ConsoleSpanExporter()
    else:
        return None
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    return trace.get_tracer("qilu-ai")


TRACER = _configure_tracer()


@contextmanager
def ai_span(name: str, trace_parent: Optional[str] = None, **attributes) -> Iterator[object]:
    if TRACER is None or trace is None:
        yield None
        return
    context = None
    if trace_parent and propagate is not None:
        context = propagate.extract({"traceparent": trace_parent})
    with TRACER.start_as_current_span(name, context=context) as span:
        for key, value in attributes.items():
            if value is not None:
                span.set_attribute(key, value)
        yield span


def inject_traceparent() -> Optional[str]:
    if propagate is None:
        return None
    carrier: Dict[str, str] = {}
    propagate.inject(carrier)
    return carrier.get("traceparent")


def record_exception(span, exc: BaseException) -> None:
    if span is not None:
        span.record_exception(exc)
