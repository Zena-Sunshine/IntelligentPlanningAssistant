"""Build the frozen 240-case product-domain contract dataset.

This dataset is deterministic and synthetic. It checks the product's declared
business contract; it must never be reported as independent human-language
generalization evidence. The external CrossWOZ holdout remains a separate test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path


SINGLE_INTENT_SEEDS: dict[str, tuple[str, ...]] = {
    "trip_plan": (
        "规划{date}{origin}到{destination}的出差行程",
        "给我制定{date}去{destination}的行程方案",
        "安排一份{origin}前往{destination}的差旅方案",
        "我{date}去{destination}开会，做个出差安排",
        "为{origin}到{destination}的商务出行规划行程",
    ),
    "travel_search": (
        "查一下{date}{origin}飞{destination}的航班",
        "看看{destination}市中心有哪些酒店",
        "查询{origin}到{destination}的高铁票",
        "{destination}{date}天气怎么样",
        "帮我找{destination}机场附近的住宿",
    ),
    "policy_query": (
        "去{destination}出差的住宿报销标准是多少",
        "公司差旅政策对去{destination}的机票舱位怎么规定",
        "{destination}酒店超过额度需要准备什么材料",
        "查询{destination}的差标和餐补规定",
        "{date}这次差旅怎样才算合规",
    ),
    "approval_create": (
        "立即提交去{destination}的出差申请",
        "帮我创建{date}的出差审批",
        "现在发起{origin}到{destination}的出差单",
        "替我提交一份去{destination}的商务出行申请",
        "确认无误，申请{date}去{destination}出差",
    ),
    "approval_status": (
        "查一下我去{destination}的出差申请审批进度",
        "刚才去{destination}的审批单现在是什么状态",
        "查询{date}差旅申请的审批结果",
        "我去{destination}的出差审批到哪一步了",
        "帮忙看看{date}那份申请目前的状态",
    ),
    "general": (
        "你好，我来自{origin}，可以介绍一下你能做什么吗",
        "{date}心情不错，讲个冷笑话吧",
        "在{origin}怎样提高英语口语能力",
        "帮我给{destination}的朋友写一段生日祝福",
        "给{origin}的小学生解释一下什么是光合作用",
    ),
}

VARIANTS = (
    {"date": "明天", "origin": "杭州", "destination": "北京"},
    {"date": "后天", "origin": "上海", "destination": "深圳"},
    {"date": "大后天", "origin": "广州", "destination": "成都"},
    {"date": "2026-09-18", "origin": "南京", "destination": "西安"},
    {"date": "下周", "origin": "武汉", "destination": "厦门"},
    {"date": "月底", "origin": "苏州", "destination": "青岛"},
)

MULTI_INTENT_SEEDS = (
    ("{date}去{destination}，查机票并告诉我住宿报销标准", ("travel_search", "policy_query")),
    ("规划{origin}到{destination}的行程，同时看看酒店", ("trip_plan", "travel_search")),
    ("查{destination}酒店和天气，再查询我的审批进度", ("travel_search", "approval_status")),
    ("制定{date}差旅方案，并说明机票舱位政策", ("trip_plan", "policy_query")),
    ("查{origin}到{destination}的航班，然后立即提交出差申请", ("travel_search", "approval_create")),
)

SIDE_EFFECT_SEEDS = (
    ("不要提交去{destination}的出差申请，我再确认", ("general",), ("approval_create",)),
    ("先别创建去{destination}的出差审批，只查审批进度", ("approval_status",), ("approval_create",)),
    ("无需发起申请，看看{destination}的酒店", ("travel_search",), ("approval_create",)),
    ("等我确认{date}安排后再提交出差申请", ("general",), ("approval_create",)),
    ("取消提交去{destination}的出差单，只告诉我住宿标准", ("policy_query",), ("approval_create",)),
)


def build_rows() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for intent, templates in SINGLE_INTENT_SEEDS.items():
        for template in templates:
            for variant in VARIANTS:
                rows.append({
                    "id": f"contract-{len(rows) + 1:03d}",
                    "query": template.format(**variant),
                    "expected_intents": [intent],
                    "forbidden_intents": [],
                    "stratum": f"single_{intent}",
                    "source_type": "deterministic_product_contract",
                    "eligible_for_generalization_metric": False,
                })

    for template, expected in MULTI_INTENT_SEEDS:
        for variant in VARIANTS:
            rows.append({
                "id": f"contract-{len(rows) + 1:03d}",
                "query": template.format(**variant),
                "expected_intents": list(expected),
                "forbidden_intents": [],
                "stratum": "multi_intent",
                "source_type": "deterministic_product_contract",
                "eligible_for_generalization_metric": False,
            })

    for template, expected, forbidden in SIDE_EFFECT_SEEDS:
        for variant in VARIANTS:
            rows.append({
                "id": f"contract-{len(rows) + 1:03d}",
                "query": template.format(**variant),
                "expected_intents": list(expected),
                "forbidden_intents": list(forbidden),
                "stratum": "side_effect_guard",
                "source_type": "deterministic_product_contract",
                "eligible_for_generalization_metric": False,
            })

    assert len(rows) == 240
    assert len({row["query"] for row in rows}) == 240
    assert Counter(row["stratum"] for row in rows) == {
        "single_trip_plan": 30,
        "single_travel_search": 30,
        "single_policy_query": 30,
        "single_approval_create": 30,
        "single_approval_status": 30,
        "single_general": 30,
        "multi_intent": 30,
        "side_effect_guard": 30,
    }
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    rows = build_rows()
    payload = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Write bytes so the frozen hash is identical on Windows and Linux (no CRLF translation).
    args.output.write_bytes(payload.encode("utf-8"))
    manifest = {
        "name": "VoyageIQ 240-case product contract",
        "cases": len(rows),
        "sha256": digest,
        "sourceType": "deterministic_product_contract",
        "eligibleForGeneralizationMetric": False,
        "warning": "Synthetic contract coverage only; not an independent human-language evaluation.",
        "strata": dict(sorted(Counter(row["stratum"] for row in rows).items())),
    }
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
