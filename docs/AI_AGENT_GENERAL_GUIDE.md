# AI Agent 通用开发指南

> 这是一份不绑定具体项目的 AI Agent 学习与开发规范。它可以给人阅读，也可以直接交给其他 AI，作为后续设计、评审、重构 Agent 系统时的通用参考。

## 1. 一句话理解 Agent

完整 Agent 不是“接一个大模型接口”，而是一个围绕目标持续运行的工程系统：

```text
感知 -> 理解 -> 规划 -> 行动 -> 观察 -> 记忆 -> 反思 -> 输出
```

可以概括为：

```text
Agent = LLM + Planning + Memory + Tools + Executor + Guardrails + Observability + Evaluation
```

LLM 是大脑，但单独的 LLM 不是完整 Agent。Agent 的关键是：它能在多步任务中根据环境反馈决定下一步，并在安全边界内调用工具完成任务。

## 2. Agent 与 ChatBot / Chain / RAG 的区别

ChatBot：

- 用户问一句，模型答一句。
- 通常没有工具、记忆、规划和闭环。

LLM Chain：

- 工程侧写死步骤，例如“改写问题 -> 检索 -> 总结”。
- 流程固定，模型通常不决定下一步。

RAG Bot：

- 先检索知识库，再让模型基于资料回答。
- 如果只是固定检索一次再回答，更像 Chain。

Agent：

- 模型可以选择动作。
- 能调用工具。
- 能读取观察结果。
- 能继续规划或停止。
- 有最大步数、权限、日志、评估和安全控制。

判断标准：

- 是否有多步决策？
- 是否有工具调用？
- 是否根据观察结果改变下一步？
- 是否有记忆？
- 是否有执行边界？
- 是否可观测、可评估、可回滚？

## 3. 开发前先定义场景

不要一开始做万能 Agent。先选一个小而明确的场景。

必须回答：

- 用户是谁？
- 用户输入是什么？
- Agent 输出是什么？
- 成功标准是什么？
- 数据来自哪里？
- 可用工具有哪些？
- 哪些操作允许自动执行？
- 哪些操作必须人工确认？
- 哪些操作禁止执行？
- 出错时如何降级？

示例场景：

- 企业知识库问答
- 智能客服
- 商城购物助手
- 工单处理助手
- 数据分析助手
- 自动写报告助手
- 编程助手
- 内部运营助手

## 4. 通用架构

推荐架构：

```text
用户请求
-> API 网关
-> 鉴权
-> 输入安全检查
-> 意图识别
-> 加载记忆
-> Agent 编排器
   -> 规划
   -> 工具选择
   -> RAG 检索
   -> 工具执行
   -> 观察结果
   -> 反思校验
-> 生成最终答案
-> 输出安全检查
-> 写入记忆
-> 记录 Trace
-> 返回用户
```

核心模块：

- API 层
- 鉴权与权限层
- Agent Orchestrator
- LLM Client
- Prompt Manager
- Tool Registry
- Tool Executor
- RAG Retriever
- Memory Manager
- Reflection Checker
- Guardrails
- Trace / Metrics / Audit
- Evaluation Runner

## 5. 输入输出协议

通用请求：

```json
{
  "message": "用户问题",
  "sessionId": "session-xxx",
  "userId": "user-xxx",
  "pageContext": {
    "pageType": "ORDER_DETAIL",
    "resourceId": "123"
  },
  "metadata": {
    "locale": "zh-CN",
    "client": "web"
  }
}
```

内部上下文：

```json
{
  "traceId": "uuid",
  "userId": "user-xxx",
  "sessionId": "session-xxx",
  "input": "用户问题",
  "intent": "UNKNOWN",
  "permissions": [],
  "memory": {
    "working": [],
    "sessionSummary": "",
    "longTerm": []
  },
  "budget": {
    "maxSteps": 6,
    "maxToolCalls": 8,
    "timeoutMs": 180000,
    "maxTokens": 8000
  }
}
```

