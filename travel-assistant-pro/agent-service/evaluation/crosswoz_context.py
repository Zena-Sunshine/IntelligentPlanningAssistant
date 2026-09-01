"""Attach prior CrossWOZ turns at evaluation time without mutating the frozen holdout."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from app.domain.models import HistoryMessage

DEFAULT_DIALOGUE_SOURCE = (
    Path(__file__).resolve().parents[2]
    / "docs" / "reports" / "sources" / "CrossWOZ" / "data" / "crosswoz" / "test-unpacked" / "test.json"
)


@lru_cache(maxsize=1)
def load_dialogues(path: str) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def history_for(row: dict, dialogue_source: Path | None, limit: int = 8) -> list[HistoryMessage]:
    if dialogue_source is None or not dialogue_source.is_file():
        return []
    source = row.get("source") or {}
    dialogue_id = str(source.get("upstream_dialogue_id", ""))
    turn_index = source.get("upstream_turn_index")
    if not dialogue_id or not isinstance(turn_index, int):
        return []
    dialogues = load_dialogues(str(dialogue_source))
    dialogue = dialogues.get(dialogue_id) or dialogues.get(str(int(dialogue_id)))
    if not dialogue:
        return []
    prior = dialogue.get("messages", [])[:turn_index]
    history: list[HistoryMessage] = []
    for message in prior[-limit:]:
        role = "user" if message.get("role") == "usr" else "assistant"
        content = str(message.get("content", "")).strip()
        if content:
            history.append(HistoryMessage(role=role, content=content))
    return history
