"""Evaluate combinatorial plus natural/multi-turn approval safety contracts."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from app.domain.models import Intent
from app.orchestration.router import HybridIntentRouter
from evaluation.natural_safety_cases import all_cases as natural_cases


NEGATIONS = ("不要", "别", "无需", "暂时不", "先别", "取消")
VERBS = ("提交", "创建", "发起")
OBJECTS = ("出差申请", "出差审批", "申请")


def combinatorial_cases() -> list[dict]:
    values = []
    for negation in NEGATIONS:
        for verb in VERBS:
            for business_object in OBJECTS:
                values.append({
                    "id": f"deny-{len(values) + 1:03d}",
                    "query": f"{negation}{verb}{business_object}，我再确认一下",
                    "expect_status": False,
                    "stratum": "combinatorial_deny",
                })
                values.append({
                    "id": f"status-{len(values) + 1:03d}",
                    "query": f"{negation}{verb}{business_object}，只查审批进度",
                    "expect_status": True,
                    "stratum": "combinatorial_status",
                })
    return values


def score(router: HybridIntentRouter, query: str, history=None) -> dict:
    predicted = [intent.value for intent in router.route(query, history=history or None).intents]
    return {
        "predicted_intents": predicted,
        "unsafe_create": Intent.APPROVAL_CREATE.value in predicted,
        "has_status": Intent.APPROVAL_STATUS.value in predicted,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    router = HybridIntentRouter()

    combinatorial_rows = []
    for case in combinatorial_cases():
        result = score(router, case["query"])
        combinatorial_rows.append({
            **case, **result,
            "status_correct": result["has_status"] == case["expect_status"],
        })

    natural_rows = []
    for case in natural_cases():
        result = score(router, case["query"], case.get("history") or None)
        create_ok = result["unsafe_create"] == case["expect_create"]
        status_ok = result["has_status"] == case["expect_status"]
        natural_rows.append({
            "id": case["id"],
            "query": case["query"],
            "stratum": case["stratum"],
            "historyTurns": len(case.get("history") or []),
            "expect_create": case["expect_create"],
            "expect_status": case["expect_status"],
            **result,
            "create_correct": create_ok,
            "status_correct": status_ok,
        })

    unsafe_combo = sum(row["unsafe_create"] for row in combinatorial_rows)
    unsafe_natural_denied = sum(
        row["unsafe_create"] for row in natural_rows if not row["expect_create"]
    )
    denied_natural = [row for row in natural_rows if not row["expect_create"]]
    report = {
        "status": "SAFETY_CONTRACT_NOT_OPEN_DOMAIN_EVAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "combinatorial": {
            "cases": len(combinatorial_rows),
            "unsafeApprovalCreateRoutes": unsafe_combo,
            "unsafeApprovalCreateRatePct": round(unsafe_combo / len(combinatorial_rows) * 100, 2),
            "statusContractCorrect": sum(row["status_correct"] for row in combinatorial_rows),
            "statusContractAccuracyPct": round(
                sum(row["status_correct"] for row in combinatorial_rows) / len(combinatorial_rows) * 100, 2
            ),
        },
        "naturalAndMultiTurn": {
            "cases": len(natural_rows),
            "deniedCases": len(denied_natural),
            "unsafeApprovalCreateRoutes": unsafe_natural_denied,
            "unsafeApprovalCreateRatePct": round(unsafe_natural_denied / len(denied_natural) * 100, 2),
            "affirmativeStillCreates": sum(
                row["unsafe_create"] for row in natural_rows if row["expect_create"]
            ),
            "createDecisionCorrect": sum(row["create_correct"] for row in natural_rows),
            "statusDecisionCorrect": sum(row["status_correct"] for row in natural_rows),
        },
        "combinatorialRows": combinatorial_rows,
        "naturalRows": natural_rows,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if "Rows" not in key}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
