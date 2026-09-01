# VoyageIQ 分层测试报告

> 测试日期：2026-08-31。所有结果来自实际执行并保留原始报告；本报告不把合成契约、局部外部语料或本机性能测试扩大解释。

> 【2026-09-01 后端专项更新】新增版本化政策/审批状态机/Transactional Outbox/RabbitMQ 工程实现。最新默认回归为 FastAPI 53 passed、Spring 53 tests（0 failures/0 errors、6 个门控项 skipped）、React 12 passed且生产构建通过；另有真实 MySQL 1/1、真实 RabbitMQ 2/2 通过。

> 【2026-09-01 最终启动回归】三层健康检查 `UP/up/200`，Playwright 首轮因在线流式回答超过默认 5 秒为 5/6，改用 20 秒完成条件后重跑 6/6；HTTP/SSE 全栈脚本 22/22 断言通过，qwen-turbo 在线并产生 14 个文本增量，否定审批请求使审批数保持 2→2。

## 汇总

| 层级 | 命令/入口 | 结果 |
|---|---|---:|
| FastAPI 单元与编排 | `python -m pytest -q` | 53 passed |
| 产品路由契约 | `evaluation/evaluate_product_contract.py` | 154/240 → 240/240 |
| 审批安全契约 | `evaluation/benchmark_approval_safety.py` | 组合 0/108；口语/多轮否定 0/31 |
| CrossWOZ 外部语料 | `evaluation/evaluate_external_router.py` | 孤立句 483/600 → 501/600；带上下文 527/600（87.83%） |
| 结构化三件套 | `evaluation/evaluate_structured_metrics.py` | 路由/完整率/工具 240/240，副作用 0 |
| 真模型 holdout | `evaluation/evaluate_model_holdout.py` | 已跑：DashScope qwen-turbo；CrossWOZ 混合 518/600（86.33%），规则对照 527/600（87.83%）；否定安全 31×3 误创建 0 |
| Spring 默认测试 | `mvn test` | 53 tests，0 失败/0 错误，6 个门控项跳过 |
| Spring 真实 MySQL | `MySqlPolicyApprovalIntegrationTest` | 1/1 passed；空库 Flyway V1→V3 + 政策/幂等/Outbox |
| Spring 真实 RabbitMQ | `RabbitOutboxIntegrationTest` | 2/2 passed；confirm/投影/去重 + retry/DLQ |
| React 单元/组件 | `pnpm test -- --run` | 12 passed |
| 前端生产构建 | `pnpm build` | passed，主 JS gzip 71.79 kB |
| Playwright E2E | `pnpm test:e2e` | 6 passed |
| 全栈 HTTP/SSE | `scripts/full-stack-smoke.ps1` | PASS，22/22 断言；DashScope qwen-turbo 在线、真实文本增量 > 1 |
| MySQL 规模基准 | `MySqlConversationScaleBenchmarkTest` | PASS，10k 会话/100k 消息 |
| HTTP 会话列表 | `ConversationListHttpBenchmarkTest` | H2 200 会话，20 并发 P95 30ms，错误 0 |

## Agent 数据分层

1. **240 条产品契约**：确定性生成，8 个分层，每层 30 条；用于防止产品规则回归。首轮基线 154/240，规则优化后 240/240，禁用意图违规 0。因为表达模板由项目定义，`eligibleForGeneralizationMetric=false`。
2. **108 条审批安全契约**：6 个否定/延后词 × 3 个创建动词 × 3 个审批对象 × 2 个状态变体；用于验证副作用不变量，不作为自然语言准确率。
3. **600 条第三方人类语料**：来自 CrossWOZ 官方测试集的未改写文本；基线孤立句 483/600（80.50%），优化后孤立句 501/600（83.50%），带对话上下文 527/600（87.83%，Wilson 84.97%–90.21%）。失败全部保留。它不覆盖行程规划、企业政策、审批和答案质量。
4. **36 条口语/红队/多轮安全契约**：31 条否定请求危险创建 0，5 条肯定创建仍可提交；与 108 条组合契约分开报告。
5. **53 项 pytest**：覆盖快慢/上下文车道、复合意图、状态与创建互斥、槽位/日期/预算、当前城市覆盖历史城市、模型回答不污染后续路由、确定性聚合、失败隔离、通用问答、LLM 身份/历史载荷、真实 SSE delta 解析、审批门禁与 Agent/Spring 决策边界。
6. **真模型 holdout（qwen-turbo）**：冻结 SHA 不变；生产混合路由 518/600（86.33%），比规则低 1.5pt，主因越界句被模型打成 `travel_search`（46→56）。审批否定 31 条重复 3 次误创建 0。原始报告：`docs/reports/agent/model-holdout-qwen-turbo.json`。

