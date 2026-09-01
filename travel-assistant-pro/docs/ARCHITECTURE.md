# 架构决策记录

## ADR-001：Spring Boot 作为唯一外部业务入口

前端只访问 Spring Boot。JWT 校验、资源所有权和持久化由业务后端完成；FastAPI 仅接受内部服务身份和已经裁剪的上下文，防止 Agent Runtime 演变为第二套业务后端。

## ADR-002：使用 SSE 结构化事件而非拼接文本

事件类型固定为 `session`、`route`、`agent_start`、`thinking`、`tool_start`、`tool_end`、`card`、`text`、`agent_end`、`trace`、`error` 和 `done`。前端按协议渲染，Spring 只负责鉴权、转发和最终持久化。

## ADR-003：业务真相归 Spring，编排状态归 FastAPI

用户、会话、消息、审批、政策等事实数据属于 Spring；一次请求的路由决策、执行图、短期槽位和 Trace 属于 Agent Runtime。需要跨轮的槽位会以结构化状态写回业务服务。

## ADR-004：本地可运行优先，但保留生产适配点

开发环境使用 H2 文件数据库和离线 Agent Provider；生产 Profile 使用 MySQL、Redis 与真实 LLM Provider。所有替换通过端口/适配器完成。

## ADR-005：真模型是可替换端口，副作用门禁不交给模型

有 `DASHSCOPE_API_KEY` 或 OpenAI 兼容 Key 时，Agent Runtime 通过 OpenAI 兼容接口调用通义千问/GPT，用于低置信语义分类和最终回复润色。无 Key 时自动离线。工具调用与审批创建仍由 Spring 内部 API 和规则门禁执行，模型不能单独创建审批。

