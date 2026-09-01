# VoyageIQ 企业差旅智能中枢

VoyageIQ 把企业差旅里的登录、会话、政策、审批和多智能体编排放在同一条链路上：浏览器只访问 Spring Boot；Agent Runtime 无状态、不碰用户库；政策判定和审批副作用全部由业务后端落地。

产品代码在 [`travel-assistant-pro/`](travel-assistant-pro/)。

```text
React 工作台  :5173
      │  JWT / HTTPS / SSE
      ▼
Spring Boot 业务服务  :8081
      │  会话、政策、审批、审计、幂等
      │  内部服务密钥 + 已裁剪上下文
      ▼
FastAPI Agent Runtime  :8001
      │  路由 → 多 Agent 并行 → 工具
      └── 内部 API 回调 Spring（政策检索 / 创建审批 / 查进度）
```

| 目录 | 职责 |
|---|---|
| `travel-assistant-pro/web/` | React + TypeScript 工作台 |
| `travel-assistant-pro/business-service/` | Spring Boot 业务后端 |
| `travel-assistant-pro/agent-service/` | FastAPI Agent Runtime |

## 工作台

- JWT 登录，会话新建 / 选择 / 搜索 / 重命名 / 软删除
- SSE 流式回答，机票、酒店、天气、政策、审批等结构化卡片
- 可折叠运行详情：路由、执行计划、工具结束、结果汇总
- 浏览器不直连 Agent 服务

## 业务后端（Spring Boot）

入口 `business-service/`，默认端口 **8081**。对外是唯一业务 API，对内给 Agent 提供受控工具。

**认证与隔离**

- `/api/v1/auth/login` 签发 JWT；会话与消息按用户所有权校验
- Agent 调用走 `/internal/v1/*`，校验 `X-Internal-Service-Key`，不使用用户 JWT

**会话与流式代理**

- 会话、消息、Agent 运行详情持久化（Flyway：H2 本地 / MySQL 生产）
- `POST /api/v1/conversations/{id}/messages:stream`：鉴权后把历史和槽位转给 Runtime，再把 SSE 转回浏览器
- `done` 时写入助手消息、卡片和 runtime 事件，刷新后可恢复

**差旅政策**

- `policy_version` 按租户和生效日选版本；`travel_policy_rule` 按职级、城市等级、差旅类型匹配
- 创建审批时写入政策版本、规则 ID、决策快照和是否需要财务复核；后续改政策不影响已生成的结论
- 内部接口：`POST /internal/v1/policies/search`

**审批状态机与可靠写入**

- 路径：`PENDING_MANAGER → PENDING_FINANCE / APPROVED`，支持拒绝、撤回、重新提交
- `Idempotency-Key` 数据库唯一约束；同键同载荷返回原单，冲突载荷拒绝
- 审批记录与 `outbox_event` 同一事务提交；调度器认领、发布确认、指数退避
- 可选 RabbitMQ：持久化交换机/队列、publisher confirm、消费者去重（`processed_message`）后投影到 `approval_event_projection`
- 内部接口：创建审批、查询状态、工作流动作

**列表查询**

- 会话聚合根维护 `messageCount`、`lastMessagePreview`、`lastMessageAt`，列表用投影查询，避免逐条再查最后一条消息

## Agent Runtime（FastAPI）

入口 `agent-service/`，默认端口 **8001**。只接受内部身份，CORS 为空，不作为用户入口。

**混合路由**

- 高置信规则快车道（明确「规划行程」「提交申请」）
- 语义组合：一次请求可拆出多个意图并行
- 多轮上下文：省略句继承无副作用意图（检索/政策），**不继承** `approval_create`
- 审批副作用门禁：否定、暂缓、条件句、多轮取消不得进入创建
- 规则落到 `general` 且已配置模型时，用 LLM 做语义分类，结果仍要过 `finalize()` 门禁

**五个专家 Agent（可并行）**

| Agent | 意图 | 作用 |
|---|---|---|
| 行程规划顾问 | `trip_plan` | 并行整理机票、住宿、天气 |
| 差旅信息专员 | `travel_search` | 按当前句查询机酒或天气 |
| 企业政策顾问 | `policy_query` | 调 Spring 政策检索 |
| 审批事务专员 | `approval_create` / `approval_status` | 调 Spring 创建或查进度 |
| 旅行问答助手 | `general` | 不走业务写工具的问答 |

单 Agent 失败不会取消其他 Agent；成功结果按优先级汇总，避免后写覆盖。

**模型与工具**

- 有 `DASHSCOPE_API_KEY` 时走 DashScope 兼容接口（默认 `qwen-turbo`）：低置信分类 + 最终回复润色
- 无 Key 时离线，编排链不变
- 机酒天气为可替换的本地工具端口；政策与审批必须打 Spring 内部 API
- 模型不能单独创建审批

**SSE 事件**

`session` → `route` → `thinking` / `plan` → `agent_start` → `tool_end` → `card` → `agent_end` → `composition` → `text` → `trace` → `done`

## 本地启动

环境：JDK 17、Maven 3.6.3+、Python 3.11、Node.js 20+ 与 Corepack。

```powershell
cd travel-assistant-pro\agent-service
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"

cd ..\web
corepack pnpm install

cd ..
.\start-dev.ps1
```

浏览器：http://127.0.0.1:5173/  
本地账号 `voyage`（初始密码见 `LocalDataInitializer`）。通义千问：复制 `agent-service/.env.example` 为 `.env`，填写 `DASHSCOPE_API_KEY` 后重新启动。停止：`.\stop-dev.ps1`。

生产请改用企业身份源、MySQL 与消息中间件，不要保留本地初始化账号。

## 测试

```powershell
cd travel-assistant-pro\agent-service
.\.venv\Scripts\python.exe -m pytest -q

cd ..\business-service
mvn test

cd ..\web
corepack pnpm test -- --run
corepack pnpm test:e2e

cd ..
.\scripts\full-stack-smoke.ps1
```

评测脚本（在 `agent-service` 下，需设置 `PYTHONPATH=.`）：产品契约、审批安全、CrossWOZ 外部语料、结构化指标、真模型 holdout。口径和边界见 [`travel-assistant-pro/docs/TEST_REPORT.md`](travel-assistant-pro/docs/TEST_REPORT.md)、[`ARCHITECTURE.md`](travel-assistant-pro/docs/ARCHITECTURE.md)。