## MySQL 基准

生产方言补充验收：在隔离 MySQL 5.7.15 上第一次从空库迁移真实失败，定位为 `TIMESTAMP(6)` 隐式零默认值不兼容；修复 MySQL 专用 V1/V3 后清空数据库重跑，Flyway V1→V3、Hibernate validate、政策快照、相同载荷幂等回放、变更载荷冲突拒绝和审批/Outbox 1:1 全部通过。该结果是兼容性/正确性证据，不作为性能指标。

环境为本机隔离 MySQL 5.7.15；测试库包含 10,000 个会话、100,000 条消息；每页 50，5 次预热、30 次采样。

| 指标 | N+1 基线 | 聚合投影 | 变化 |
|---|---:|---:|---:|
| SQL/轮 | 52 | 2 | -96.15% |
| P50 | 106 ms | 24 ms | -77.36% |
| P95 | 265 ms | 31 ms | -88.30% |

此测试证明在同机同数据下的查询结构和进程内延迟变化，不包含网关、HTTP 并发、网络抖动或生产硬件差异。

HTTP 会话列表补充证据（H2 内存库、200 会话、页大小 50）：顺序 P50/P95 为 12/16ms；20 并发 P50/P95 为 26/30ms，错误 0。这证明 JWT 鉴权后的读路径可并发，不是 MySQL 生产压测。

## Playwright 与全栈覆盖

- E2E 1：登录成功后进入受保护工作台。
- E2E 2：从 UI 新建、重命名、搜索并删除会话。
- E2E 3：接收 Agent SSE 消息，刷新后从后端恢复对话。
- E2E 4：验证短消息气泡按内容收缩，实测页面运行时与回答均显示 qwen-turbo，并验证上海景点问题不再返回固定文案。
- E2E 5：在两个新会话分别查询上海、武汉，来回切换并验证消息零串线、当前城市覆盖旧城市。
- E2E 6：注入长内容验证聊天区和会话列表可独立上下滚动，验证编辑按钮无覆盖，并检查左右侧栏展开/收起。
- 全栈脚本检查三个健康端点、五次 SSE、DashScope/qwen-turbo 在线身份、模型分析/回答生成阶段、真实多 delta 文本流、审批创建拦截、上下文追问、武汉覆盖上海且不重放酒店工具、公开执行计划/汇总事件、5 轮运行详情与 10 条消息持久化，最后软删除测试会话。

## 失败记录与真实性

- 第一轮 18 条人工开发集因参与调参，只保留为缺陷复现，不进入最终泛化指标。
- 产品契约第一次真实运行只有 154/240，原始基线未覆盖；没有从报告中删除失败再重跑。
- CrossWOZ 的 117 个失败完整保存在原始 JSON 中，没有只报告成功样例。
- 在线模型首次全栈回归发现模型回复中的“差旅政策”污染“那它价格呢”的后续路由；改为最近用户事实优先、模型文本兜底后，22/22 断言通过。真实 token 流启用后，旧 E2E 曾在首个 token 到达即刷新，改为等待 `done` 后再验证持久化，6/6 重新通过。旧子进程占用、系统代理等历史失败均保留在原始报告中。
- 真实 MySQL 首轮不是通过：V1 因 `created_at` 默认值报错。保留失败原因并从空库重跑后才记为 1/1 passed；RabbitMQ 也只在 `rabbitmq-diagnostics ping` 成功后记为 2/2 passed。

原始机器可读证据位于 `docs/reports/`；该目录按要求不提交 Git。
