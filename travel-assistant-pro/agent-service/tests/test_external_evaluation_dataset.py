from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path


EXPECTED_SHA256 = "393d5d70cc4458441112e71c13e4716b4327217e82df4bcb0db02e7591412a47"


def test_crosswoz_holdout_is_frozen_balanced_and_unmodified() -> None:
    dataset = Path(__file__).parents[1] / "evaluation" / "datasets" / "crosswoz_external_holdout.jsonl"
    rows = [json.loads(line) for line in dataset.read_text(encoding="utf-8").splitlines() if line]

    assert hashlib.sha256(dataset.read_bytes()).hexdigest() == EXPECTED_SHA256
    assert len(rows) == 600
    assert len({row["query"] for row in rows}) == 600
    assert Counter(row["expected_intents"][0] for row in rows) == {
        "general": 300,
        "travel_search": 300,
    }
    assert all(row["source"]["human_authored"] for row in rows)
    assert all(not row["source"]["text_transformed"] for row in rows)
