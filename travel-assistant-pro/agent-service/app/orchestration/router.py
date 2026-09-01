from __future__ import annotations

import re
from datetime import date, timedelta

from app.domain.models import AgentTarget, CollaborationMode, HistoryMessage, Intent, RouteDecision


TARGETS: dict[Intent, AgentTarget] = {
    Intent.TRIP_PLAN: AgentTarget(
        key="trip_planner", display_name="行程规划顾问", intent=Intent.TRIP_PLAN,
        mode=CollaborationMode.ROUTING, priority=10,
    ),
    Intent.TRAVEL_SEARCH: AgentTarget(
        key="travel_researcher", display_name="差旅信息专员", intent=Intent.TRAVEL_SEARCH,
        mode=CollaborationMode.HANDOFF, priority=20,
    ),
    Intent.POLICY_QUERY: AgentTarget(
        key="policy_advisor", display_name="企业政策顾问", intent=Intent.POLICY_QUERY,
        mode=CollaborationMode.ROUTING, priority=30,
    ),
    Intent.APPROVAL_CREATE: AgentTarget(
        key="approval_specialist", display_name="审批事务专员", intent=Intent.APPROVAL_CREATE,
        mode=CollaborationMode.ROUTING, priority=40,
    ),
    Intent.APPROVAL_STATUS: AgentTarget(
        key="approval_specialist", display_name="审批事务专员", intent=Intent.APPROVAL_STATUS,
        mode=CollaborationMode.ROUTING, priority=40,
    ),
    Intent.GENERAL: AgentTarget(
        key="service_concierge", display_name="旅行问答助手", intent=Intent.GENERAL,
        mode=CollaborationMode.HANDOFF, priority=90,
    ),
}


