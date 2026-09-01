from app.agents.registry import AgentRegistry, ApprovalAgent
from app.config import Settings
from app.domain.models import AgentRequest, AgentResult, HistoryMessage, Intent, RouteDecision
from app.orchestration.orchestrator import AgentOrchestrator
from app.orchestration.router import HybridIntentRouter
from app.tools.business import BusinessToolClient
from app.tools.travel import TravelSearchTools


def test_aggregator_keeps_every_mixed_intent_result():
    results = [
        AgentResult(agent_key="travel_researcher", display_name="差旅信息专员", intent=Intent.TRAVEL_SEARCH, text="机酒已查询"),
        AgentResult(agent_key="policy_advisor", display_name="企业政策顾问", intent=Intent.POLICY_QUERY, text="标准已核对"),
    ]
    answer = AgentOrchestrator.aggregate("query", results)
    assert "机酒已查询" in answer
    assert "标准已核对" in answer


def offline_orchestrator() -> AgentOrchestrator:
    settings = Settings(business_base_url="http://127.0.0.1:1")
    registry = AgentRegistry(TravelSearchTools(), BusinessToolClient(settings))
    return AgentOrchestrator(HybridIntentRouter(), registry)


async def test_stream_blocks_negated_approval_before_agent_execution():
    request = AgentRequest(
        request_id="safety-1",
        conversation_id="conversation-1",
        user_id="user-1",
        query="不要提交出差申请，只查审批进度",
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    route = next(event for event in events if event.type == "route")
    started_agents = [event.data["key"] for event in events if event.type == "agent_start"]

    assert route.data["intents"] == [Intent.APPROVAL_STATUS.value]
    assert started_agents == ["approval_specialist"]
    done = next(event for event in events if event.type == "done")
    assert Intent.APPROVAL_CREATE.value not in done.data["intents"]


async def test_stream_passes_history_to_context_router():
    request = AgentRequest(
        request_id="context-1",
        conversation_id="conversation-1",
        user_id="user-1",
        query="那它的价格怎么样？",
        history=[HistoryMessage(role="user", content="帮我找一家上海的酒店")],
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    route = next(event for event in events if event.type == "route")

    assert route.data["lane"] == "context"
    assert route.data["intents"] == [Intent.TRAVEL_SEARCH.value]


async def test_travel_follow_up_uses_hotel_tool_instead_of_default_flight():
    request = AgentRequest(
        request_id="tool-1",
        conversation_id="conversation-1",
        user_id="user-1",
        query="那它的价格怎么样？",
        history=[HistoryMessage(role="user", content="帮我找一家上海的酒店")],
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    tools = [event.data["toolName"] for event in events if event.type == "tool_end"]
    assert "hotel_search" in tools
    assert "flight_search" not in tools


async def test_explicit_wuhan_weather_never_falls_back_to_shanghai_history():
    request = AgentRequest(
        request_id="city-override",
        conversation_id="conversation-1",
        user_id="user-1",
        query="武汉天气",
        history=[HistoryMessage(role="user", content="帮我找上海的酒店")],
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    route = next(event for event in events if event.type == "route")
    card = next(event for event in events if event.type == "card")
    done = next(event for event in events if event.type == "done")
    tools = [event.data["toolName"] for event in events if event.type == "tool_end"]

    assert route.data["slots"]["destination"] == "武汉"
    assert card.data["card"]["data"]["city"] == "武汉"
    assert tools == ["weather_query"]
    assert "已完成武汉相关差旅信息查询" in done.data["answer"]


async def test_one_agent_failure_does_not_cancel_successful_sibling():
    class FailingAgent:
        async def run(self, request, decision, intent):
            raise TimeoutError("simulated tool timeout")

    class SuccessfulAgent:
        async def run(self, request, decision, intent):
            return AgentResult(
                agent_key="policy_advisor",
                display_name="企业政策顾问",
                intent=intent,
                text="政策结果仍然可用",
            )

    class MixedRegistry:
        def get(self, key):
            return FailingAgent() if key == "travel_researcher" else SuccessfulAgent()

    orchestrator = AgentOrchestrator(HybridIntentRouter(), MixedRegistry())
    request = AgentRequest(
        request_id="isolation-1",
        conversation_id="conversation-1",
        user_id="user-1",
        query="查一下上海酒店和住宿报销标准",
    )
    events = [event async for event in orchestrator.stream(request)]
    endings = [event for event in events if event.type == "agent_end"]

    assert len(endings) == 2
    assert {event.data["success"] for event in endings} == {True, False}
    done = next(event for event in events if event.type == "done")
    assert done.data["successfulIntents"] == [Intent.POLICY_QUERY.value]
    assert "政策结果仍然可用" in done.data["answer"]
    assert "执行失败" in done.data["answer"]


async def test_general_identity_question_is_answered_instead_of_returning_canned_capabilities():
    request = AgentRequest(
        request_id="general-identity",
        conversation_id="conversation-1",
        user_id="user-1",
        query="你是谁",
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    done = next(event for event in events if event.type == "done")

    assert "我是 VoyageIQ 旅行问答助手" in done.data["answer"]
    assert "我可以协助规划差旅行程、查询机酒天气" not in done.data["answer"]


async def test_general_attraction_question_returns_city_specific_answer():
    request = AgentRequest(
        request_id="general-attractions",
        conversation_id="conversation-1",
        user_id="user-1",
        query="上海有哪些景点比较好玩",
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    done = next(event for event in events if event.type == "done")

    assert "外滩" in done.data["answer"]
    assert "上海博物馆" in done.data["answer"]
    assert done.data["answer"] != "我可以协助规划差旅行程、查询机酒天气、核对企业政策或发起出差申请。"


async def test_punctuation_only_question_requests_clarification():
    request = AgentRequest(
        request_id="general-clarify",
        conversation_id="conversation-1",
        user_id="user-1",
        query="？",
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    done = next(event for event in events if event.type == "done")

    assert "完整的问题" in done.data["answer"]


async def test_offline_meta_questions_are_answered_instead_of_using_generic_fallback():
    model_events = [event async for event in offline_orchestrator().stream(AgentRequest(
        request_id="meta-model", conversation_id="conversation-1", user_id="user-1",
        query="你是什么大模型",
    ))]
    thinking_events = [event async for event in offline_orchestrator().stream(AgentRequest(
        request_id="meta-thinking", conversation_id="conversation-1", user_id="user-1",
        query="你思考过吗",
    ))]

    model_answer = next(event for event in model_events if event.type == "done").data["answer"]
    thinking_answer = next(event for event in thinking_events if event.type == "done").data["answer"]
    assert "离线规则编排器" in model_answer
    assert "识别意图和上下文" in thinking_answer
    assert "当前信息还不足" not in model_answer + thinking_answer


async def test_stream_exposes_public_plan_and_composition_without_private_chain_of_thought():
    request = AgentRequest(
        request_id="runtime-detail",
        conversation_id="conversation-1",
        user_id="user-1",
        query="帮我规划杭州到上海的差旅行程并核对住宿标准",
    )
    events = [event async for event in offline_orchestrator().stream(request)]
    plan = next(event for event in events if event.type == "plan")
    composition = next(event for event in events if event.type == "composition")
    thinking = next(event for event in events if event.type == "thinking")

    assert len(plan.data["steps"]) == 2
    assert plan.data["parallel"] is True
    assert "路由方式" in thinking.data["evidence"]
    assert composition.data["totalAgents"] == 2


async def test_approval_agent_delegates_policy_decision_to_spring_without_overriding_it():
    class RecordingBusinessClient:
        def __init__(self):
            self.call = None

        async def create_approval(self, **kwargs):
            self.call = kwargs
            return {
                "approvalNo": "VI-900001",
                "status": "PENDING_FINANCE",
                "policyVersion": 7,
                "policyRuleId": 42,
                "decisionTrace": "matched rule: L2-TIER1; budget exceeds 1200",
                "requiresFinance": True,
                "idempotentReplay": False,
            }

    business = RecordingBusinessClient()
    request = AgentRequest(
        request_id="approval-boundary-1", conversation_id="conversation-1",
        user_id="user-1", tenant_id="tenant-a", query="提交去上海的出差申请",
    )
    decision = RouteDecision(
        lane="rule", intents=[Intent.APPROVAL_CREATE], targets=[], rewritten_query=request.query,
        confidence=1, reasoning_summary="explicit create",
        slots={"destination": "上海", "date": "2026-09-18", "budget": "1600"},
    )

    result = await ApprovalAgent(business).run(request, decision, Intent.APPROVAL_CREATE)

    assert business.call["request_id"] == request.request_id
    assert business.call["tenant_id"] == request.tenant_id
    assert set(business.call["payload"]) == {"destination", "travelDate", "budget", "reason"}
    assert result.cards[0].data["policyVersion"] == 7
    assert result.cards[0].data["policyRuleId"] == 42
    assert result.cards[0].data["requiresFinance"] is True
    assert result.cards[0].data["decisionTrace"].startswith("matched rule")
