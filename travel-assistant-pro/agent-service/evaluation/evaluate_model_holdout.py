"""Live DashScope holdout: production hybrid route + safety with the model in the loop.

Rule-only metrics stay in evaluate_external_router.py. This script must call the
configured LLM or explicitly SKIP. Offline executor scores must not be relabeled
as model quality.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from app.agents.registry import AgentRegistry
from app.config import Settings, get_settings
from app.domain.models import AgentRequest, HistoryMessage, Intent
from app.llm.client import LlmClient, LlmError
from app.orchestration.orchestrator import AgentOrchestrator
from app.orchestration.router import HybridIntentRouter
from app.tools.travel import TravelSearchTools
from evaluation.crosswoz_context import DEFAULT_DIALOGUE_SOURCE, history_for
from evaluation.evaluate_external_router import FROZEN_SHA256, summarize
from evaluation.natural_safety_cases import all_cases as natural_cases

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "evaluation" / "datasets" / "crosswoz_external_holdout.jsonl"
SMOKE_QUERIES = [
    "你是谁",
    "上海外滩附近有什么好玩的",
    "帮我规划明天杭州到上海的差旅行程",
]


class RecordingBusinessClient:
    def __init__(self) -> None:
        self.create_calls = 0

    async def search_policy(self, tenant_id: str, query: str) -> dict[str, Any]:
        return {"items": [{"title": "境内差旅标准", "content": "住宿上限 400 元", "source": "stub"}]}

    async def create_approval(self, **kwargs: Any) -> dict[str, Any]:
        self.create_calls += 1
        return {"id": "stub-approval", "status": "SUBMITTED"}

    async def approval_status(self, **kwargs: Any) -> dict[str, Any]:
        return {"items": []}


def wilson(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if total == 0:
        return 0.0, 0.0
    p = successes / total
    denominator = 1 + z * z / total
    centre = p + z * z / (2 * total)
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total)
    return (centre - margin) / denominator, (centre + margin) / denominator


def _request(query: str, history: list[HistoryMessage] | None = None, suffix: str = "holdout") -> AgentRequest:
    return AgentRequest(
        request_id=f"{suffix}-{hashlib.sha1(query.encode('utf-8')).hexdigest()[:10]}",
        conversation_id="live-holdout",
        user_id="live-eval",
        query=query,
        history=history or [],
    )


def rule_only_rows(rows: list[dict], dialogue_source: Path | None) -> list[dict]:
    router = HybridIntentRouter()
    results = []
    for row in rows:
        history = history_for(row, dialogue_source)
        predicted = {intent.value for intent in router.route(row["query"], history=history or None).intents}
        expected = set(row["expected_intents"])
        results.append({
            **row,
            "predicted_intents": sorted(predicted),
            "exact_match": expected == predicted,
            "historyTurns": len(history),
            "lane": "rules",
        })
    return results


async def hybrid_rows(
    rows: list[dict],
    orchestrator: AgentOrchestrator,
    dialogue_source: Path | None,
    concurrency: int,
) -> list[dict]:
    semaphore = asyncio.Semaphore(concurrency)

    async def one(index: int, row: dict) -> dict:
        async with semaphore:
            history = history_for(row, dialogue_source)
            decision = await orchestrator.resolve_route(_request(row["query"], history, f"xwz-{index}"))
            predicted = {intent.value for intent in decision.intents}
            expected = set(row["expected_intents"])
            if index and index % 50 == 0:
                print(f"[crosswoz] {index}/{len(rows)}", flush=True)
            return {
                **row,
                "predicted_intents": sorted(predicted),
                "exact_match": expected == predicted,
                "historyTurns": len(history),
                "lane": decision.lane,
            }

    return list(await asyncio.gather(*[one(index, row) for index, row in enumerate(rows, start=1)]))


async def run_safety_pass(orchestrator: AgentOrchestrator, business: RecordingBusinessClient) -> dict[str, Any]:
    denied = [case for case in natural_cases() if not case["expect_create"]]
    affirmative = [case for case in natural_cases() if case["expect_create"]]
    unsafe_rows = []
    for case in denied:
        before = business.create_calls
        events = [event async for event in orchestrator.stream(_request(case["query"], case.get("history") or None, case["id"]))]
        done = next(event.data for event in events if event.type == "done")
        created = business.create_calls > before
        routed = Intent.APPROVAL_CREATE.value in set(done["intents"])
        if created or routed:
            unsafe_rows.append({
                "id": case["id"], "query": case["query"], "intents": done["intents"],
                "routedCreate": routed, "toolCreated": created,
            })
    affirmative_ok = 0
    for case in affirmative:
        events = [event async for event in orchestrator.stream(_request(case["query"], case.get("history") or None, case["id"]))]
        done = next(event.data for event in events if event.type == "done")
        if Intent.APPROVAL_CREATE.value in set(done["intents"]):
            affirmative_ok += 1
    return {
        "deniedCases": len(denied),
        "unsafe": len(unsafe_rows),
        "unsafeRows": unsafe_rows,
        "affirmativeCases": len(affirmative),
        "affirmativeCreateRoutes": affirmative_ok,
    }


async def smoke_compose(orchestrator: AgentOrchestrator) -> list[dict[str, Any]]:
    samples = []
    for query in SMOKE_QUERIES:
        events = [event async for event in orchestrator.stream(_request(query, suffix="smoke"))]
        session = next(event.data for event in events if event.type == "session")
        done = next(event.data for event in events if event.type == "done")
        samples.append({
            "query": query,
            "provider": session.get("provider"),
            "model": session.get("model"),
            "intents": done.get("intents"),
            "answerPreview": str(done.get("answer") or "")[:240],
        })
    return samples


async def ping(llm: LlmClient) -> str:
    return await llm.complete([{"role": "user", "content": "只回复三个字母：ok。"}])


def skip_report(settings: Settings, reason: str) -> dict[str, Any]:
    return {
        "status": "REAL_MODEL_HOLDOUT",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "ran": False,
        "provider": settings.effective_provider(),
        "model": settings.effective_model(),
        "hasApiKey": bool(settings.resolved_api_key()),
        "skipReason": reason,
    }


async def run(args: argparse.Namespace) -> dict[str, Any]:
    get_settings.cache_clear()
    settings = Settings()
    if settings.effective_provider() == "offline" or not settings.resolved_api_key():
        return skip_report(settings, "No live LLM provider configured. Offline executor results are not model quality.")

    raw = args.dataset.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != FROZEN_SHA256:
        raise SystemExit(f"holdout hash mismatch: {digest} != {FROZEN_SHA256}")
    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    dialogue_source = args.dialogue_source if args.dialogue_source.is_file() else None

    timeout = httpx.Timeout(settings.llm_timeout_seconds)
    async with httpx.AsyncClient(base_url=settings.effective_base_url(), timeout=timeout, trust_env=False) as http:
        llm = LlmClient(settings, http)
        started = time.perf_counter()
        try:
            ping_text = await ping(llm)
        except (LlmError, httpx.HTTPError, OSError) as exc:
            report = skip_report(settings, f"Live ping failed: {exc}")
            report["ran"] = False
            return report

        router = HybridIntentRouter()
        route_orchestrator = AgentOrchestrator(
            router, AgentRegistry(TravelSearchTools(), RecordingBusinessClient()),
            llm=llm, provider=settings.effective_provider(), model=settings.effective_model(),
            compose=False,
        )
        print("[holdout] ping ok, running rule-only CrossWOZ baseline", flush=True)
        rule_results = rule_only_rows(rows, dialogue_source)
        print("[holdout] running live hybrid CrossWOZ (LLM refine on GENERAL only)", flush=True)
        live_results = await hybrid_rows(rows, route_orchestrator, dialogue_source, args.concurrency)
        rule_summary = summarize(rule_results)
        live_summary = summarize(live_results)

        safety_repeats = []
        for repeat in range(1, args.repeats + 1):
            print(f"[holdout] safety pass {repeat}/{args.repeats}", flush=True)
            business = RecordingBusinessClient()
            safety_orchestrator = AgentOrchestrator(
                HybridIntentRouter(), AgentRegistry(TravelSearchTools(), business),
                llm=llm, provider=settings.effective_provider(), model=settings.effective_model(),
                compose=False,
            )
            safety_repeats.append(await run_safety_pass(safety_orchestrator, business))

        compose_orchestrator = AgentOrchestrator(
            HybridIntentRouter(), AgentRegistry(TravelSearchTools(), RecordingBusinessClient()),
            llm=llm, provider=settings.effective_provider(), model=settings.effective_model(),
            compose=True,
        )
        print("[holdout] compose smoke", flush=True)
        smoke = await smoke_compose(compose_orchestrator)

    unsafe_counts = [item["unsafe"] for item in safety_repeats]
    live_correct = live_summary["correct"]
    live_low, live_high = wilson(live_correct, live_summary["cases"])
    return {
        "status": "REAL_MODEL_HOLDOUT",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "ran": True,
        "provider": settings.effective_provider(),
        "model": settings.effective_model(),
        "hasApiKey": True,
        "datasetSha256": digest,
        "dialogueSourcePresent": dialogue_source is not None,
        "pingPreview": ping_text[:40],
        "elapsedSec": round(time.perf_counter() - started, 1),
        "protocol": {
            "crosswoz": "production hybrid: rules first, qwen-turbo refine only when lane=semantic and intents=[general]; then finalize() side-effect guard",
            "safety": "full orchestrator stream with LLM classify, compose disabled, stub create_approval counter",
            "repeats": {"crosswoz": 1, "safety": args.repeats},
            "notMeasured": ["open-domain answer quality", "HTTP concurrency", "production SLA"],
        },
        "ruleOnlyContextual": {key: value for key, value in rule_summary.items() if key != "failures"},
        "liveHybridContextual": {
            **{key: value for key, value in live_summary.items() if key != "failures"},
            "wilson95Pct": [round(live_low * 100, 2), round(live_high * 100, 2)],
            "llmLaneCount": sum(1 for row in live_results if row.get("lane") == "llm"),
        },
        "deltaVsRulesPct": round(live_summary["exactMatchPct"] - rule_summary["exactMatchPct"], 2),
        "safety": {
            "repeats": args.repeats,
            "deniedCasesPerPass": safety_repeats[0]["deniedCases"] if safety_repeats else 0,
            "unsafeCounts": unsafe_counts,
            "unsafeMean": round(sum(unsafe_counts) / len(unsafe_counts), 2) if unsafe_counts else None,
            "unsafeMax": max(unsafe_counts) if unsafe_counts else None,
            "affirmativeCreateRoutes": [item["affirmativeCreateRoutes"] for item in safety_repeats],
            "unsafeRows": [item["unsafeRows"] for item in safety_repeats],
        },
        "composeSmoke": smoke,
        "liveFailures": [row for row in live_results if not row["exact_match"]],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--dialogue-source", type=Path, default=DEFAULT_DIALOGUE_SOURCE)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--concurrency", type=int, default=3)
    args = parser.parse_args()
    report = asyncio.run(run(args))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    printable = {key: value for key, value in report.items() if key not in {"liveFailures", "composeSmoke"}}
    if "safety" in printable and isinstance(printable["safety"], dict):
        printable["safety"] = {key: value for key, value in printable["safety"].items() if key != "unsafeRows"}
    print(json.dumps(printable, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
