"""Evaluate structured router output on the frozen external-language holdout."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from app.orchestration.router import HybridIntentRouter
from evaluation.crosswoz_context import DEFAULT_DIALOGUE_SOURCE, history_for

FROZEN_SHA256 = "393d5d70cc4458441112e71c13e4716b4327217e82df4bcb0db02e7591412a47"


def wilson(successes: int, total: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if total == 0:
        return 0.0, 0.0
    p = successes / total
    denominator = 1 + z * z / total
    centre = p + z * z / (2 * total)
    margin = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total)
    return (centre - margin) / denominator, (centre + margin) / denominator


def summarize(rows: list[dict]) -> dict:
    successes = sum(row["exact_match"] for row in rows)
    low, high = wilson(successes, len(rows))
    confusion: Counter[tuple[str, str]] = Counter()
    for row in rows:
        expected_label = "+".join(row["expected_intents"])
        predicted_label = "+".join(row["predicted_intents"])
        confusion[(expected_label, predicted_label)] += 1
    by_stratum = {}
    for stratum in sorted({row["stratum"] for row in rows}):
        subset = [row for row in rows if row["stratum"] == stratum]
        correct = sum(row["exact_match"] for row in subset)
        stratum_low, stratum_high = wilson(correct, len(subset))
        by_stratum[stratum] = {
            "correct": correct,
            "cases": len(subset),
            "exactMatchPct": round(correct / len(subset) * 100, 2),
            "wilson95Pct": [round(stratum_low * 100, 2), round(stratum_high * 100, 2)],
        }
    return {
        "cases": len(rows),
        "correct": successes,
        "exactMatchPct": round(successes / len(rows) * 100, 2),
        "wilson95Pct": [round(low * 100, 2), round(high * 100, 2)],
        "byStratum": by_stratum,
        "confusion": [
            {"expected": expected, "predicted": predicted, "count": count}
            for (expected, predicted), count in sorted(confusion.items())
        ],
        "failures": [row for row in rows if not row["exact_match"]],
    }


def evaluate(rows: list[dict], *, use_history: bool, dialogue_source: Path | None) -> list[dict]:
    router = HybridIntentRouter()
    results = []
    for row in rows:
        history = history_for(row, dialogue_source) if use_history else []
        predicted = {intent.value for intent in router.route(row["query"], history=history or None).intents}
        expected = set(row["expected_intents"])
        results.append({
            **row,
            "predicted_intents": sorted(predicted),
            "exact_match": expected == predicted,
            "historyTurns": len(history),
        })
    return results


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--dialogue-source", type=Path, default=DEFAULT_DIALOGUE_SOURCE)
    args = parser.parse_args()

    raw = args.dataset.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != FROZEN_SHA256:
        raise SystemExit(f"holdout hash mismatch: {digest} != {FROZEN_SHA256}")

    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    dialogue_source = args.dialogue_source if args.dialogue_source.is_file() else None
    isolated = evaluate(rows, use_history=False, dialogue_source=None)
    contextual = evaluate(rows, use_history=True, dialogue_source=dialogue_source)

    isolated_summary = summarize(isolated)
    contextual_summary = summarize(contextual)
    recovered = [
        row["id"] for row in isolated_summary["failures"]
        if any(item["id"] == row["id"] and item["exact_match"] for item in contextual)
    ]

    report = {
        "status": "LIMITED_EXTERNAL_BENCHMARK_NOT_FULL_PRODUCT_EVAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {"platform": platform.platform(), "python": platform.python_version()},
        "datasetSha256": digest,
        "scope": ["travel_search", "unsupported-domain general"],
        "explicitlyNotCovered": [
            "trip_plan", "policy_query", "approval_create", "approval_status",
            "tool arguments", "answer quality",
        ],
        "baselineIsolatedExactMatchPct": 80.5,
        "baselineIsolatedCorrect": 483,
        "dialogueSourcePresent": dialogue_source is not None,
        "isolatedUtterance": {key: value for key, value in isolated_summary.items() if key != "failures"},
        "withDialogueContext": {key: value for key, value in contextual_summary.items() if key != "failures"},
        "contextRecoveredFromIsolatedFailures": len(recovered),
        "isolatedFailures": isolated_summary["failures"],
        "contextualFailures": contextual_summary["failures"],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    printable = {key: value for key, value in report.items() if key not in {"isolatedFailures", "contextualFailures"}}
    print(json.dumps(printable, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
