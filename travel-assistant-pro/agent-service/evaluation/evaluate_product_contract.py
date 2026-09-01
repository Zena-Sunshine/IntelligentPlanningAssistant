"""Evaluate the frozen product contract without hiding any failed row."""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from app.orchestration.router import HybridIntentRouter


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
        raise SystemExit(f"dataset hash mismatch: {digest} != {manifest['sha256']}")

    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    if any(row.get("eligible_for_generalization_metric") for row in rows):
        raise SystemExit("product contract must not be marked as a generalization metric")

    router = HybridIntentRouter()
    results: list[dict[str, object]] = []
    for row in rows:
        predicted = {intent.value for intent in router.route(row["query"]).intents}
        expected = set(row["expected_intents"])
        forbidden = set(row["forbidden_intents"])
        results.append({
            **row,
            "predicted_intents": sorted(predicted),
            "exact_match": predicted == expected,
            "forbidden_intent_violation": bool(predicted & forbidden),
        })

    by_stratum = {}
    for stratum in sorted({str(row["stratum"]) for row in results}):
        subset = [row for row in results if row["stratum"] == stratum]
        by_stratum[stratum] = {
            "cases": len(subset),
            "exactMatches": sum(bool(row["exact_match"]) for row in subset),
            "forbiddenViolations": sum(bool(row["forbidden_intent_violation"]) for row in subset),
        }

    report = {
        "status": "SYNTHETIC_PRODUCT_CONTRACT_NOT_GENERALIZATION_EVAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "datasetSha256": digest,
        "cases": len(results),
        "exactMatches": sum(bool(row["exact_match"]) for row in results),
        "forbiddenIntentViolations": sum(bool(row["forbidden_intent_violation"]) for row in results),
        "byStratum": by_stratum,
        "predictionPatterns": dict(sorted(Counter("+".join(row["predicted_intents"]) for row in results).items())),
        "failures": [row for row in results if not row["exact_match"] or row["forbidden_intent_violation"]],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "failures"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