通用响应：

```json
{
  "answer": "最终回答",
  "intent": "PRODUCT_RECOMMENDATION",
  "citations": [],
  "suggestedActions": [],
  "toolCalls": [],
  "confidence": 0.82,
  "needHuman": false,
  "traceId": "uuid"
}
```

流式事件：

```json
{ "type": "delta", "content": "正在查询..." }
{ "type": "tool_call", "name": "search_kb", "status": "running" }
{ "type": "tool_result", "name": "search_kb", "status": "success" }
{ "type": "done", "answer": "最终回答", "traceId": "uuid" }
{ "type": "error", "code": "TOOL_TIMEOUT", "message": "工具超时", "traceId": "uuid" }
```

## 6. LLM 模型层

模型负责：

- 理解意图
- 生成计划
- 选择工具
- 填写参数
- 理解工具返回
- 生成答案
- 判断是否继续
- 总结失败原因

模型不应该直接负责：

- 访问数据库
- 修改业务数据
- 判断权限
- 执行高危动作
- 保存敏感长期记忆

生产系统建议支持多模型：

- 强模型：复杂规划、最终生成、反思校验。
- 快模型：意图分类、工具路由、摘要。
- Embedding 模型：知识和记忆向量化。
- Reranker 模型：检索结果重排。
- 备用模型：主模型失败时降级。

模型调用必须记录：

- 模型名
- Prompt 版本
- 输入 token
- 输出 token
- 耗时
- 错误码
- 是否重试
- 是否降级

## 7. Prompt 系统

Prompt 必须工程化管理，不要散落在代码里。

一个完整 System Prompt 应包含：

- 角色定义
- 业务目标
- 任务边界
- 可用工具
- 工具调用格式
- 输出格式
- 安全规则
- 拒答规则
- 错误处理规则
- 引用规则
- 人工确认规则
- few-shot 示例

Prompt 分层：

- System Prompt：固定规则。
- Developer Prompt：业务策略和工程约束。
- User Prompt：用户输入。
- Context Prompt：页面、会话、检索结果、工具结果。
- Output Prompt：格式要求。

Prompt 管理要求：

- 版本化
- 可回滚
- 可 A/B 测试
- 可评估
- 改动记录原因
- 与 Trace 关联

Few-shot 示例要覆盖：

- 正常回答
- 工具调用
- 多步工具调用
- 查不到资料
- 权限不足
- 需要用户确认
- 工具失败
- 拒答

## 8. ReAct 模式

ReAct = Reasoning + Acting。

典型结构：

```text
Thought: 我需要判断下一步
Action: 调用工具
Observation: 工具返回结果
Thought: 基于结果继续判断
Final Answer: 最终回答
```

适合：

- 客服问答
- 工具调用
- 检索问答
- 路径不确定的问题
- 需要边查边判断的问题

优点：

- 能利用外部证据。
- 能根据工具结果调整。
- 可解释、可调试。
- 适合流式输出。

风险：

- 死循环。
- 重复调用工具。
- 工具噪声误导模型。
- 复杂任务缺全局计划。

必须设置：

- 最大步数
- 最大工具调用数
- 超时时间
- 重复动作检测
- 无进展检测
- 工具失败重试上限

## 9. Plan-and-Execute 模式

流程：

```text
Planner 先生成完整计划
-> Executor 按步骤执行
-> 每一步可调用工具
-> 失败时 Replan
-> 汇总结果
```

适合：

- 报告生成
- 数据分析
- 任务结构明确
- 需要审计计划
- 多步骤交付任务

优点：

- 全局性更强。
- 计划可展示。
- 适合复杂任务拆解。

风险：

- 初始计划错会影响后续。
- 环境变化时需要重新规划。
- 简单任务会增加延迟。

实用方案：

