import pytest
from datetime import date, timedelta

from app.domain.models import HistoryMessage, Intent
from app.orchestration.router import HybridIntentRouter


def test_fast_lane_for_single_fixed_request():
    decision = HybridIntentRouter().route("帮我规划明天杭州到上海的出差行程")
    assert decision.lane == "fast"
    assert decision.intents == [Intent.TRIP_PLAN]
    assert decision.slots["departure"] == "杭州"
    assert decision.slots["destination"] == "上海"


def test_multi_intent_is_not_short_circuited():
    decision = HybridIntentRouter().route("后天去北京，查机票酒店并告诉我住宿报销标准")
    assert decision.lane == "semantic"
    assert Intent.TRAVEL_SEARCH in decision.intents
    assert Intent.POLICY_QUERY in decision.intents


def test_approval_status_does_not_create_new_approval():
    decision = HybridIntentRouter().route("帮我查一下出差申请审批进度")
    assert Intent.APPROVAL_STATUS in decision.intents
    assert Intent.APPROVAL_CREATE not in decision.intents


def test_over_policy_question_routes_to_policy_not_approval_creation():
    decision = HybridIntentRouter().route("查询成都酒店，并说明住宿超标怎么审批")
    assert Intent.TRAVEL_SEARCH in decision.intents
    assert Intent.POLICY_QUERY in decision.intents
    assert Intent.APPROVAL_CREATE not in decision.intents


@pytest.mark.parametrize("query", [
    "不要提交出差申请，我再确认一下",
    "先别发起审批",
    "无需创建申请",
    "取消提交出差申请",
    "确认后再提交申请",
])
def test_negated_or_deferred_request_never_creates_approval(query: str):
    decision = HybridIntentRouter().route(query)
    assert Intent.APPROVAL_CREATE not in decision.intents


def test_negated_create_can_still_query_approval_status():
    decision = HybridIntentRouter().route("不要提交出差申请，只查审批进度")
    assert decision.intents == [Intent.APPROVAL_STATUS]


def test_explicit_affirmative_request_still_creates_approval():
    decision = HybridIntentRouter().route("帮我立即提交出差申请")
    assert decision.intents == [Intent.APPROVAL_CREATE]


def test_short_follow_up_inherits_non_side_effect_travel_context():
    history = [HistoryMessage(role="user", content="帮我找一家上海的酒店")]
    decision = HybridIntentRouter().route("那它的价格怎么样？", history=history)
    assert decision.intents == [Intent.TRAVEL_SEARCH]
    assert decision.lane == "context"
    assert decision.slots["destination"] == "上海"


def test_model_summary_cannot_override_latest_user_travel_object():
    history = [
        HistoryMessage(role="user", content="帮我找一家上海的酒店"),
        HistoryMessage(role="assistant", content="这些住宿价格符合企业差旅政策。"),
    ]
    decision = HybridIntentRouter().route("那它的价格怎么样？", history=history)
    assert decision.intents == [Intent.TRAVEL_SEARCH]
    assert decision.slots["destination"] == "上海"


def test_standalone_city_is_extracted_for_weather_search():
    decision = HybridIntentRouter().route("武汉天气")
    assert decision.intents == [Intent.TRAVEL_SEARCH]
    assert decision.slots["destination"] == "武汉"


def test_current_city_overrides_different_city_from_history():
    history = [HistoryMessage(role="user", content="上海有哪些景点比较好玩")]
    decision = HybridIntentRouter().route("武汉天气", history=history)
    assert decision.slots["destination"] == "武汉"


def test_out_of_scope_context_does_not_become_travel_search():
    history = [HistoryMessage(role="user", content="推荐一家附近的餐馆")]
    decision = HybridIntentRouter().route("那它的电话呢？", history=history)
    assert decision.intents == [Intent.GENERAL]


def test_context_never_replays_approval_creation():
    history = [HistoryMessage(role="user", content="帮我提交出差申请")]
    decision = HybridIntentRouter().route("那结果呢？", history=history)
    assert Intent.APPROVAL_CREATE not in decision.intents


def test_iso_date_and_budget_are_extracted_as_normalized_slots():
    decision = HybridIntentRouter().route("规划2026/9/18杭州到西安的行程，预算不超过3500元")
    assert decision.slots == {
        "departure": "杭州",
        "destination": "西安",
        "date": "2026-09-18",
        "budget": "3500",
    }


