from __future__ import annotations

import asyncio
import json
import time
import uuid
from collections.abc import AsyncIterator

import httpx

from app.agents.registry import AgentRegistry
from app.domain.models import AgentRequest, AgentResult, Intent, StreamEvent
from app.llm.client import LlmClient, LlmError
from app.orchestration.router import HybridIntentRouter


class AgentOrchestrator:
    def __init__(
        self,
        router: HybridIntentRouter,
        registry: AgentRegistry,
        max_parallel_agents: int = 4,
        llm: LlmClient | None = None,
        provider: str = "offline",
        model: str = "offline",
        compose: bool = True,
    ):
        self.router = router
        self.registry = registry
        self.semaphore = asyncio.Semaphore(max_parallel_agents)
        self.llm = llm if llm is not None and llm.enabled else None
        self.provider = provider if self.llm else "offline"
        self.model = model if self.llm else "offline"
        self.compose_with_llm = compose

    async def resolve_route(self, request: AgentRequest):
        decision = self.router.route(request.query, request.state, request.history)
        if self.llm and decision.lane == "semantic" and decision.intents == [Intent.GENERAL]:
            return await self._refine_with_llm(request, decision)
        return decision

    async def stream(self, request: AgentRequest) -> AsyncIterator[StreamEvent]:
        trace_id = uuid.uuid4().hex[:12]
        started = time.perf_counter()
        yield self._event("session", trace_id, {
            "requestId": request.request_id, "conversationId": request.conversation_id,
            "runtime": "fastapi", "provider": self.provider, "model": self.model,
        })

        preliminary = self.router.route(request.query, request.state, request.history)
        if self.llm and preliminary.lane == "semantic" and preliminary.intents == [Intent.GENERAL]:
            yield self._event("thinking_start", trace_id, {
                "displayName": "模型语义分析",
                "summary": f"正在由 {self.model} 结合当前问题与最近对话判断意图。",
            })
            decision = await self._refine_with_llm(request, preliminary)
        else:
            decision = preliminary
        yield self._event("route", trace_id, decision.model_dump(mode="json"))
        yield self._event("thinking", trace_id, {
            "agentKey": "semantic_dispatcher", "displayName": "语义调度中心",
            "summary": decision.reasoning_summary,
            "evidence": self._routing_evidence(decision),
            "collapsible": True,
        })
        yield self._event("plan", trace_id, {
            "displayName": "执行计划",
            "summary": self._plan_summary(decision),
            "parallel": len(decision.targets) > 1,
            "steps": [
                {
                    "agentKey": target.key,
                    "displayName": target.display_name,
                    "objective": self._objective(target.intent.value),
                    "priority": target.priority,
                }
                for target in decision.targets
            ],
        })

        for target in decision.targets:
            yield self._event("agent_start", trace_id, target.model_dump(mode="json"))

        tasks = [asyncio.create_task(self._run_target(request, decision, target)) for target in decision.targets]
        results = await asyncio.gather(*tasks)
        results.sort(key=lambda result: next(
            target.priority for target in decision.targets if target.intent == result.intent
        ))

        for result in results:
            for tool_name in result.tool_calls:
                yield self._event("tool_end", trace_id, {
                    "agentKey": result.agent_key, "toolName": tool_name, "status": "ok" if result.success else "error",
                })
            for card in result.cards:
                yield self._event("card", trace_id, {
                    "agentKey": result.agent_key, "displayName": result.display_name,
                    "card": card.model_dump(mode="json"),
                })
            yield self._event("agent_end", trace_id, result.model_dump(mode="json"))

        successful = sum(1 for result in results if result.success)
        yield self._event("composition_start", trace_id, {
            "displayName": "回答生成",
            "summary": (
                f"正在由 {self.model} 综合 Agent 与工具结果。"
                if self.llm else "正在使用确定性离线聚合器整理执行结果。"
            ),
        })

        fallback = self.aggregate(request.query, results)
        final_chunks: list[str] = []
        used_live_generation = False
        if self.llm and self.compose_with_llm:
            try:
                async for delta in self.llm.stream_compose(
                    request.query,
                    self._drafts(results),
                    request.history,
                    {"provider": self.provider, "model": self.model},
                ):
                    if not delta:
                        continue
                    used_live_generation = True
                    final_chunks.append(delta)
                    yield self._event("text", trace_id, {"delta": delta, "agentKey": "response_composer"})
            except (LlmError, httpx.HTTPError, OSError):
                if final_chunks:
                    interrupted = "\n\n模型流式生成中断，请稍后重试。"
                    final_chunks.append(interrupted)
                    yield self._event("text", trace_id, {"delta": interrupted, "agentKey": "response_composer"})
        if not final_chunks:
            final_chunks = self._chunks(fallback, 18)
            for chunk in final_chunks:
                yield self._event("text", trace_id, {"delta": chunk, "agentKey": "response_composer"})
                await asyncio.sleep(0)
        final_text = "".join(final_chunks)

        yield self._event("composition", trace_id, {
            "displayName": "结果汇总",
            "summary": f"已汇总 {len(results)} 个 Agent 的结果，其中 {successful} 个执行成功，共生成 {sum(len(result.cards) for result in results)} 张业务卡片；回答由 {'在线模型' if used_live_generation else '离线聚合器'}生成。",
            "successfulAgents": successful,
            "totalAgents": len(results),
        })

        elapsed_ms = (time.perf_counter() - started) * 1000
        success_intents = [result.intent.value for result in results if result.success]
        yield self._event("trace", trace_id, {
            "elapsedMs": round(elapsed_ms, 2), "agentCount": len(results),
            "successfulIntents": success_intents,
        })
        yield self._event("done", trace_id, {
            "answer": final_text, "state": decision.slots, "intents": [intent.value for intent in decision.intents],
            "successfulIntents": success_intents, "elapsedMs": round(elapsed_ms, 2),
        })

    async def _refine_with_llm(self, request: AgentRequest, decision):
        try:
            classified = await self.llm.classify(request.query, request.history)
        except (LlmError, httpx.HTTPError, OSError):
            return decision
        if not classified:
            return decision
        intents = []
        for value in classified["intents"]:
            try:
                intents.append(Intent(value))
            except ValueError:
                continue
        if not intents:
            return decision
        refined = self.router.finalize(
            request.query, intents, history=request.history, state=request.state,
            lane="llm", confidence=0.86,
            summary=classified.get("summary") or "模型完成语义分类，仍经过副作用门禁。",
        )
        if classified.get("slots"):
            refined = refined.model_copy(update={"slots": {**classified["slots"], **refined.slots}})
        return refined

    async def _compose(self, query: str, results: list[AgentResult]) -> str:
        fallback = self.aggregate(query, results)
        if not self.llm or not self.compose_with_llm:
            return fallback
        drafts = [
            {
                "agent": result.display_name,
                "intent": result.intent.value,
                "success": result.success,
                "text": result.text,
                "cards": [card.model_dump(mode="json") for card in result.cards],
            }
            for result in results
        ]
        try:
            composed = (await self.llm.compose(query, drafts)).strip()
        except (LlmError, httpx.HTTPError, OSError):
            return fallback
        return composed or fallback

    @staticmethod
    def _drafts(results: list[AgentResult]) -> list[dict]:
        return [
            {
                "agent": result.display_name,
                "intent": result.intent.value,
                "success": result.success,
                "text": result.text,
                "cards": [card.model_dump(mode="json") for card in result.cards],
            }
            for result in results
        ]

    async def _run_target(self, request: AgentRequest, decision, target) -> AgentResult:
        async with self.semaphore:
            agent = self.registry.get(target.key)
            try:
                return await agent.run(request, decision, target.intent)
            except Exception as exc:  # boundary: one Agent failure must not cancel siblings
                return AgentResult(
                    agent_key=target.key, display_name=target.display_name, intent=target.intent,
                    text=f"{target.display_name}执行失败，其他事项仍已继续处理。", success=False,
                    error_code=type(exc).__name__,
                )

    @staticmethod
    def aggregate(query: str, results: list[AgentResult]) -> str:
        """Deterministically retain every successful/failed sub-task result.

        This intentionally replaces the legacy "any handoff wins" behavior that
        discarded routing results in mixed-intent requests.
        """
        if len(results) == 1:
            return results[0].text
        sections = ["你的多个差旅事项已经分别处理："]
        for result in results:
            status = "已完成" if result.success else "需重试"
            sections.append(f"- {result.display_name}（{status}）：{result.text}")
        sections.append("相关候选项和业务凭证已整理在下方卡片中。")
        return "\n".join(sections)

    @staticmethod
    def _chunks(text: str, size: int) -> list[str]:
        return [text[index:index + size] for index in range(0, len(text), size)]

    @staticmethod
    def _routing_evidence(decision) -> str:
        slots = "、".join(f"{key}={value}" for key, value in decision.slots.items())
        lane = {"fast": "高置信规则", "context": "上下文续问", "semantic": "语义组合", "llm": "大模型语义分类"}.get(
            decision.lane, decision.lane
        )
        return f"路由方式：{lane}" + (f"；已提取：{slots}" if slots else "；未提取到必要槽位")

    @staticmethod
    def _plan_summary(decision) -> str:
        names = "、".join(target.display_name for target in decision.targets)
        mode = "并行协作" if len(decision.targets) > 1 else "单 Agent 处理"
        return f"采用{mode}，调度：{names}。"

    @staticmethod
    def _objective(intent: str) -> str:
        return {
            "trip_plan": "形成完整行程并整理机票、住宿与天气",
            "travel_search": "查询本轮所需的差旅信息",
            "policy_query": "检索并核对企业差旅制度",
            "approval_create": "校验信息后创建出差申请",
            "approval_status": "查询当前账号的审批进度",
            "general": "直接回答无需业务工具的旅行问题",
        }.get(intent, "处理当前事项")

    @staticmethod
    def _event(event_type: str, trace_id: str, data: dict) -> StreamEvent:
        return StreamEvent(type=event_type, trace_id=trace_id, data=data)


def encode_sse(event: StreamEvent) -> str:
    payload = event.model_dump(mode="json")
    return f"event: {event.type}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"
