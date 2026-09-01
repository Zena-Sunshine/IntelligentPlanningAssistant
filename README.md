# VoyageIQ 企业差旅智能中枢

面向企业差旅的智能工作台：登录与会话、差旅政策与审批、多智能体编排在同一条业务链路上协作。

产品代码位于 `travel-assistant-pro/`。

- `web/`：React + TypeScript 工作台（会话、结构化卡片、Agent 运行详情）
- `business-service/`：Spring Boot 业务后端（认证授权、持久化、内部 API、SSE 代理）
- `agent-service/`：FastAPI Agent Runtime（混合意图路由、多 Agent 并行、工具调用、流式事件）

浏览器只访问 Spring Boot；Agent 服务不暴露给终端用户，也不直接操作用户数据库。

## 启动

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

浏览器访问 `http://127.0.0.1:5173/`。接入通义千问时，复制 `agent-service/.env.example` 为 `.env` 并填写 `DASHSCOPE_API_KEY`。

更完整的说明见 [`travel-assistant-pro/README.md`](travel-assistant-pro/README.md)。
