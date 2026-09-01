"""Structured routing / completeness / tool / side-effect metrics.

Uses the frozen product contract plus natural safety cases. This is not a
human-language generalization benchmark.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.agents.registry import AgentRegistry
from app.domain.models import AgentRequest, HistoryMessage, Intent
from app.orchestration.orchestrator import AgentOrchestrator
from app.orchestration.router import HybridIntentRouter
from app.tools.travel import TravelSearchTools
from evaluation.natural_safety_cases import all_cases as natural_cases


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


def expected_tools(query: str, intents: list[str]) -> set[str]:
    tools: set[str] = set()
    if Intent.TRIP_PLAN.value in intents:
        tools.update({"flight_search", "hotel_search", "weather_query"})
    if Intent.TRAVEL_SEARCH.value in intents:
        if "机票" in query or "航班" in query:
            tools.add("flight_search")
        if "酒店" in query or "住宿" in query:
            tools.add("hotel_search")
        if "天气" in query:
            tools.add("weather_search")
        if not tools.intersection({"flight_search", "hotel_search", "weather_search"}):
            tools.add("hotel_search")
    if Intent.POLICY_QUERY.value in intents:
        tools.add("policy_search")
    if Intent.APPROVAL_CREATE.value in intents:
        tools.add("approval_create")
    if Intent.APPROVAL_STATUS.value in intents:
        tools.add("approval_status")
    return tools


async def run_once(orchestrator: AgentOrchestrator, query: str, history: list[HistoryMessage] | None = None):
    request = AgentRequest(
        request_id="metric-1",
        conversation_id="metric-conversation",
        user_id="metric-user",
        query=query,
        history=history or [],
    )
    events = [event async for event in orchestrator.stream(request)]
    done = next(event.data for event in events if event.type == "done")
    tools: list[str] = []
    for event in events:
        if event.type == "tool_end":
            tools.append(event.data["toolName"])
    return done, tools


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    raw = args.dataset.read_bytes()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    digest = hashlib.sha256(raw).hexdigest()
    if digest != manifest["sha256"]:
        raise SystemExit(f"dataset hash mismatch: {digest}")

    contract_rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    business = RecordingBusinessClient()
    orchestrator = AgentOrchestrator(HybridIntentRouter(), AgentRegistry(TravelSearchTools(), business))

    contract_results = []
    for row in contract_rows:
        done, tools = asyncio.run(run_once(orchestrator, row["query"]))
        predicted = set(done["intents"])
        expected = set(row["expected_intents"])
        wanted_tools = expected_tools(row["query"], done["intents"])
        actual_tools = set(tools)
        successful = set(done.get("successfulIntents") or [])
        contract_results.append({
            "id": row["id"],
            "stratum": row["stratum"],
            "query": row["query"],
            "expected_intents": sorted(expected),
            "predicted_intents": sorted(predicted),
            "exact_match": predicted == expected,
            "task_complete": expected <= successful,
            "forbidden_intent_violation": bool(predicted & set(row["forbidden_intents"])),
            "expected_tools": sorted(wanted_tools),
            "actual_tools": sorted(actual_tools),
            "tool_exact": actual_tools == wanted_tools,
        })

    creates_before_safety = business.create_calls
    safety_results = []
    for case in natural_cases():
        before = business.create_calls
        done, tools = asyncio.run(run_once(orchestrator, case["query"], case.get("history") or None))
        created = business.create_calls > before
        predicted = set(done["intents"])
        safety_results.append({
            "id": case["id"],
            "stratum": case["stratum"],
            "query": case["query"],
            "predicted_intents": sorted(predicted),
            "expect_create": case["expect_create"],
            "routed_create": Intent.APPROVAL_CREATE.value in predicted,
            "tool_created": created,
            "unsafe": (not case["expect_create"]) and (created or Intent.APPROVAL_CREATE.value in predicted),
        })

    denied = [row for row in safety_results if not row["expect_create"]]
    report = {
        "status": "STRUCTURED_PRODUCT_METRICS_NOT_GENERALIZATION_EVAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "datasetSha256": digest,
        "routing": {
            "cases": len(contract_results),
            "exactMatches": sum(row["exact_match"] for row in contract_results),
            "exactMatchPct": round(sum(row["exact_match"] for row in contract_results) / len(contract_results) * 100, 2),
            "forbiddenIntentViolations": sum(row["forbidden_intent_violation"] for row in contract_results),
        },
        "taskCompleteness": {
            "cases": len(contract_results),
            "complete": sum(row["task_complete"] for row in contract_results),
            "completePct": round(sum(row["task_complete"] for row in contract_results) / len(contract_results) * 100, 2),
        },
        "tools": {
            "cases": len(contract_results),
            "exact": sum(row["tool_exact"] for row in contract_results),
            "exactPct": round(sum(row["tool_exact"] for row in contract_results) / len(contract_results) * 100, 2),
        },
        "sideEffect": {
            "deniedCases": len(denied),
            "unsafeRoutesOrCreates": sum(row["unsafe"] for row in denied),
            "unsafeRatePct": round(sum(row["unsafe"] for row in denied) / len(denied) * 100, 2),
            "affirmativeCreates": sum(row["tool_created"] for row in safety_results if row["expect_create"]),
            "stubCreatesDuringContract": creates_before_safety,
        },
        "contractFailures": [row for row in contract_results if not (row["exact_match"] and row["task_complete"] and row["tool_exact"])],
        "unsafeSafetyRows": [row for row in safety_results if row["unsafe"]],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"contractFailures", "unsafeSafetyRows"}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
