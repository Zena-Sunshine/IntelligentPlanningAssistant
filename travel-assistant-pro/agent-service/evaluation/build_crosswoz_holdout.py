"""Build an auditable external-language holdout from the CrossWOZ test split.

This is deliberately not a template generator. The query text comes unchanged
from a third-party, human-authored corpus collected before VoyageIQ existed.
Only labels are mapped to VoyageIQ's narrower product taxonomy.

The resulting benchmark has a limited and explicit scope:

* hotel / metro / taxi -> travel_search
* restaurant / attraction -> general (unsupported-domain handling)

It does not measure policy or approval intents. Those require an independently
collected enterprise-domain gold set and must never be inferred from this file.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any


COMMIT = "df82c9fdff91b9b130f2d6b89110d3870ba6260e"
SOURCE_URL = "https://github.com/thu-coai/CrossWOZ"
SUPPORTED = {"酒店", "地铁", "出租"}
OUT_OF_SCOPE = {"餐馆", "景点"}
IGNORED = {"General", "一般"}


def stable_rank(value: str) -> str:
    return hashlib.sha256((COMMIT + ":" + value).encode("utf-8")).hexdigest()


def mapped_intent(message: dict[str, Any]) -> tuple[str, str] | None:
    domains = {
        str(act[1])
        for act in message.get("dialog_act", [])
        if isinstance(act, list) and len(act) >= 2 and str(act[1]) not in IGNORED
    }
    if domains and domains <= SUPPORTED:
        return "travel_search", "supported_travel_domain"
    if domains and domains <= OUT_OF_SCOPE:
        return "general", "out_of_scope_domain"
    return None


def load_test(zip_path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(zip_path) as archive:
        json_names = [name for name in archive.namelist() if name.endswith(".json")]
        if len(json_names) != 1:
            raise ValueError(f"expected one JSON file in {zip_path}, found {json_names}")
        with archive.open(json_names[0]) as stream:
            return json.load(stream)


def build(zip_path: Path, output: Path, per_class: int) -> dict[str, Any]:
    dialogues = load_test(zip_path)
    buckets: dict[str, list[dict[str, Any]]] = {"travel_search": [], "general": []}
    seen_text: set[str] = set()

    for dialogue_id, dialogue in dialogues.items():
        for turn_index, message in enumerate(dialogue.get("messages", [])):
            if message.get("role") != "usr":
                continue
            text = str(message.get("content", "")).strip()
            mapping = mapped_intent(message)
            if not text or mapping is None or text in seen_text:
                continue
            intent, stratum = mapping
            seen_text.add(text)
            case_id = f"crosswoz-test-{dialogue_id}-{turn_index}"
            buckets[intent].append({
                "id": case_id,
                "query": text,
                "expected_intents": [intent],
                "stratum": stratum,
                "source": {
                    "dataset": "CrossWOZ",
                    "upstream_split": "test",
                    "upstream_dialogue_id": str(dialogue_id),
                    "upstream_turn_index": turn_index,
                    "commit": COMMIT,
                    "human_authored": True,
                    "text_transformed": False,
                },
            })

    selected: list[dict[str, Any]] = []
    for intent, rows in buckets.items():
        rows.sort(key=lambda row: stable_rank(row["id"]))
        if len(rows) < per_class:
            raise ValueError(f"not enough {intent} examples: need {per_class}, found {len(rows)}")
        selected.extend(rows[:per_class])
    selected.sort(key=lambda row: stable_rank(row["id"]))

    output.parent.mkdir(parents=True, exist_ok=True)
    payload = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in selected)
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(payload)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    return {
        "path": str(output),
        "sha256": digest,
        "cases": len(selected),
        "classes": dict(Counter(row["expected_intents"][0] for row in selected)),
        "sourceUrl": SOURCE_URL,
        "sourceCommit": COMMIT,
        "upstreamSplit": "test",
        "textTransformed": False,
        "limitation": "Only travel_search and unsupported-domain general are covered.",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-class", type=int, default=300)
    args = parser.parse_args()
    print(json.dumps(build(args.source_zip, args.output, args.per_class), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
