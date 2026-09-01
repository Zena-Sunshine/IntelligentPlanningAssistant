# VoyageIQ 企业差旅智能中枢

VoyageIQ 是面向企业差旅场景的智能工作台。系统不是单纯聊天页面，而是将用户、会话、审批、差旅政策等传统业务能力与多智能体编排解耦：

- `web/`：React + TypeScript 工作台，提供登录、会话管理、结构化业务卡片和可折叠 Agent 运行详情。
- `business-service/`：Spring Boot 业务后端，负责认证授权、会话与消息持久化、业务 API、审计和 Agent 流式代理。
- `agent-service/`：FastAPI Agent Runtime，负责混合意图路由、多 Agent 并发执行、工具调用、上下文工程、结构化事件和追踪。

## 设计原则

1. 浏览器只信任并访问 Spring Boot，Agent 服务不直接暴露用户身份与业务数据库。
2. Agent Runtime 尽量无状态；会话事实、权限和业务结果由 Spring Boot 管理。
3. Agent 决策使用结构化契约，执行结果采用确定性聚合，避免多意图结果互相覆盖。
4. 本地开发默认不依赖云模型；配置模型密钥后可替换为真实 LLM，离线模式仍走完整编排链。
5. 所有关键结果必须有自动化评测或性能测试作为证据。

## 本地启动

环境要求：JDK 17、Maven 3.6.3+、Python 3.11、Node.js 20+ 与 Corepack。

```powershell
cd agent-service
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"

cd ..\web
corepack pnpm install

cd ..
.\start-dev.ps1
```

浏览器访问 `http://127.0.0.1:5173/`。本地初始化账号为 `voyage`，初始密码通过
`LocalDataInitializer` 配置；生产环境必须接入企业身份源并移除本地初始化器。

接入真模型：复制 `agent-service/.env.example` 为 `agent-service/.env`，设置 `MODEL_PROVIDER=dashscope`、`DASHSCOPE_MODEL=qwen-turbo` 和 `DASHSCOPE_API_KEY` 后重新执行 `.\start-dev.ps1`。工作台右上角会显示 `通义 qwen-turbo`；没有 Key 时自动离线，审批创建仍走规则门禁。

停止由脚本启动的服务：

```powershell
.\stop-dev.ps1
```

## 质量证据

本项目按“单元/集成测试 → 产品契约 → 第三方外部语料 → 浏览器 E2E → 全栈回归 → 数据库规模基准”分层验收：

- FastAPI：53/53 pytest；240 条产品契约；108 条审批安全契约 + 31 条口语/多轮否定；600 条未改写 CrossWOZ；**qwen-turbo 真模型 holdout 518/600（86.33%）**，规则对照 527/600。
- Spring Boot：默认套件 53 项（0 失败/0 错误，6 项外部环境或专项基准按条件跳过）；另有真实 MySQL 1/1、真实 RabbitMQ 2/2、版本化政策/审批状态机/幂等 Outbox 专项测试和两个 10,000 规模基准。
- React：12/12 Vitest、生产构建和 6/6 Playwright E2E。
- 全栈：22/22 断言，覆盖 JWT、SSE、在线模型、真实 token 流、内部工具、否定审批、上下文追问、显式城市覆盖、通用问答与运行详情持久化。

常用复现命令：

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

完整数字、环境和限制见 `docs/TEST_REPORT.md`。240 条产品契约的 100% 只表示规则契约覆盖；CrossWOZ 规则 87.83% 与 qwen-turbo 86.33% 只覆盖差旅搜索和越界识别；MySQL 延迟是本机基准，均不得包装成生产指标。
