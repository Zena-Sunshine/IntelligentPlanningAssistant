# VoyageIQ 企业差旅智能中枢

VoyageIQ 是面向企业差旅的智能工作台。用户、会话、审批、差旅政策等业务能力在 Spring Boot 中落地；意图路由、多 Agent 并行和流式事件在 FastAPI Runtime 中执行。浏览器只访问业务后端。

- `web/`：React + TypeScript 工作台（登录、会话、结构化卡片、可折叠运行详情）
- `business-service/`：Spring Boot（JWT、持久化、政策决策、审批状态机、幂等 Outbox、SSE 代理）
- `agent-service/`：FastAPI Agent Runtime（混合路由、五专家并行、工具调用、模型端口、结构化 SSE）

完整架构说明以仓库根目录 [`README.md`](../README.md) 为准。

## 设计原则

1. 浏览器只信任 Spring Boot；Agent 不暴露给终端用户，也不直接操作用户数据库。
2. 会话事实、权限和审批结果归业务服务；一次请求的路由、执行图和 Trace 归 Runtime。
3. 多意图并行执行，失败隔离，结果确定性汇总，避免互相覆盖。
4. 审批创建等副作用由规则门禁 + Spring 事务落地；模型不能单独提交审批。
5. 无模型密钥时离线可跑通编排；有 Key 时替换为通义千问，路径不变。

## 本地启动

环境：JDK 17、Maven 3.6.3+、Python 3.11、Node.js 20+ 与 Corepack。

```powershell
cd agent-service
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"

cd ..\web
corepack pnpm install

cd ..
.\start-dev.ps1
```

浏览器访问 `http://127.0.0.1:5173/`。本地账号 `voyage`，初始密码见 `LocalDataInitializer`。

接入通义：复制 `agent-service/.env.example` 为 `.env`，设置 `DASHSCOPE_API_KEY`（可选 `DASHSCOPE_MODEL=qwen-turbo`）后重新执行 `.\start-dev.ps1`。停止：`.\stop-dev.ps1`。

## 测试

```powershell
cd agent-service
.\.venv\Scripts\python.exe -m pytest -q
$env:PYTHONPATH = "."
python evaluation/evaluate_product_contract.py --dataset evaluation/datasets/product_contract_240.jsonl --manifest evaluation/PRODUCT_CONTRACT_MANIFEST.json --report ..\docs\reports\agent\product-contract-latest.json
python evaluation/benchmark_approval_safety.py --report ..\docs\reports\agent\approval-safety-latest.json
python evaluation/evaluate_external_router.py --dataset evaluation/datasets/crosswoz_external_holdout.jsonl --report ..\docs\reports\agent\external-router-latest.json
python evaluation/evaluate_structured_metrics.py --dataset evaluation/datasets/product_contract_240.jsonl --manifest evaluation/PRODUCT_CONTRACT_MANIFEST.json --report ..\docs\reports\agent\structured-metrics-latest.json
python evaluation/evaluate_model_holdout.py --dataset evaluation/datasets/crosswoz_external_holdout.jsonl --report ..\docs\reports\agent\model-holdout-qwen-turbo.json

cd ..\business-service
mvn test

cd ..\web
corepack pnpm test -- --run
corepack pnpm build
corepack pnpm test:e2e

cd ..
.\scripts\full-stack-smoke.ps1
```

数字与口径见 `docs/TEST_REPORT.md`。产品契约 100% 表示规则覆盖，不是开放域准确率；CrossWOZ 只覆盖差旅搜索与越界识别。
