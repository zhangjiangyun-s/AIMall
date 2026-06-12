# 第一轮任务总览：1A 最小可运行骨架

更新时间：2026-06-11 17:45:21 +08:00

## 总原则

第一轮任务 1A 只做“最小可运行骨架”，不做完整业务。

每个 agent 必须严格读取自己目录下的 `AGENT_TASK.md`，按文件中的细则执行。根目录本文档只作为总览，不替代各自目录里的任务。

## 四个 agent 固定任务范围

```text
aimall-server：
创建 Spring Boot mock 后端，端口 8080，实现 6 个固定接口。

aimall-web：
创建 Vue 用户端骨架，端口 5173，实现商品和订单 4 个页面，加 AI 浮窗。

aimall-admin：
创建 Vue 管理端骨架，端口 5174，实现工作台、商品管理、知识库文档 3 个页面。

aimall-ai-service：
创建 FastAPI mock AI 服务，端口 8000，实现 /health、/ai/chat、/ai/knowledge/rebuild。
```

## 本轮统一禁止事项

```text
不得接真实支付。
不得接真实物流。
不得做微服务。
不得做完整商城业务。
不得做复杂权限系统。
不得做复杂 AI Agent。
不得接真实大模型。
不得接真实向量库。
不得自行新增大范围模块。
不得自行更换技术栈。
```

## 固定端口

```text
aimall-server：8080
aimall-web：5173
aimall-admin：5174
aimall-ai-service：8000
```

## 固定调用边界

```text
aimall-web 只能调用 aimall-server。
aimall-admin 只能调用 aimall-server。
aimall-server 后续负责调用 aimall-ai-service，但 1A 不要求真实调用。
aimall-ai-service 后续通过 aimall-server 获取业务数据，但 1A 不要求真实调用。
```

## 交付方式

每个 agent 完成后，必须在自己目录下的 `AGENT_TASK.md` 末尾追加完成报告。

不得把报告写到其他文件。

不得覆盖项目组长任务内容。

## 验收方式

项目组长会读取每个目录的 `AGENT_TASK.md` 和实际代码，按以下结果验收：

```text
PASS：通过。
PASS_WITH_MINOR_FIXES：基本通过，有少量问题需要修。
NEEDS_REWORK：需要返工。
BLOCKED_BY_DECISION：被决策问题卡住。
```

