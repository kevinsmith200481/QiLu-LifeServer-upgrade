from __future__ import annotations

import os
import time

from agent import campus_support_agent as agent
from app.acceptance_faults import delay_tool_if_configured
from app.failures import failure_contract


def assert_equal(actual, expected, message: str) -> None:
    if actual != expected:
        raise AssertionError(f"{message}: expected={expected!r}, actual={actual!r}")


def with_env(values, callback) -> None:
    previous = {name: os.environ.get(name) for name in values}
    try:
        for name, value in values.items():
            os.environ[name] = value
        callback()
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def test_model_timeout_is_structured() -> None:
    def run() -> None:
        result = agent.invoke_llm_result("system", "question", "trace-timeout")
        assert_equal(result.error_code, "MODEL_TIMEOUT", "model timeout code")
        state = {
            "generation_record": {"fallbackReason": result.error_code},
            "knowledge_initialized": True,
            "business_tool_results": [],
        }
        assert_equal(agent.resolve_fallback_reason(state, [object()]), "MODEL_TIMEOUT", "model response code")

    with_env({
        "OPENAI_API_KEY": "acceptance-placeholder",
        "APP_PROFILE": "acceptance",
        "QILU_ACCEPTANCE_FAULTS_ENABLED": "true",
        "QILU_ACCEPTANCE_MODEL_TIMEOUT": "true",
    }, run)


def test_model_client_has_explicit_budget_and_no_retry() -> None:
    captured = {}
    original = agent.ChatOpenAI

    class FakeResponse:
        content = "ok"

    class FakeChatOpenAI:
        def __init__(self, **kwargs):
            captured.update(kwargs)

        def invoke(self, messages):
            return FakeResponse()

    def run() -> None:
        agent.ChatOpenAI = FakeChatOpenAI
        try:
            result = agent.invoke_llm_result("system", "question")
        finally:
            agent.ChatOpenAI = original
        assert_equal(result.content, "ok", "model response")
        assert_equal(captured.get("timeout"), 8.0, "model timeout")
        assert_equal(captured.get("max_retries"), 0, "model retries")

    with_env({"OPENAI_API_KEY": "acceptance-placeholder"}, run)


def test_tool_fault_respects_total_budget() -> None:
    def run() -> None:
        started = time.monotonic()
        try:
            delay_tool_if_configured(0.02)
        except TimeoutError:
            pass
        else:
            raise AssertionError("tool delay must time out")
        if time.monotonic() - started > 0.2:
            raise AssertionError("tool timeout exceeded its test budget")

    with_env({
        "APP_PROFILE": "acceptance",
        "QILU_ACCEPTANCE_FAULTS_ENABLED": "true",
        "QILU_ACCEPTANCE_TOOL_DELAY_MS": "50",
    }, run)


def test_failure_catalog() -> None:
    timeout = failure_contract("TOOL_TIMEOUT")
    assert_equal(timeout.stage, "tool", "tool stage")
    assert_equal(timeout.retriable, True, "tool retry")
    denied = failure_contract("PERMISSION_DENIED")
    assert_equal(denied.retriable, False, "permission retry")


if __name__ == "__main__":
    tests = [
        test_model_timeout_is_structured,
        test_model_client_has_explicit_budget_and_no_retry,
        test_tool_fault_respects_total_budget,
        test_failure_catalog,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)}/{len(tests)}")
