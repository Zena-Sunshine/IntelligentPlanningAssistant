from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field


class Intent(StrEnum):
    TRIP_PLAN = "trip_plan"
    TRAVEL_SEARCH = "travel_search"
    POLICY_QUERY = "policy_query"
    APPROVAL_CREATE = "approval_create"
    APPROVAL_STATUS = "approval_status"
    GENERAL = "general"


class CollaborationMode(StrEnum):
    ROUTING = "routing"
    HANDOFF = "handoff"


class HistoryMessage(BaseModel):
    role: str
    content: str


class AgentRequest(BaseModel):
    request_id: str
    conversation_id: str
    user_id: str
    tenant_id: str = "default"
    query: str = Field(min_length=1, max_length=4000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=20)
    state: dict[str, Any] = Field(default_factory=dict)


class AgentTarget(BaseModel):
    key: str
    display_name: str
    intent: Intent
    mode: CollaborationMode
    priority: int = 100


class RouteDecision(BaseModel):
    lane: str
    intents: list[Intent]
    targets: list[AgentTarget]
    rewritten_query: str
    confidence: float = Field(ge=0, le=1)
    reasoning_summary: str
    slots: dict[str, str] = Field(default_factory=dict)


class Card(BaseModel):
    type: str
    data: dict[str, Any]


class AgentResult(BaseModel):
    agent_key: str
    display_name: str
    intent: Intent
    text: str
    cards: list[Card] = Field(default_factory=list)
    tool_calls: list[str] = Field(default_factory=list)
    success: bool = True
    error_code: str | None = None
    duration_ms: float = 0


class StreamEvent(BaseModel):
    type: str
    trace_id: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    data: dict[str, Any] = Field(default_factory=dict)

