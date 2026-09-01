from __future__ import annotations

import json

import httpx

from app.config import Settings
from app.domain.models import AgentRequest, Intent
from app.llm.client import LlmClient
from app.orchestration.orchestrator import AgentOrchestrator
from app.orchestration.router import HybridIntentRouter
from app.tools.business import BusinessToolClient
from app.tools.travel import TravelSearchTools
from app.agents.registry import AgentRegistry


def settings_with_key() -> Settings:
    return Settings(
        provider="dashscope",
        llm_api_key="test-key",
        llm_model="qwen-plus",
        llm_base_url="http://llm.test/v1",
        fallback_to_offline=False,
        business_base_url="http://127.0.0.1:1",
    )


def _clear_llm_env(monkeypatch) -> None:
    for name in (
        "DASHSCOPE_API_KEY", "OPENAI_API_KEY", "MODEL_PROVIDER", "DASHSCOPE_MODEL",
        "VOYAGEIQ_AGENT_PROVIDER", "VOYAGEIQ_AGENT_LLM_MODEL", "VOYAGEIQ_AGENT_LLM_API_KEY",
    ):
        monkeypatch.delenv(name, raising=False)


def test_auto_provider_is_offline_without_keys(monkeypatch):
    _clear_llm_env(monkeypatch)
    settings = Settings(provider="auto", llm_api_key="", _env_file=None)
    assert settings.effective_provider() == "offline"
    assert LlmClient(settings).enabled is False


def test_dashscope_key_enables_provider(monkeypatch):
    _clear_llm_env(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test")
    settings = Settings(provider="auto", llm_api_key="", _env_file=None)
    assert settings.effective_provider() == "dashscope"
    assert settings.effective_model() == "qwen-plus"
    assert "dashscope.aliyuncs.com" in settings.effective_base_url()


def test_dashscope_model_env_alias(monkeypatch):
    _clear_llm_env(monkeypatch)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test")
    monkeypatch.setenv("MODEL_PROVIDER", "dashscope")
    monkeypatch.setenv("DASHSCOPE_MODEL", "qwen-turbo")
    settings = Settings(provider="auto", llm_api_key="", llm_model="", _env_file=None)
    assert settings.effective_provider() == "dashscope"
    assert settings.effective_model() == "qwen-turbo"


async def test_complete_reads_openai_compatible_payload():
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["model"] == "qwen-plus"
        assert request.headers["authorization"] == "Bearer test-key"
        return httpx.Response(200, json={"choices": [{"message": {"content": "  行程已根据机票整理。  "}}]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm.test/v1") as http:
        text = await LlmClient(settings_with_key(), http).complete([{"role": "user", "content": "hi"}])
    assert text == "行程已根据机票整理。"


async def test_stream_complete_yields_real_sse_deltas():
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["stream"] is True
        content = (
            'data: {"choices":[{"delta":{"content":"正在"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"分析"}}]}\n\n'
            'data: [DONE]\n\n'
        )
        return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content=content)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm.test/v1") as http:
        chunks = [chunk async for chunk in LlmClient(settings_with_key(), http).stream_complete(
            [{"role": "user", "content": "hi"}], max_tokens=60,
        )]
    assert chunks == ["正在", "分析"]


async def test_compose_payload_contains_history_and_runtime_identity():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured.update(json.loads(body["messages"][1]["content"]))
        return httpx.Response(200, json={"choices": [{"message": {"content": "当前使用 qwen-plus。"}}]})

    from app.domain.models import HistoryMessage
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm.test/v1") as http:
        answer = await LlmClient(settings_with_key(), http).compose(
            "你是什么模型",
            [{"agent": "旅行问答助手", "text": "模型身份"}],
            [HistoryMessage(role="user", content="先介绍一下自己")],
            {"provider": "dashscope", "model": "qwen-plus"},
        )
    assert answer == "当前使用 qwen-plus。"
    assert captured["runtime"] == {"provider": "dashscope", "model": "qwen-plus"}
    assert captured["history"][0]["content"] == "先介绍一下自己"


async def test_llm_cannot_bypass_approval_side_effect_guard():
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        system = body["messages"][0]["content"]
        if "intent classifier" in system.lower() or "意图分类器" in system:
            content = json.dumps({"intents": ["approval_create"], "summary": "submit now"})
        else:
            content = "先不提交申请，我可以继续帮你查政策和行程。"
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm.test/v1") as http:
        llm = LlmClient(settings_with_key(), http)
        settings = settings_with_key()
        orchestrator = AgentOrchestrator(
            HybridIntentRouter(),
            AgentRegistry(TravelSearchTools(), BusinessToolClient(settings)),
            llm=llm, provider="dashscope", model="qwen-plus",
        )
        events = [event async for event in orchestrator.stream(AgentRequest(
            request_id="llm-safety", conversation_id="c1", user_id="u1",
            query="最近怎么样，随便聊聊",
        ))]
    done = next(event for event in events if event.type == "done")
    session = next(event for event in events if event.type == "session")
    assert session.data["provider"] == "dashscope"
    assert Intent.APPROVAL_CREATE.value not in done.data["intents"]


async def test_llm_compose_replaces_offline_draft_when_provider_is_live():
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        system = body["messages"][0]["content"]
        if "intent classifier" in system.lower() or "意图分类器" in system:
            content = json.dumps({"intents": ["general"]})
        else:
            content = "我是已接入通义千问的 VoyageIQ 助手。"
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://llm.test/v1") as http:
        llm = LlmClient(settings_with_key(), http)
        settings = settings_with_key()
        orchestrator = AgentOrchestrator(
            HybridIntentRouter(),
            AgentRegistry(TravelSearchTools(), BusinessToolClient(settings)),
            llm=llm, provider="dashscope", model="qwen-plus",
        )
        events = [event async for event in orchestrator.stream(AgentRequest(
            request_id="llm-compose", conversation_id="c1", user_id="u1", query="你是谁",
        ))]
    done = next(event for event in events if event.type == "done")
    event_types = [event.type for event in events]
    assert "thinking_start" in event_types
    assert "composition_start" in event_types
    assert "已接入通义千问" in done.data["answer"]