- 简单任务：直接 ReAct。
- 复杂任务：先 Plan，再每步用 ReAct 执行。
- 高风险结果：最后 Reflection 检查。

## 10. Reflection / Reflexion

反思模块用于质量校验。

检查内容：

- 是否回答用户问题？
- 是否遗漏限制条件？
- 是否有证据？
- 是否编造事实？
- 工具是否失败？
- 是否需要继续检索？
- 是否需要用户确认？
- 是否违反安全规则？
- 输出格式是否正确？

不通过时：

- 重新检索
- 重新生成
- 重新规划
- 请求澄清
- 降级回答
- 转人工
- 拒答

建议：

- 规则校验 + 模型校验结合。
- 高风险场景必须有校验器。
- 不要让同一个模型无限自检，应设置重试上限。

## 11. LangGraph / 状态机编排

当流程复杂时，不建议把所有逻辑写在一个循环里。

可以显式建模为图：

- 节点：意图识别、检索、工具执行、反思、输出。
- 边：条件判断。
- 状态：任务目标、计划、工具结果、错误、预算、引用。

适合：

- 复杂业务流程
- 审核节点
- 条件分支
- 重试拓扑
- 人机协同
- 长任务检查点

收益：

- 可恢复
- 可审计
- 可测试
- 可观测
- 更容易控制终止条件

## 12. 工具系统

工具是 Agent 的手脚。

工具必须有标准 schema：

```json
{
  "name": "search_kb",
  "description": "在知识库中搜索相关内容",
  "parameters": {
    "type": "object",
    "properties": {
      "query": { "type": "string" },
      "topK": { "type": "integer" }
    },
    "required": ["query"]
  },
  "permission": "KB_READ",
  "dangerous": false,
  "requiresConfirmation": false,
  "timeoutMs": 5000
}
```

工具返回：

```json
{
  "ok": true,
  "code": "OK",
  "data": {},
  "summary": "检索到 5 条结果",
  "retryable": false
}
```

每个工具要有：

- 名称
- 描述
- 参数 schema
- 返回 schema
- 错误码
- 权限要求
- 是否高危
- 是否需要确认
- 超时时间
- 重试策略
- 审计要求

工具设计原则：

- 单职责。
- 参数尽量结构化。
- 返回结果可被模型理解。
- 错误信息可指导下一步。
- 不让模型直接操作数据库。
- 写操作必须幂等。
- 高危操作必须人工确认。

## 13. 工具路由

工具多时不能全部塞进 Prompt。

工具路由流程：

```text
用户问题
-> 工具类别识别
-> 候选工具召回
-> 给模型少量候选工具
-> 模型选择具体工具
-> 参数填充
```

路由方式：

- 规则路由
- 分类模型路由
- 向量检索工具描述
- 分组路由
- MCP 工具发现

工具描述必须写清：

- 什么时候用
- 什么时候不要用
- 输入是什么
- 输出是什么
- 权限是什么
- 可能失败的原因

## 14. Function Calling 与 MCP

Function Calling：

- 模型侧表达“我要调用哪个函数以及参数”。
- 适合简单、内部、数量较少的工具。
- 通常由应用本地执行工具。

MCP：

- 标准化连接外部工具和资源。
- 适合工具生态、跨系统、跨产品复用。
- MCP Server 暴露工具，MCP Client 拉取并调用。

二者关系：

- 不是替代关系。
- Function Calling 解决模型如何表达调用。
- MCP 解决工具如何暴露、治理、复用。
- 实际系统可把 MCP 工具映射成 Function Calling 工具。

选择建议：

- 内部少量工具：Function Calling。
- 多系统、多团队、工具复用：MCP。
- 需要统一治理和审计：MCP。
- 追求最小实现：Function Calling。

## 15. Executor 执行器

Executor 是安全边界，不是模型。

职责：

