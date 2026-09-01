"""Fail closed when an evaluation dataset is small, altered, or contaminated."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path


def normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9\u4e00-\u9fff]", "", text.lower())


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def check(dataset: Path, development_set: Path, expected_sha256: str, minimum_cases: int) -> dict:
    actual_sha256 = hashlib.sha256(dataset.read_bytes()).hexdigest()
    rows = load_jsonl(dataset)
    dev_rows = json.loads(development_set.read_text(encoding="utf-8"))
    normalized = [normalize(row["query"]) for row in rows]
    duplicates = len(normalized) - len(set(normalized))

    dev_queries = [normalize(row["query"]) for row in dev_rows]
    overlaps = []
    for row, candidate in zip(rows, normalized):
        nearest = max((SequenceMatcher(None, candidate, dev).ratio() for dev in dev_queries), default=0.0)
        if nearest >= 0.88:
            overlaps.append({"id": row["id"], "similarity": round(nearest, 4)})

    classes = Counter(intent for row in rows for intent in row["expected_intents"])
    failures = []
    if actual_sha256 != expected_sha256:
        failures.append("SHA-256 mismatch: the frozen dataset changed")
    if len(rows) < minimum_cases:
        failures.append(f"dataset too small: {len(rows)} < {minimum_cases}")
    if duplicates:
        failures.append(f"normalized duplicate queries: {duplicates}")
    if overlaps:
        failures.append(f"possible development-set leakage: {len(overlaps)} rows")

    report = {
        "passed": not failures,
        "cases": len(rows),
        "sha256": actual_sha256,
        "classes": dict(classes),
        "duplicates": duplicates,
        "possibleLeakage": overlaps,
        "failures": failures,
    }
    if failures:
        raise SystemExit(json.dumps(report, ensure_ascii=False, indent=2))
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--development-set", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--minimum-cases", type=int, default=400)
    args = parser.parse_args()
    print(json.dumps(check(args.dataset, args.development_set, args.sha256, args.minimum_cases), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
