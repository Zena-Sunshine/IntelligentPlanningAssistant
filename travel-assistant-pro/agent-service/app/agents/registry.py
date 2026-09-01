from __future__ import annotations

import asyncio
import re
import time
from abc import ABC, abstractmethod

import httpx

from app.domain.models import AgentRequest, AgentResult, Card, Intent, RouteDecision
from app.tools.business import BusinessToolClient
from app.tools.travel import TravelSearchTools


class SpecializedAgent(ABC):
    key: str

    @abstractmethod
    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        raise NotImplementedError


class TripPlannerAgent(SpecializedAgent):
    key = "trip_planner"

    def __init__(self, travel: TravelSearchTools):
        self.travel = travel

    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        started = time.perf_counter()
        departure = decision.slots.get("departure", "杭州")
        destination = decision.slots.get("destination", "上海")
        travel_date = decision.slots.get("date", "待确认")
        flights, hotels, weather = await asyncio.gather(
            self.travel.flights(departure, destination, travel_date),
            self.travel.hotels(destination, travel_date),
            self.travel.weather(destination),
        )
        cards = [
            Card(type="flight", data=flights), Card(type="hotel", data=hotels),
            Card(type="weather", data=weather),
        ]
        text = f"已形成 {departure}前往{destination}的差旅行程建议，机票、住宿和天气信息已整理。"
        return AgentResult(
            agent_key=self.key, display_name="行程规划顾问", intent=intent, text=text,
            cards=cards, tool_calls=["flight_search", "hotel_search", "weather_query"],
            duration_ms=(time.perf_counter() - started) * 1000,
        )


class TravelResearchAgent(SpecializedAgent):
    key = "travel_researcher"

    def __init__(self, travel: TravelSearchTools):
        self.travel = travel

    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        started = time.perf_counter()
        departure = decision.slots.get("departure", "杭州")
        destination = decision.slots.get("destination")
        travel_date = decision.slots.get("date", "待确认")
        cards: list[Card] = []
        tools: list[str] = []
        query = request.query
        travel_object = re.compile(r"机票|航班|酒店|住宿|天气")
        if travel_object.search(query):
            # An explicit object in the current turn is authoritative. Do not
            # replay unrelated tools mentioned earlier in the conversation.
            blob = query
        else:
            recent_context = next(
                (message.content for message in reversed(request.history) if travel_object.search(message.content)),
                "",
            )
            blob = f"{query} {recent_context}"
        if not destination:
            return AgentResult(
                agent_key=self.key, display_name="差旅信息专员", intent=intent,
                text="还需要目的地才能可靠查询。请补充城市，例如“查询武汉天气”或“找北京的酒店”。",
                duration_ms=(time.perf_counter() - started) * 1000,
            )
        tasks = []
        kinds = []
        if "机票" in blob or "航班" in blob:
            tasks.append(self.travel.flights(departure, destination, travel_date)); kinds.append("flight")
        if "酒店" in blob or "住宿" in blob:
            tasks.append(self.travel.hotels(destination, travel_date)); kinds.append("hotel")
        if "天气" in blob:
            tasks.append(self.travel.weather(destination)); kinds.append("weather")
        if not tasks:
            tasks = [self.travel.hotels(destination, travel_date)]; kinds = ["hotel"]
        for kind, data in zip(kinds, await asyncio.gather(*tasks), strict=True):
            cards.append(Card(type=kind, data=data))
            tools.append("weather_query" if kind == "weather" else f"{kind}_search")
        return AgentResult(
            agent_key=self.key, display_name="差旅信息专员", intent=intent,
            text=f"已完成{destination}相关差旅信息查询，共整理 {len(cards)} 类结果。",
            cards=cards, tool_calls=tools, duration_ms=(time.perf_counter() - started) * 1000,
        )


class PolicyAdvisorAgent(SpecializedAgent):
    key = "policy_advisor"

    def __init__(self, business: BusinessToolClient):
        self.business = business

    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        started = time.perf_counter()
        try:
            data = await self.business.search_policy(request.tenant_id, request.query)
        except (httpx.HTTPError, OSError):
            data = {
                "items": [{
                    "title": "境内差旅标准",
                    "content": "国内机票限经济舱；一线城市住宿 400 元/晚，其他城市 350 元/晚。",
                    "source": "企业差旅制度（本地容灾副本）",
                }],
                "degraded": True,
            }
        return AgentResult(
            agent_key=self.key, display_name="企业政策顾问", intent=intent,
            text="已根据企业差旅制度核对相关标准，详细依据见政策卡片。",
            cards=[Card(type="policy", data=data)], tool_calls=["policy_search"],
            duration_ms=(time.perf_counter() - started) * 1000,
        )


class ApprovalAgent(SpecializedAgent):
    key = "approval_specialist"

    def __init__(self, business: BusinessToolClient):
        self.business = business

    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        started = time.perf_counter()
        try:
            if intent == Intent.APPROVAL_STATUS:
                data = await self.business.approval_status(user_id=request.user_id, tenant_id=request.tenant_id)
                text = "已查询当前账号的出差审批状态。"
                tool = "approval_status"
            else:
                data = await self.business.create_approval(
                    user_id=request.user_id, tenant_id=request.tenant_id, request_id=request.request_id,
                    payload={
                        "destination": decision.slots.get("destination", "待补充"),
                        "travelDate": decision.slots.get("date", "待补充"),
                        "budget": decision.slots.get("budget"), "reason": request.query,
                    },
                )
                text = "出差申请已提交，后续可以在会话中继续查询审批进度。"
                tool = "approval_create"
            return AgentResult(
                agent_key=self.key, display_name="审批事务专员", intent=intent, text=text,
                cards=[Card(type="approval", data=data)], tool_calls=[tool],
                duration_ms=(time.perf_counter() - started) * 1000,
            )
        except (httpx.HTTPError, OSError) as exc:
            return AgentResult(
                agent_key=self.key, display_name="审批事务专员", intent=intent,
                text="审批服务暂时不可用，本次没有创建申请，请稍后重试。", success=False,
                error_code="BUSINESS_SERVICE_UNAVAILABLE",
                duration_ms=(time.perf_counter() - started) * 1000,
            )