- 工具白名单
- 参数校验
- 权限判断
- 高危操作确认
- 幂等控制
- 超时控制
- 重试退避
- 熔断降级
- 错误归一化
- 结果格式化
- 审计日志

错误归一化：

```json
{
  "ok": false,
  "code": "PERMISSION_DENIED",
  "message": "无权执行该操作",
  "retryable": false,
  "safeForUser": true
}
```

工具失败处理：

- 参数错误：让模型修正一次。
- 超时：重试或降级。
- 权限不足：停止。
- 业务状态不允许：解释原因。
- 连续失败：停止 Agent。

## 16. RAG 知识检索

RAG = Retrieval-Augmented Generation。

完整离线流程：

```text
文档采集
-> 解析
-> 清洗
-> 去重
-> 质量评估
-> 分块
-> 元数据抽取
-> Embedding
-> 入向量库 / 关键词索引
```

完整在线流程：

```text
用户问题
-> 查询改写
-> 向量检索
-> BM25 检索
-> 元数据过滤
-> 多路融合
-> 重排序
-> Top-K 证据
-> 拼 Prompt
-> 基于证据回答
-> 引用来源
-> 忠实度校验
```

常见检索方式：

- 向量检索
- BM25
- 混合检索
- RRF 融合
- Reranker 重排序
- MMR 去冗余
- 结构化过滤

分块策略：

- 固定长度分块
- 递归字符分块
- 按标题分块
- 语义分块
- Parent-Child Chunk
- 表格单独处理

高级 RAG：

- GraphRAG：适合关系推理和全局聚合。
- Agentic RAG：Agent 决定是否检索、怎么检索、是否继续检索。
- Self-RAG：模型自评证据是否充分。
- Corrective RAG：检索差时纠正查询或换策略。
- Adaptive RAG：根据问题难度路由不同检索管道。

RAG 回答原则：

- 动态事实必须有来源。
- 没有证据就不要编。
- 引用必须能追溯。
- 权限过滤必须在检索前或检索中完成。
- 文档内容不能覆盖系统指令。

## 17. 记忆系统

记忆让 Agent 不会说完就忘，但记忆不能乱写。

三层记忆：

工作记忆：

- 当前任务状态。
- 当前计划。
- 工具观察结果。
- 临时结论。

会话记忆：

- 当前会话历史。
- 滑动窗口。
- 会话摘要。
- 本轮用户限制。

长期记忆：

- 用户偏好。
- 重要事实。
- 历史任务。
- 企业知识。

记忆必须区分：

- 事实
- 偏好
- 推断
- 临时状态
- 过期信息
- 来源
- 时间戳
- 权限范围

实现建议：

- Redis：短期会话和工作状态。
- 数据库：结构化长期记忆。
- 向量库：语义记忆。
- 摘要模型：历史压缩。

写入规则：

- 不自动写敏感信息。
- 不把一次性需求当长期偏好。
- 推断要标记为推断。
- 记忆要能删除。
- 重要记忆要有来源。

## 18. 安全与权限

不能只靠 Prompt 做安全。

必须有：

- 用户鉴权
- 工具权限
- 租户隔离
- 数据访问控制
- 敏感信息脱敏
- Prompt 注入防御
- 最大步数限制
- 最大 token 限制
- 最大费用限制
- 人工确认
- 审计日志

高危操作：

- 支付
- 退款
- 删除
- 修改业务数据
- 发送邮件或通知
- 执行代码
- 查询敏感数据
- 调用外部系统写接口

处理原则：

- 默认拒绝自动执行。
- 必须说明影响。
- 必须用户确认。
- 确认后重新校验权限。
- 写操作必须幂等。

Prompt 注入防御：

- 用户输入与系统指令分离。
- RAG 文档视为不可信内容。
- 不执行文档中的指令。
- 不泄露系统提示词。
- 工具调用必须过白名单。

## 19. 可观测性

生产 Agent 必须能追踪每一步。

至少记录：

