"""Run the legacy and V2 stacks over the same DEVELOPMENT REGRESSION set.

This script calls real HTTP services and reads each system's persisted assistant
message. It intentionally measures final-answer completeness, not whether a
sub-Agent happened to run in the background. The hand-authored set participated
in implementation tuning and is prohibited from final performance claims.
"""
from __future__ import annotations

import json
import platform
import re
import statistics
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
CASES = json.loads((Path(__file__).parent / "task_completeness_cases.json").read_text(encoding="utf-8"))
REPORT_DIR = ROOT / "docs" / "reports"

EVIDENCE = {
    "travel_search": re.compile(r"天气|机票|酒店|航班|查询|差旅信息"),
    "policy_query": re.compile(r"政策|制度|标准|差标|报销"),
    "trip_plan": re.compile(r"规划|行程"),
}


def parse_sse(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.replace("\r\n", "\n").split("\n\n"):
        event = "message"
        data_lines = []
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
        if data_lines:
            try:
                events.append((event, json.loads("\n".join(data_lines))))
            except json.JSONDecodeError:
                pass
    return events


def legacy_answer(client: httpx.Client, query: str) -> tuple[str, float]:
    session_id = "eval-" + uuid.uuid4().hex[:10]
    started = time.perf_counter()
    response = client.get("http://127.0.0.1:8080/api/chat/stream", params={
        "query": query, "sessionId": session_id, "userId": "eval-user", "tenantId": "tenant-alibaba",
    }, timeout=60)
    response.raise_for_status()
    elapsed = (time.perf_counter() - started) * 1000
    memory = client.get(f"http://127.0.0.1:8080/api/debug/memory/{session_id}", timeout=10).json()
    assistant = [message["text"] for message in memory["messages"] if message["role"] == "assistant"]
    return assistant[-1] if assistant else "", elapsed


def v2_login(client: httpx.Client) -> str:
    response = client.post("http://127.0.0.1:8081/api/v1/auth/login", json={
        "username": "voyage", "password": "Voyage@2026",
    })
    response.raise_for_status()
    return response.json()["accessToken"]


def v2_answer(client: httpx.Client, token: str, query: str) -> tuple[str, float]:
    headers = {"Authorization": f"Bearer {token}"}
    conversation = client.post("http://127.0.0.1:8081/api/v1/conversations", headers=headers,
                               json={"title": "自动评测"}).json()
    started = time.perf_counter()
    response = client.post(
        f"http://127.0.0.1:8081/api/v1/conversations/{conversation['id']}/messages:stream",
        headers=headers, json={"content": query, "requestId": "eval-" + uuid.uuid4().hex, "state": {}},
        timeout=60,
    )
    response.raise_for_status()
    elapsed = (time.perf_counter() - started) * 1000
    messages = client.get(
        f"http://127.0.0.1:8081/api/v1/conversations/{conversation['id']}/messages", headers=headers,
    ).json()
    assistant = [message["content"] for message in messages if message["role"] == "assistant"]
    answer = assistant[-1] if assistant else ""
    # Keep the production workspace clean while preserving raw benchmark evidence in the report.
    client.delete(f"http://127.0.0.1:8081/api/v1/conversations/{conversation['id']}", headers=headers)
    return answer, elapsed


def coverage(answer: str, expected: list[str]) -> tuple[int, dict[str, bool]]:
    matched = {intent: bool(EVIDENCE[intent].search(answer)) for intent in expected}
    return sum(matched.values()), matched


def summarize(rows: list[dict], key: str) -> dict:
    expected = sum(len(row["expected"]) for row in rows)
    matched = sum(row[key]["matched"] for row in rows)
    latencies = [row[key]["latencyMs"] for row in rows]
    return {
        "matchedIntents": matched,
        "expectedIntents": expected,
        "taskCompletenessPct": round(matched / expected * 100, 2),
        "caseFullSuccessPct": round(sum(row[key]["matched"] == len(row["expected"]) for row in rows) / len(rows) * 100, 2),
        "latencyP50Ms": round(statistics.median(latencies), 2),
        "latencyP95Ms": round(sorted(latencies)[min(len(latencies) - 1, int(len(latencies) * .95))], 2),
    }


def main() -> None:
    rows = []
    with httpx.Client() as client:
        token = v2_login(client)
        for case in CASES:
            legacy_text, legacy_ms = legacy_answer(client, case["query"])
            v2_text, v2_ms = v2_answer(client, token, case["query"])
            legacy_count, legacy_evidence = coverage(legacy_text, case["expected"])
            v2_count, v2_evidence = coverage(v2_text, case["expected"])
            rows.append({
                **case,
                "legacy": {"matched": legacy_count, "evidence": legacy_evidence, "latencyMs": round(legacy_ms, 2), "answer": legacy_text},
                "v2": {"matched": v2_count, "evidence": v2_evidence, "latencyMs": round(v2_ms, 2), "answer": v2_text},
            })
            print(f"{case['id']}: legacy={legacy_count}/{len(case['expected'])} v2={v2_count}/{len(case['expected'])}", flush=True)

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {"platform": platform.platform(), "python": platform.python_version(), "caseCount": len(rows)},
        "definition": "persisted final-answer expected-intent evidence coverage",
        "legacy": summarize(rows, "legacy"), "v2": summarize(rows, "v2"), "cases": rows,
    }
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    json_path = REPORT_DIR / "agent-task-completeness.json"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    markdown = f"""# Agent 多意图开发回归（禁止用于最终指标）\n\n> 这 18 条人工用例参与过规则调优，不是独立测试集；关键词判分和延迟数据不得作为泛化指标。\n\n- 生成时间：{report['generatedAt']}\n- 样本数：{len(rows)}\n- 口径：持久化最终回答中的预期意图证据覆盖率\n- 原版：{report['legacy']['taskCompletenessPct']}%（{report['legacy']['matchedIntents']}/{report['legacy']['expectedIntents']}）\n- V2：{report['v2']['taskCompletenessPct']}%（{report['v2']['matchedIntents']}/{report['v2']['expectedIntents']}）\n- 完整成功用例：{report['legacy']['caseFullSuccessPct']}% → {report['v2']['caseFullSuccessPct']}%\n- P95（非公平条件，仅诊断）：{report['legacy']['latencyP95Ms']} ms → {report['v2']['latencyP95Ms']} ms\n\n原始逐例结果见 `agent-task-completeness.json`。\n"""
    (REPORT_DIR / "agent-task-completeness.md").write_text(markdown, encoding="utf-8")
    print(json.dumps({"legacy": report["legacy"], "v2": report["v2"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