class HybridIntentRouter:
    """High-confidence rules first, composable semantic rules second.

    The output is a strict contract. An LLM-based classifier can replace the semantic
    stage without changing the orchestrator or frontend event protocol.
    """

    _fast_rules = (
        (re.compile(r"^(?:帮我|为我|开始)?(?:规划|制定).*(?:行程|出差)"), Intent.TRIP_PLAN),
        (re.compile(r"^(?:帮我|为我|立即)?(?:提交|创建|发起).*(?:申请|审批)"), Intent.APPROVAL_CREATE),
    )
    _semantic_rules = (
        (Intent.TRIP_PLAN, re.compile(r"规划|行程方案|出差安排|差旅方案")),
        (Intent.TRAVEL_SEARCH, re.compile(r"机票|航班|酒店|住宿|天气|高铁|火车票|地铁|出租|打车")),
        (Intent.POLICY_QUERY, re.compile(r"报销|差标|标准|政策|规定|制度|材料|额度|超标|合规")),
        (Intent.APPROVAL_CREATE, re.compile(
            r"(?:提交|创建|发起|提|交).{0,24}(?:申请|审批|出差单)|申请.{0,24}出差|走.{0,8}审批"
        )),
        (Intent.APPROVAL_STATUS, re.compile(
            r"(?:审批|审批单|申请).{0,10}(?:进度|状态|结果|到哪|哪一步)"
            r"|(?:进度|状态|结果).{0,8}(?:审批|申请)"
        )),
    )
    _cities = (
        "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "西安", "武汉", "南京",
        "苏州", "厦门", "青岛", "大连", "昆明", "贵阳", "长沙", "合肥", "郑州", "天津",
        "宁波", "无锡", "福州", "济南", "太原", "沈阳", "长春", "南昌", "海口", "三亚",
    )

    _city_alternation = "|".join(map(re.escape, _cities))
    _route_pattern = re.compile(rf"({_city_alternation})(?:到|去|往|飞)({_city_alternation})")
    _destination_pattern = re.compile(r"去([\u4e00-\u9fa5]{2,4})(?:出差|开会|办事|旅行)")
    _approval_action = re.compile(
        r"(?:提交|创建|发起|提|交).{0,24}(?:申请|审批|出差单)|申请.{0,24}出差|走.{0,8}审批"
    )
    _approval_denied = re.compile(
        r"(?:不要|不想|不用|不必|无需|暂时不|先别|先不|先不用|别|取消|算了|暂缓|先放放|以后再说|还是先别|不是)"
        r"(?:立即|现在|再|真的|帮我|先别|先不|暂时)?"
        r".{0,10}(?:提交|创建|发起|提|交|申请|走)"
    )
    _approval_deferred = re.compile(
        r"(?:如果|等到|等|待|确认|核对|考虑).{0,24}(?:再|之后|以后|后|就).{0,8}"
        r"(?:提交|创建|发起|提|申请|走)"
        r"|(?:领导|还没|尚未).{0,12}(?:点头|批准|同意)"
    )
    _status_follow_up = re.compile(r"进度|状态|到哪|哪一步")
    _follow_up_cue = re.compile(
        r"价格|多少钱|多钱|地址|电话|位置|评分|周边|怎么走|还有吗|哪家|哪个|哪一个"
        r"|怎么样|具体|咨询|房型|经济型|舒适型|豪华型|进度|状态|到哪|哪一步"
    )
    _referring = re.compile(
        r"(?:^|[，,。！？\s])(?:那|那么|它|这个|这家|那家|它家|那里|刚才|上面|前面|这几家)"
    )
    _travel_context = re.compile(r"酒店|住宿|机票|航班|高铁|火车票|天气|地铁|出租车|打车")
    _out_of_scope_context = re.compile(r"餐馆|餐厅|饭店|景点|门票|游玩")
    _oos_primary = re.compile(
        r"(?:想找|找家|找一|给我推荐|推荐一家|推荐一个|有什么好).{0,16}(?:餐馆|餐厅|饭店|景点)"
        r"|(?:餐馆|餐厅|饭店).{0,12}(?:电话|地址|推荐|人均|哪)"
        r"|人均消费"
        r"|门票"
        r"|这道菜|能吃到|推荐菜"
        r"|好玩的景点"
    )
    _policy_context = re.compile(r"报销|差标|标准|政策|规定|制度|额度|超标|合规")
    _explicit_travel_search = re.compile(
        r"(?:查|查询|看看|找|搜索|预订|订).{0,12}(?:机票|航班|酒店|住宿|天气|高铁|火车票|地铁|出租)"
        r"|(?:机票|航班|酒店|住宿|天气|高铁|火车票|地铁|出租).{0,8}(?:查|查询|看看|找|搜索|预订|订)"
    )

    def route(
        self,
        query: str,
        state: dict[str, object] | None = None,
        history: list[HistoryMessage] | None = None,
    ) -> RouteDecision:
        normalized = re.sub(r"\s+", " ", query.strip())
        slots = self._extract_slots(normalized)

        fast_intents = [intent for pattern, intent in self._fast_rules if pattern.search(normalized)]
        if len(fast_intents) == 1 and not self._has_additional_intent(normalized, fast_intents[0]):
            intents = fast_intents
            lane = "fast"
            confidence = 0.99
            summary = "固定业务表达命中高置信路由，跳过语义分类。"
        else:
            intents = [intent for intent, pattern in self._semantic_rules if pattern.search(normalized)]
            intents = list(dict.fromkeys(intents))
            # A policy question often names a travel object ("酒店报销标准")
            # without asking to search it. Keep both intents only when an
            # explicit search/booking action is present.
            if (
                Intent.TRAVEL_SEARCH in intents
                and Intent.POLICY_QUERY in intents
                and not self._explicit_travel_search.search(normalized)
            ):
                intents.remove(Intent.TRAVEL_SEARCH)
            if not intents:
                intents = [Intent.GENERAL]
                confidence = 0.62
                summary = "未发现需要业务工具处理的明确意图，交由旅行问答助手直接作答。"
            else:
                confidence = min(0.97, 0.83 + 0.03 * len(intents))
                summary = f"识别到 {len(intents)} 个可并行处理的业务目标。"
            lane = "semantic"

        return self.finalize(
            query, intents, history=history, state=state,
            lane=lane, confidence=confidence, summary=summary,
        )

    def finalize(
        self,
        query: str,
        intents: list[Intent],
        *,
        history: list[HistoryMessage] | None = None,
        state: dict[str, object] | None = None,
        lane: str = "semantic",
        confidence: float = 0.8,
        summary: str = "",
    ) -> RouteDecision:
        normalized = re.sub(r"\s+", " ", query.strip())
        slots = self._extract_slots(normalized)
        history_slots = self._history_slots(history)
        intents = list(dict.fromkeys(intents)) or [Intent.GENERAL]

        out_of_scope_primary = bool(self._oos_primary.search(normalized))
        if out_of_scope_primary and Intent.TRAVEL_SEARCH in intents:
            intents = [intent for intent in intents if intent != Intent.TRAVEL_SEARCH]
            summary = "当前句的主请求是餐饮或景点，酒店等词只作为场景，不升级为差旅搜索。"
            if not intents:
                intents = [Intent.GENERAL]
                confidence = 0.9

        if Intent.APPROVAL_CREATE in intents and not self._approval_create_allowed(normalized):
            intents = [intent for intent in intents if intent != Intent.APPROVAL_CREATE]
            summary = "检测到审批创建被否定或延后，已阻止有副作用的创建操作。"
            if not intents:
                intents = [Intent.GENERAL]
                confidence = 0.96

        if intents == [Intent.GENERAL] and not out_of_scope_primary:
            contextual_intent = self._resolve_contextual_intent(normalized, history)
            if contextual_intent is not None:
                intents = [contextual_intent]
                lane = "context"
                confidence = 0.78
                summary = "结合最近会话上下文解析了省略的业务对象；不会继承审批创建操作。"

        targets = [TARGETS[intent].model_copy(deep=True) for intent in intents]
        targets.sort(key=lambda item: item.priority)
        return RouteDecision(
            lane=lane,
            intents=intents,
            targets=targets,
            rewritten_query=normalized,
            confidence=confidence,
            reasoning_summary=summary,
            # Old context is only a fallback. Explicit entities in the current
            # query must always win (history=上海, current=武汉天气 -> 武汉).
            slots={**history_slots, **self._state_slots(state), **slots},
        )

    def _has_additional_intent(self, query: str, fast_intent: Intent) -> bool:
        return any(intent != fast_intent and pattern.search(query) for intent, pattern in self._semantic_rules)

    def _extract_slots(self, query: str) -> dict[str, str]:
        slots: dict[str, str] = {}
        route = self._route_pattern.search(query)
        if route and route.group(1) != route.group(2):
            slots["departure"], slots["destination"] = route.groups()
        elif match := self._destination_pattern.search(query):
            slots["destination"] = match.group(1)
        else:
            mentioned_cities = re.findall(self._city_alternation, query)
            if mentioned_cities:
                slots["destination"] = mentioned_cities[-1]

        today = date.today()
        if "大后天" in query:
            slots["date"] = str(today + timedelta(days=3))
        elif "后天" in query:
            slots["date"] = str(today + timedelta(days=2))
        elif "明天" in query:
            slots["date"] = str(today + timedelta(days=1))
        elif match := re.search(r"(20\d{2})[-年/](\d{1,2})[-月/](\d{1,2})日?", query):
            slots["date"] = f"{int(match.group(1)):04d}-{int(match.group(2)):02d}-{int(match.group(3)):02d}"

        if match := re.search(r"预算\s*(\d{2,6})|(?:预算|不超过).*?(\d{2,6})\s*元", query):
            slots["budget"] = next(group for group in match.groups() if group)
        return slots

    def _history_slots(self, history: list[HistoryMessage] | None) -> dict[str, str]:
        if not history:
            return {}
        inherited: dict[str, str] = {}
        # Prefer recent user facts. Assistant text is only a fallback for
        # elliptical follow-ups and never overrides a user-provided value.
        for role in ("user", "assistant"):
            for message in reversed(history[-12:]):
                if message.role != role:
                    continue
                for key, value in self._extract_slots(message.content).items():
                    inherited.setdefault(key, value)
        return inherited

    def _approval_create_allowed(self, query: str) -> bool:
        if not self._approval_action.search(query):
            return False
        compact = re.sub(r"[\s，,。！？!?]", "", query)
        return not (self._approval_denied.search(compact) or self._approval_deferred.search(compact))

    def _resolve_contextual_intent(
        self, query: str, history: list[HistoryMessage] | None
    ) -> Intent | None:
        if not history or len(query) > 96:
            return None
        if not (self._follow_up_cue.search(query) or self._referring.search(query)):
            return None

        # Prefer the user's latest explicit object. A model-generated answer
        # may mention both "酒店" and "差旅政策" while summarizing; letting that
        # text outrank the user's hotel request would pollute the next route.
        # Assistant text remains a fallback for imported/partial histories.
        for role in ("user", "assistant"):
            for message in reversed(history[-8:]):
                if message.role != role:
                    continue
                content = message.content
                if self._status_follow_up.search(query) and re.search(r"审批|申请|出差单", content):
                    return Intent.APPROVAL_STATUS
                if self._oos_primary.search(content) or (
                    self._out_of_scope_context.search(content) and not self._travel_context.search(content)
                ):
                    return Intent.GENERAL
                if self._policy_context.search(content):
                    return Intent.POLICY_QUERY
                if self._travel_context.search(content):
                    return Intent.TRAVEL_SEARCH
        return None

    def _state_slots(self, state: dict[str, object] | None) -> dict[str, str]:
        if not state:
            return {}
        return {str(key): str(value) for key, value in state.items() if value not in (None, "")}