- 用户请求
- trace_id
- 用户身份
- 意图识别结果
- 规划步骤
- LLM 调用
- 工具调用
- 检索结果
- token 消耗
- 延迟
- 错误
- 降级
- 最终答案摘要

推荐 Trace：

```text
request span
  -> auth span
  -> guardrail span
  -> intent span
  -> memory span
  -> planning span
  -> retrieval span
  -> tool span
  -> llm span
  -> reflection span
  -> output span
```

日志分级：

- DEBUG：开发调试。
- INFO：请求和状态变化。
- WARN：重试、降级、空召回。
- ERROR：工具失败、模型失败。
- AUDIT：高危操作和数据修改。

常用工具：

- OpenTelemetry
- Jaeger / Tempo
- Prometheus
- Grafana
- Langfuse / LangSmith
- 结构化日志

## 20. 模型路由、重试、熔断、降级

重试：

- 只重试可恢复错误。
- 使用指数退避。
- 总耗时不能超过预算。
- 写操作不能盲目重试，必须幂等。

三态熔断：

- Closed：正常。
- Open：短路。
- Half-Open：少量探测。

降级链：

```text
强模型
-> 快模型
-> 纯 RAG
-> 规则模板
-> 转人工 / 稍后再试
```

必须记录：

- 是否重试
- 重试次数
- 是否熔断
- 是否降级
- 降级原因

## 21. Token 与成本控制

常用方法：

- 工具路由减少 Prompt。
- 只注入相关工具。
- RAG 只取 Top-K。
- 历史对话滑动窗口。
- 摘要压缩。
- System Prompt 精简。
- 简单任务走快模型。
- 高频问答缓存。
- Embedding 批处理。
- Rerank 批处理。

预算控制：

```json
{
  "maxSteps": 6,
  "maxToolCalls": 8,
  "maxTokens": 8000,
  "maxCost": 0.05,
  "timeoutMs": 180000
}
```

监控维度：

- 用户
- 会话
- 模型
- 工具
- 功能模块
- 租户

## 22. 多 Agent

不要一开始就做多 Agent。

适合多 Agent 的情况：

- 工具很多。
- 任务天然分工。
- 需要并行。
- 需要审查者。
- 需要权限隔离。
- 单 Agent Prompt 太长。

常见角色：

- Supervisor Agent：路由和汇总。
- RAG Agent：知识检索。
- Tool Agent：工具调用。
- Critic Agent：审查。
- Summary Agent：摘要。
- Domain Agent：领域专家。

通信要求：

- 结构化 JSON。
- 明确输入输出。
- 独立权限。
- 独立超时。
- Supervisor 汇总。

风险：

- 延迟上升。
- 成本上升。
- 协调复杂。
- 错误级联。
- 互相附和。

## 23. 评估体系

Agent 不能靠感觉优化。

Agent 指标：

- 任务完成率
- 答案正确率
- 工具选择准确率
- 工具调用成功率
- 平均步数
- 死循环率
- 幻觉率
- 拒答正确率
- 人工接管率
- 用户满意度
- 平均延迟
- P95 / P99
- Token 成本

RAG 指标：

- Faithfulness
- Answer correctness
- Context relevance
- Context precision
- Context recall
- Recall@K
- MRR
- 引用准确率
- 空召回率

测试集要覆盖：

- 简单问答
- 多轮对话
- 多跳问题
- 查不到的问题
- 容易幻觉的问题
- 权限不足
- 需要工具
- 工具失败
- 需要转人工
- Prompt 注入
- 高危操作

每次改动都应跑评估：

- Prompt 改动
- 模型切换
- 工具 schema 变更
- RAG 参数调整
- 记忆策略调整

## 24. 部署与运维

生产系统需要：

- API 服务
- Worker
- 数据库
- Redis
- 向量数据库
- 日志系统
- 监控告警
- 配置中心
- CI/CD
- 灰度发布
- 回滚方案