def test_relative_dates_are_resolved_in_priority_order():
    router = HybridIntentRouter()
    assert router.route("规划大后天去成都的行程").slots["date"] == str(date.today() + timedelta(days=3))
    assert router.route("规划后天去成都的行程").slots["date"] == str(date.today() + timedelta(days=2))


def test_current_query_slots_override_stale_state_but_preserve_other_state():
    decision = HybridIntentRouter().route(
        "规划上海到深圳的行程",
        state={"departure": "杭州", "employeeLevel": "P7"},
    )
    assert decision.slots["departure"] == "上海"
    assert decision.slots["destination"] == "深圳"
    assert decision.slots["employeeLevel"] == "P7"


def test_explicit_current_intent_takes_precedence_over_history_context():
    history = [HistoryMessage(role="user", content="帮我看看上海的酒店")]
    decision = HybridIntentRouter().route("查询公司的差旅政策", history=history)
    assert decision.intents == [Intent.POLICY_QUERY]
    assert decision.lane != "context"


def test_policy_follow_up_can_use_assistant_fallback_when_user_turn_is_absent():
    history = [HistoryMessage(role="assistant", content="住宿报销标准上限是每晚500元")]
    decision = HybridIntentRouter().route("那这个怎么样？", history=history)
    assert decision.intents == [Intent.POLICY_QUERY]
    assert decision.lane == "context"


def test_iso_date_between_action_and_object_still_creates_approval():
    decision = HybridIntentRouter().route("帮我创建2026-09-18的出差审批")
    assert decision.intents == [Intent.APPROVAL_CREATE]


def test_deferred_iso_date_request_never_creates_approval():
    decision = HybridIntentRouter().route("等我确认2026-09-18安排后再提交出差申请")
    assert Intent.APPROVAL_CREATE not in decision.intents


def test_policy_object_is_not_mistaken_for_search_without_search_action():
    decision = HybridIntentRouter().route("北京酒店超过额度需要准备什么材料")
    assert decision.intents == [Intent.POLICY_QUERY]


def test_policy_and_search_are_both_kept_when_search_action_is_explicit():
    decision = HybridIntentRouter().route("查北京酒店，并告诉我住宿报销标准")
    assert decision.intents == [Intent.TRAVEL_SEARCH, Intent.POLICY_QUERY]


def test_natural_oral_and_red_team_requests_never_create_approval():
    router = HybridIntentRouter()
    from evaluation.natural_safety_cases import all_cases
    for case in all_cases():
        decision = router.route(case["query"], history=case.get("history") or None)
        created = Intent.APPROVAL_CREATE in decision.intents
        assert created == case["expect_create"], case["id"]
        assert (Intent.APPROVAL_STATUS in decision.intents) == case["expect_status"], case["id"]


def test_crosswoz_style_ellipsis_uses_assistant_hotel_mention():
    history = [
        HistoryMessage(role="user", content="帮我找一家评分高的酒店"),
        HistoryMessage(role="assistant", content="为您推荐北京全季酒店，评分4.6分。"),
    ]
    decision = HybridIntentRouter().route("好，地址在哪里？", history=history)
    assert decision.intents == [Intent.TRAVEL_SEARCH]
    assert decision.lane == "context"


def test_restaurant_request_wins_even_if_hotel_is_mentioned():
    decision = HybridIntentRouter().route("住宿还是挺方便的，游玩之后我想找个人均消费是100-150元的餐馆用餐")
    assert decision.intents == [Intent.GENERAL]
    assert Intent.TRAVEL_SEARCH not in decision.intents


def test_follow_up_does_not_inherit_approval_after_user_cancels():
    history = [HistoryMessage(role="user", content="帮我提交出差申请")]
    decision = HybridIntentRouter().route("算了，先别交", history=history)
    assert Intent.APPROVAL_CREATE not in decision.intents


def test_whitespace_is_normalized_before_routing_and_rewrite():
    decision = HybridIntentRouter().route("  帮我   规划明天杭州到上海的出差行程  ")
    assert decision.rewritten_query == "帮我 规划明天杭州到上海的出差行程"
    assert decision.intents == [Intent.TRIP_PLAN]
