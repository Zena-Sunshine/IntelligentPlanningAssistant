from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from pydantic_settings import BaseSettings, SettingsConfigDict

load_dotenv(Path(__file__).resolve().parent.parent / ".env")


class Settings(BaseSettings):
    app_name: str = "VoyageIQ Agent Runtime"
    environment: str = "dev"
    internal_service_key: str = "voyageiq-local-internal-key"
    business_base_url: str = "http://localhost:8081"
    tool_timeout_seconds: float = 3.0
    max_parallel_agents: int = 4
    provider: str = "auto"
    llm_model: str = ""
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_timeout_seconds: float = 20.0
    fallback_to_offline: bool = True

    model_config = SettingsConfigDict(env_prefix="VOYAGEIQ_AGENT_", env_file=".env", extra="ignore")

    def resolved_api_key(self) -> str:
        return (
            self.llm_api_key
            or os.getenv("DASHSCOPE_API_KEY", "")
            or os.getenv("OPENAI_API_KEY", "")
        ).strip()

    def effective_provider(self) -> str:
        requested = (self.provider or "auto").strip().lower()
        if requested == "auto":
            alias = os.getenv("MODEL_PROVIDER", "").strip().lower()
            if alias in {"dashscope", "openai", "offline"}:
                requested = alias
        key = self.resolved_api_key()
        if requested == "offline":
            return "offline"
        if requested in {"dashscope", "openai"}:
            return requested if key or not self.fallback_to_offline else "offline"
        if not key:
            return "offline"
        if os.getenv("OPENAI_API_KEY") and not os.getenv("DASHSCOPE_API_KEY") and not self.llm_api_key:
            return "openai"
        return "dashscope"

    def effective_model(self) -> str:
        if self.llm_model:
            return self.llm_model
        dashscope_model = os.getenv("DASHSCOPE_MODEL", "").strip()
        if dashscope_model and self.effective_provider() == "dashscope":
            return dashscope_model
        return "gpt-4o-mini" if self.effective_provider() == "openai" else "qwen-plus"

    def effective_base_url(self) -> str:
        if self.llm_base_url:
            return self.llm_base_url.rstrip("/")
        if self.effective_provider() == "openai":
            return "https://api.openai.com/v1"
        return "https://dashscope.aliyuncs.com/compatible-mode/v1"


@lru_cache
def get_settings() -> Settings:
    return Settings()