class ConciergeAgent(SpecializedAgent):
    key = "service_concierge"

    def __init__(self, provider: str = "offline", model: str = "offline"):
        self.provider = provider
        self.model = model

    _ATTRACTIONS: dict[str, tuple[str, ...]] = {
        "上海": ("外滩与北外滩", "上海博物馆", "武康路—安福路街区", "豫园", "陆家嘴滨江"),
        "北京": ("故宫博物院", "天坛公园", "颐和园", "什刹海", "慕田峪长城"),
        "杭州": ("西湖景区", "灵隐寺", "中国茶叶博物馆", "京杭大运河", "良渚古城遗址公园"),
        "成都": ("成都博物馆", "武侯祠", "杜甫草堂", "人民公园", "金沙遗址博物馆"),
        "西安": ("秦始皇帝陵博物院", "陕西历史博物馆", "西安城墙", "大雁塔", "大唐不夜城"),
        "南京": ("南京博物院", "中山陵", "明孝陵", "夫子庙—秦淮河", "侵华日军南京大屠杀遇难同胞纪念馆"),
        "苏州": ("苏州博物馆", "拙政园", "平江路", "虎丘", "金鸡湖"),
        "深圳": ("深圳湾公园", "莲花山公园", "南头古城", "大鹏所城", "深圳博物馆"),
        "广州": ("广东省博物馆", "陈家祠", "沙面", "广州塔", "越秀公园"),
    }

    async def run(self, request: AgentRequest, decision: RouteDecision, intent: Intent) -> AgentResult:
        return AgentResult(
            agent_key=self.key, display_name="旅行问答助手", intent=intent,
            text=self._answer(request.query),
        )

    def _answer(self, query: str) -> str:
        normalized = re.sub(r"\s+", "", query).strip()
        if not normalized or re.fullmatch(r"[?？!！。.，,、…]+", normalized):
            return "我还没有收到完整的问题。你可以告诉我目的地、日期和想了解的事项，例如“上海两天有哪些景点值得去？”。"

        if re.search(r"你是谁|你叫什么|自我介绍|什么助手", normalized):
            return (
                "我是 VoyageIQ 旅行问答助手，也是企业差旅多智能体系统的统一入口。"
                "我能回答旅行问题，也能把机酒天气、企业政策和审批事项分派给对应的专业 Agent。"
            )

        if re.search(r"什么(?:大)?模型|哪个(?:大)?模型|模型是什么|模型版本", normalized):
            if self.provider == "offline":
                return "当前会话没有启用在线大模型，运行的是 VoyageIQ 离线规则编排器；页面顶部的模型标识会显示真实运行模式。"
            return f"当前 VoyageIQ Agent Runtime 接入的是 {self.provider} 的 {self.model} 模型，页面顶部也会显示这一运行时信息。"

        if re.search(r"思考过|会思考|怎么思考|思考过程|推理过程", normalized):
            return (
                "我会先识别意图和上下文，再制定执行计划、调用专业 Agent 或工具，最后综合结果。"
                "右侧展示的是可核验的判断依据和执行阶段；私密思维链不会直接输出。"
            )

        if re.search(r"你好|您好|嗨|hello|hi", normalized, re.IGNORECASE):
            return "你好，我是 VoyageIQ 旅行问答助手。告诉我目的地和需求，我可以直接回答，也可以协调专业 Agent 完成查询或审批。"

        if re.search(r"谢谢|感谢|辛苦了", normalized):
            return "不客气。如果还要比较目的地、补充行程，或核对差旅政策，继续告诉我即可。"

        if re.search(r"景点|哪里好玩|去哪玩|值得去|游玩", normalized):
            city = next((name for name in self._ATTRACTIONS if name in normalized), None)
            if city:
                places = "、".join(self._ATTRACTIONS[city])
                return (
                    f"{city}比较有代表性的选择包括：{places}。"
                    "如果时间有限，可以按“城市地标、博物馆、人文街区”各选一处；告诉我出行天数和偏好后，我还能继续排成顺路的日程。"
                )
            return "可以推荐，但需要先知道城市。请补充目的地、可用天数，以及你偏好自然风景、历史人文还是城市漫步。"

        if re.search(r"能做什么|会什么|功能|帮助", normalized):
            return "我可以规划差旅行程、查询机票酒店和天气、核对企业差旅政策、查询或创建审批，也能回答常见旅行问题。你可以一次提出多个目标。"

        return (
            f"我理解你想了解“{query.strip()}”，但当前信息还不足以给出可靠结论。"
            "请补充目的地、时间或具体目标；如果这是普通旅行问题，我会直接回答，需要业务数据时再调用专业 Agent。"
        )


class AgentRegistry:
    def __init__(
        self,
        travel: TravelSearchTools,
        business: BusinessToolClient,
        provider: str = "offline",
        model: str = "offline",
    ):
        agents: list[SpecializedAgent] = [
            TripPlannerAgent(travel), TravelResearchAgent(travel), PolicyAdvisorAgent(business),
            ApprovalAgent(business), ConciergeAgent(provider, model),
        ]
        self._agents = {agent.key: agent for agent in agents}

    def get(self, key: str) -> SpecializedAgent:
        if key not in self._agents:
            raise KeyError(f"Agent not registered: {key}")
        return self._agents[key]
