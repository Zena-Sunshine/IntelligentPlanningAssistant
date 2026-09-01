from __future__ import annotations

import asyncio
import json
import re
from collections.abc import AsyncIterator
from typing import Any

import httpx

from app.config import Settings
from app.domain.models import HistoryMessage, Intent

ALLOWED_INTENTS = {intent.value for intent in Intent}


class LlmError(RuntimeError):
    pass


CLASSIFY_SYSTEM = """You are VoyageIQ's intent classifier. Output JSON only, no Markdown.
Allowed intents: trip_plan, travel_search, policy_query, approval_create, approval_status, general.
Rules:
- Use approval_create only when the user clearly wants to submit a travel request now.
- Negation, cancel, defer, or wait-for-confirmation must not use approval_create; use approval_status if they ask for progress.
- Restaurants, attractions and tickets are general, not travel_search.
- Multiple intents are allowed. Format: {"intents":["travel_search"],"slots":{"destination":"上海"},"summary":"查询住宿"}"""

COMPOSE_SYSTEM = """你是 VoyageIQ 企业差旅助手，也是多智能体系统最终回复的生成器。
硬性约束：
- 机票、酒店、天气、企业政策和审批等业务事实只能使用工具结果，不要编造航班号、价格、政策或审批单号。
- 普通知识问答、身份问题和自然对话要直接回答，不要用“信息不足，请补充目的地”敷衍与旅行无关的问题。
- 回答模型身份时，只能使用输入中的 runtime.provider 和 runtime.model，不要猜测。
- 能结合最近对话理解追问，但当前用户消息优先于历史内容。
- 不要提议或执行创建审批；若结果里没有审批单，就不要说已经提交。
- 保留全部成功事项，不要因为某一个失败就丢掉其他结果。
- 先直接回答，再给必要依据或下一步；语气自然、专业，不要输出 JSON。
- 不输出私密思维链；可以概括判断依据、执行步骤和工具结果。"""


class LlmClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self.provider = settings.effective_provider()
        self.model = settings.effective_model()
        self._client = client

    @property
    def enabled(self) -> bool:
        return self.provider != "offline" and bool(self.settings.resolved_api_key())

    async def complete(self, messages: list[dict[str, str]], *, temperature: float = 0.3, max_tokens: int = 800) -> str:
        if not self.enabled:
            raise LlmError("LLM provider is offline")
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        headers = {
            "Authorization": f"Bearer {self.settings.resolved_api_key()}",
            "Content-Type": "application/json",
        }
        last_error: Exception | None = None
        for attempt in range(4):
            try:
                response = await self._post(payload, headers)
            except (httpx.HTTPError, OSError) as exc:
                last_error = exc
                await asyncio.sleep(1.5 * (attempt + 1))
                continue
            if response.status_code in {429, 500, 502, 503, 529} and attempt < 3:
                await asyncio.sleep(1.5 * (attempt + 1))
                continue
            if response.status_code >= 400:
                raise LlmError(f"LLM HTTP {response.status_code}: {response.text[:240]}")
            body = response.json()
            try:
                content = body["choices"][0]["message"]["content"]
            except (KeyError, IndexError, TypeError) as exc:
                raise LlmError("LLM response missing content") from exc
            return str(content or "").strip()
        raise LlmError(f"LLM request failed after retries: {last_error}")

    async def stream_complete(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float = 0.3,
        max_tokens: int = 800,
    ) -> AsyncIterator[str]:
        """Yield real provider deltas from the OpenAI-compatible SSE API."""
        if not self.enabled:
            raise LlmError("LLM provider is offline")
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }
        headers = {
            "Authorization": f"Bearer {self.settings.resolved_api_key()}",
            "Content-Type": "application/json",
        }

        async def consume(client: httpx.AsyncClient) -> AsyncIterator[str]:
            try:
                async with client.stream("POST", "/chat/completions", json=payload, headers=headers) as response:
                    if response.status_code >= 400:
                        body = (await response.aread()).decode(errors="replace")
                        raise LlmError(f"LLM HTTP {response.status_code}: {body[:240]}")
                    content_type = response.headers.get("content-type", "")
                    if "text/event-stream" not in content_type:
                        body = await response.aread()
                        parsed = json.loads(body)
                        content = parsed["choices"][0]["message"]["content"]
                        if content:
                            yield str(content)
                        return
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if not data or data == "[DONE]":
                            continue
                        try:
                            frame = json.loads(data)
                            delta = frame["choices"][0].get("delta", {}).get("content")
                        except (json.JSONDecodeError, KeyError, IndexError, TypeError):
                            continue
                        if delta:
                            yield str(delta)
            except (httpx.HTTPError, OSError) as exc:
                raise LlmError(f"LLM stream failed: {exc}") from exc

        if self._client is not None:
            async for delta in consume(self._client):
                yield delta
            return
        async with httpx.AsyncClient(
            base_url=self.settings.effective_base_url(),
            timeout=self.settings.llm_timeout_seconds,
            trust_env=False,
        ) as client:
            async for delta in consume(client):
                yield delta

    async def _post(self, payload: dict[str, Any], headers: dict[str, str]) -> httpx.Response:
        if self._client is not None:
            return await self._client.post("/chat/completions", json=payload, headers=headers)
        async with httpx.AsyncClient(
            base_url=self.settings.effective_base_url(),
            timeout=self.settings.llm_timeout_seconds,
            trust_env=False,
        ) as client:
            return await client.post("/chat/completions", json=payload, headers=headers)

    async def classify(self, query: str, history: list[HistoryMessage] | None = None) -> dict[str, Any] | None:
        history_lines = []
        for message in (history or [])[-6:]:
            history_lines.append(f"{message.role}: {message.content}")
        user = "会话历史：\n" + ("\n".join(history_lines) if history_lines else "（无）") + f"\n当前用户：{query}"
        raw = await self.complete(
            [{"role": "system", "content": CLASSIFY_SYSTEM}, {"role": "user", "content": user}],
            temperature=0, max_tokens=300,
        )
        parsed = _parse_json(raw)
        if not parsed:
            return None
        intents = [value for value in parsed.get("intents", []) if value in ALLOWED_INTENTS]
        if not intents:
            return None
        slots = {
            str(key): str(value)
            for key, value in dict(parsed.get("slots") or {}).items()
            if value not in (None, "")
        }
        return {"intents": intents, "slots": slots, "summary": str(parsed.get("summary") or "模型完成语义分类。")}

    def compose_messages(
        self,
        query: str,
        drafts: list[dict[str, Any]],
        history: list[HistoryMessage] | None = None,
        runtime: dict[str, str] | None = None,
    ) -> list[dict[str, str]]:
        payload = json.dumps({
            "runtime": runtime or {"provider": self.provider, "model": self.model},
            "history": [message.model_dump() for message in (history or [])[-8:]],
            "current_query": query,
            "agent_results": drafts,
        }, ensure_ascii=False)
        return [{"role": "system", "content": COMPOSE_SYSTEM}, {"role": "user", "content": payload}]

    async def compose(
        self,
        query: str,
        drafts: list[dict[str, Any]],
        history: list[HistoryMessage] | None = None,
        runtime: dict[str, str] | None = None,
    ) -> str:
        return await self.complete(
            self.compose_messages(query, drafts, history, runtime),
            temperature=0.2, max_tokens=900,
        )

    async def stream_compose(
        self,
        query: str,
        drafts: list[dict[str, Any]],
        history: list[HistoryMessage] | None = None,
        runtime: dict[str, str] | None = None,
    ) -> AsyncIterator[str]:
        async for delta in self.stream_complete(
            self.compose_messages(query, drafts, history, runtime),
            temperature=0.2,
            max_tokens=900,
        ):
            yield delta


def _parse_json(raw: str) -> dict[str, Any] | None:
    text = raw.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    if fenced:
        text = fenced.group(1)
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.S)
        if not match:
            return None
        try:
            value = json.loads(match.group(0))
        except json.JSONDecodeError:
            return None
    return value if isinstance(value, dict) else None
