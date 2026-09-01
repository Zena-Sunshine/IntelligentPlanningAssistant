from __future__ import annotations

from datetime import date
from typing import Any


class TravelSearchTools:
    """Deterministic local provider behind a production-replaceable tool port."""

    async def flights(self, departure: str, destination: str, travel_date: str) -> dict[str, Any]:
        seed = abs(hash(f"{departure}:{destination}"))
        carriers = ("MU", "CA", "CZ")
        rows = []
        for index, carrier in enumerate(carriers):
            hour = 8 + (seed + index * 3) % 10
            rows.append({
                "flightNo": f"{carrier}{1200 + (seed + index * 97) % 8000}",
                "departureTime": f"{hour:02d}:{index * 15:02d}",
                "arrivalTime": f"{hour + 2:02d}:{index * 15:02d}",
                "cabin": "经济舱",
                "price": 420 + (seed + index * 73) % 380,
            })
        return {"route": f"{departure} → {destination}", "date": travel_date, "items": rows}

    async def hotels(self, city: str, check_in: str, nights: int = 1) -> dict[str, Any]:
        seed = abs(hash(city))
        brands = ("全季酒店", "亚朵酒店", "桔子水晶")
        rows = []
        for index, brand in enumerate(brands):
            price = 290 + (seed + index * 67) % 190
            rows.append({
                "name": f"{city}{brand}", "stars": 4 + index % 2,
                "nightlyPrice": price, "withinPolicy": price <= 400,
            })
        return {"city": city, "checkIn": check_in, "nights": nights, "items": rows}

    async def weather(self, city: str) -> dict[str, Any]:
        seed = abs(hash(city))
        conditions = ("晴", "多云", "小雨", "阴")
        high = 24 + seed % 8
        return {
            "city": city, "date": str(date.today()), "condition": conditions[seed % len(conditions)],
            "low": high - 7, "high": high, "advice": "注意早晚温差，建议携带轻薄外套。",
        }

