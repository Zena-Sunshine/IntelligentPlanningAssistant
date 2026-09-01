from __future__ import annotations

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from sse_starlette.sse import EventSourceResponse

from app.config import Settings, get_settings
from app.dependencies import get_orchestrator
from app.domain.models import AgentRequest
from app.orchestration.orchestrator import AgentOrchestrator

app = FastAPI(
    title="VoyageIQ Agent Runtime",
    version="2.0.0",
    description="Internal multi-agent orchestration service. Business clients must enter through Spring Boot.",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[], allow_credentials=False, allow_methods=["GET", "POST"], allow_headers=["*"],
)


def verify_internal_key(
    x_internal_service_key: str = Header(default=""),
    settings: Settings = Depends(get_settings),
) -> None:
    if x_internal_service_key != settings.internal_service_key:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid internal service identity")


@app.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    provider = settings.effective_provider()
    return {
        "status": "up",
        "service": "agent-runtime",
        "provider": provider,
        "model": settings.effective_model() if provider != "offline" else "offline",
    }


@app.post("/internal/v1/agent/chat/stream", dependencies=[Depends(verify_internal_key)])
async def chat_stream(
    request: AgentRequest,
    orchestrator: AgentOrchestrator = Depends(get_orchestrator),
) -> EventSourceResponse:
    async def events():
        async for event in orchestrator.stream(request):
            # A structured SSE mapping avoids double-encoding frames and preserves event names.
            yield {
                "event": event.type,
                "id": f"{event.trace_id}:{event.timestamp.timestamp()}",
                "data": event.model_dump_json(),
            }

    return EventSourceResponse(events(), ping=15)
