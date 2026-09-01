from functools import lru_cache

from app.agents.registry import AgentRegistry
from app.config import get_settings
from app.llm.client import LlmClient
from app.orchestration.orchestrator import AgentOrchestrator
from app.orchestration.router import HybridIntentRouter
from app.tools.business import BusinessToolClient
from app.tools.travel import TravelSearchTools


@lru_cache
def get_orchestrator() -> AgentOrchestrator:
    settings = get_settings()
    llm = LlmClient(settings)
    registry = AgentRegistry(
        TravelSearchTools(), BusinessToolClient(settings),
        provider=settings.effective_provider(), model=settings.effective_model(),
    )
    return AgentOrchestrator(
        HybridIntentRouter(),
        registry,
        settings.max_parallel_agents,
        llm=llm,
        provider=settings.effective_provider(),
        model=settings.effective_model(),
    )
