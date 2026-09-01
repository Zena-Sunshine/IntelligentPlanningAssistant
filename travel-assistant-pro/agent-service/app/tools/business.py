from __future__ import annotations

from typing import Any

import httpx

from app.config import Settings


class BusinessToolClient:
    def __init__(self, settings: Settings):
        self._base_url = settings.business_base_url.rstrip("/")
        self._headers = {"X-Internal-Service-Key": settings.internal_service_key}
        self._timeout = settings.tool_timeout_seconds

    async def search_policy(self, tenant_id: str, query: str) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=self._timeout, trust_env=False) as client:
            response = await client.post(
                f"{self._base_url}/internal/v1/policies/search",
                headers=self._headers,
                json={"tenantId": tenant_id, "query": query, "limit": 3},
            )
            response.raise_for_status()
            return response.json()

    async def create_approval(
        self, *, user_id: str, tenant_id: str, request_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=self._timeout, trust_env=False) as client:
            response = await client.post(
                f"{self._base_url}/internal/v1/approvals",
                headers={**self._headers, "Idempotency-Key": request_id},
                json={"userId": user_id, "tenantId": tenant_id, **payload},
            )
            response.raise_for_status()
            return response.json()

    async def approval_status(self, *, user_id: str, tenant_id: str) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=self._timeout, trust_env=False) as client:
            response = await client.get(
                f"{self._base_url}/internal/v1/approvals",
                headers=self._headers,
                params={"userId": user_id, "tenantId": tenant_id},
            )
            response.raise_for_status()
            return response.json()