CI/CD 流程：

```text
代码提交
-> 单元测试
-> 工具 schema 测试
-> Prompt 格式测试
-> AI 评估集
-> 安全扫描
-> 构建镜像
-> Staging
-> 冒烟测试
-> 灰度
-> 全量
```

告警项：

- 模型错误率升高
- 工具错误率升高
- 检索空召回升高
- P95 延迟升高
- Token 成本异常
- 死循环率升高
- 高危操作异常
- Prompt 注入命中增多

## 25. 推荐落地顺序

新手和新项目推荐顺序：

1. 普通 ChatBot。
2. 加知识库检索，变成 RAG Bot。
3. 加 ReAct 循环，让它能决定是否检索和继续查。
4. 加工具调用，比如查数据库、查订单、查工单。
5. 加短期记忆，支持多轮对话。
6. 加长期记忆，记住用户偏好和历史任务。
7. 加反思校验，降低幻觉。
8. 加权限、安全、日志、Trace。
9. 加评估集，持续优化。
10. 再考虑多 Agent 和复杂工作流。

最小可行版本：

```text
用户问题
-> 判断意图
-> 检索知识库
-> 必要时调用工具
-> 根据观察结果生成答案
-> 校验答案
-> 返回带引用的结果
```

MVP 必须有：

- LLM
- Prompt
- 1 个知识库检索工具
- 1 个 ReAct 循环
- 短期会话历史
- 最大步数限制
- 最终答案输出
- trace_id

MVP 暂不做：

- 多 Agent
- 自动执行高危动作
- 长期记忆
- GraphRAG
- 自动优化 Prompt

## 26. 通用工程目录建议

```text
agent-service/
  app/
    api/
      chat_controller.py
    agent/
      orchestrator.py
      react_executor.py
      planner.py
      reflection.py
      state.py
    llm/
      client.py
      router.py
      prompts/
    tools/
      base.py
      registry.py
      builtin_tools.py
      business_tools.py
    rag/
      loader.py
      splitter.py
      retriever.py
      reranker.py
      citations.py
    memory/
      working_memory.py
      session_memory.py
      long_term_memory.py
    guardrails/
      input_guard.py
      output_guard.py
      permission.py
    observability/
      trace.py
      metrics.py
      audit.py
    evaluation/
      datasets/
      runner.py
```

## 27. 开发检查清单

上线前逐项检查：

- [ ] 场景是否明确？
- [ ] 用户是谁？
- [ ] 输入输出是否明确？
- [ ] 成功标准是否明确？
- [ ] 哪些动作可自动执行？
- [ ] 哪些动作必须确认？
- [ ] 哪些动作禁止？
- [ ] 是否有 System Prompt？
- [ ] Prompt 是否版本化？
- [ ] 工具是否有 schema？
- [ ] 工具是否有权限？
- [ ] 工具是否有超时？
- [ ] 工具是否有错误码？
- [ ] 是否设置最大步数？
- [ ] 是否设置 token 预算？
- [ ] 是否有 trace_id？
- [ ] 是否记录 LLM 调用？
- [ ] 是否记录工具调用？
- [ ] 是否记录检索结果？
- [ ] 是否有安全规则？
- [ ] 是否防 Prompt 注入？
- [ ] 是否脱敏日志？
- [ ] 是否有评估集？
- [ ] 是否有降级方案？
- [ ] 是否有人工确认机制？
- [ ] 是否能回滚？

## 28. 最重要原则

- 先小场景，再大系统。
- 先只读工具，再写操作。
- 先单 Agent，再多 Agent。
- 先 RAG + 工具，再长期记忆。
- 先可观测，再复杂编排。
- 动态事实必须来自工具或知识库。
- 高危动作必须用户确认。
- 工具调用必须可审计。
- Prompt、模型、工具、索引必须版本化。
- 每次改动都要能评估、能回滚。
