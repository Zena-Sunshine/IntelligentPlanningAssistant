import hashlib
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).parents[1]
DATASET = ROOT / "evaluation" / "datasets" / "product_contract_240.jsonl"
MANIFEST = ROOT / "evaluation" / "PRODUCT_CONTRACT_MANIFEST.json"


def test_product_contract_is_frozen_balanced_and_explicitly_synthetic() -> None:
    raw = DATASET.read_bytes()
    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    assert len(rows) == manifest["cases"] == 240
    assert len({row["query"] for row in rows}) == 240
    assert hashlib.sha256(raw).hexdigest() == manifest["sha256"]
    assert manifest["eligibleForGeneralizationMetric"] is False
    assert all(row["source_type"] == "deterministic_product_contract" for row in rows)
    assert not any(row["eligible_for_generalization_metric"] for row in rows)
    assert set(Counter(row["stratum"] for row in rows).values()) == {30}
