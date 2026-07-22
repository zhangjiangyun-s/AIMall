# AIMall AI Agent 开发规范

> 本文依据 `ai-agent-interview-guide-zh.pdf` 的核心章节整理，面向 AIMall 商城 AI 功能落地使用。目标不是做一个“套模型接口的聊天框”，而是逐步建设一个可规划、可调用工具、可检索知识、可记忆、可观测、可评估、可控的工程化 Agent 系统。

## 1. 核心定义

一个完整 Agent 可以概括为：

```text
Agent = LLM + Planning + Memory + Tools + Executor + Guardrails + Observability + Evaluation
```

更完整的运行闭环是：

```text
感知输入 -> 理解意图 -> 加载记忆 -> 规划步骤 -> 选择工具 -> 执行动作
-> 观察结果 -> 反思校验 -> 生成输出 -> 写入记忆 -> 记录 Trace
```

关键判断：

- 只有一次模型调用，不是完整 Agent。
- 固定三步 RAG 流水线，如果没有基于观察结果的再决策，更像 Chain。
- ChatBot 加插件不一定是 Agent，关键看是否能在多步任务中自主选择工具、处理观察结果并继续行动。
- RAG + Chat 如果能多轮检索、改写问题、交叉验证、失败重试，就具备 Agent 特征。
- Agent 的“自主”不是不受控，必须由工程侧设置权限、步数、预算、审计和人工确认。

## 2. AIMall 首期业务边界

首期不要做万能 Agent，先做“商城购物助手 Agent”。

目标用户：

- 普通商城用户
- 登录用户
- 后续可扩展到客服、运营、管理员

输入：

- 用户自然语言问题
- 当前页面上下文
- 登录态和用户 ID
- 会话历史
- 可访问的商品、订单、优惠券、售后、商城规则知识

输出：

- 中文自然语言回答
- 推荐商品列表
- 可执行建议动作
- 必要的引用来源
- 是否需要人工处理
- 工具调用和推理链路日志

允许自动执行：

- 查询商品
- 查询商品分类
- 查询库存
- 查询用户订单
- 查询优惠券
- 查询售后状态
- 查询商城政策知识库
- 生成购物建议

必须用户确认后执行：

- 加入购物车
- 创建订单
- 取消订单
- 申请售后
- 修改地址
- 使用优惠券

首期禁止 Agent 自动执行：

- 支付
- 退款
- 删除用户数据
- 修改订单金额
- 修改库存
- 后台商品上下架
- 管理员级别操作

成功标准：

- 回答能解决用户问题
- 推荐商品与预算、品类、库存匹配
- 订单和售后回答只能基于当前用户数据
- 不编造商品、价格、库存、物流、退款政策
- 查不到时明确说明并引导人工或下一步操作
- 每次回答可追踪：用了哪些工具、检索了哪些内容、耗时多少

## 3. 总体架构

推荐架构：

```text
前端 AI 助手
  -> AI API Controller
  -> 鉴权与租户/用户上下文
  -> 输入 Guardrails
  -> 意图识别与工具路由
  -> 记忆加载
  -> Agent Orchestrator
      -> Planner
      -> ReAct Executor
      -> Tool Router
      -> Tool Executor
      -> RAG Retriever
      -> Reflection Checker
  -> 输出 Guardrails
  -> 写入记忆
  -> Trace / Metrics / Audit Log
  -> 流式返回
```

推荐服务边界：

- `aimall-web`：展示流式回答、商品卡片、建议动作、确认弹窗。
- `aimall-server`：商城业务 API、鉴权、用户数据、订单、商品、优惠券、售后。
- `aimall-ai-service`：Agent 编排、模型调用、工具路由、RAG、记忆、Trace。
- 数据库：结构化业务数据。
- Redis：短期会话、任务状态、限流、幂等锁。
- 向量库或检索引擎：长期知识、商城政策、FAQ、商品语义检索。
- 日志/监控：Trace、Span、Token、延迟、错误、工具调用。

## 4. Agent 请求与响应协议

前端请求建议结构：

```json
{
  "message": "帮我找一台 3000 元内的轻薄本",
  "sessionId": "user-1-session-xxx",
  "pageContext": {
    "pageType": "PRODUCT_LIST",
    "productId": 1,
    "orderId": null,
    "keyword": "轻薄本",
    "categoryId": 10,
    "cartItemCount": 2
  }
}
```

Agent 内部请求上下文：

```json
{
  "traceId": "uuid",
  "userId": 1,
  "sessionId": "xxx",
  "message": "用户原始问题",
  "pageContext": {},
  "authScopes": ["PRODUCT_READ", "ORDER_READ_SELF"],
  "memory": {
    "working": [],
    "sessionSummary": "",
    "longTermFacts": []
  },
  "budget": {
    "maxSteps": 6,
    "timeoutMs": 180000,
    "maxTokens": 8000,
    "maxToolCalls": 8
  }
}
```

流式响应事件：

```json
{ "type": "delta", "content": "正在为你筛选..." }
{ "type": "tool_call", "name": "search_products", "status": "running" }
{ "type": "tool_result", "name": "search_products", "status": "success" }
{
  "type": "done",
  "answer": "推荐这几款...",
  "intent": "PRODUCT_RECOMMENDATION",
  "relatedProducts": [],
  "suggestedActions": [],
  "citations": [],
  "traceId": "uuid"
}
```

错误响应：

```json
{
  "type": "error",
  "code": "TOOL_TIMEOUT",
  "message": "查询商品耗时较长，请稍后再试",
  "traceId": "uuid",
  "recoverable": true
}
```

## 5. LLM 模型层

LLM 是 Agent 的“大脑”，但不是完整 Agent。

模型职责：

- 理解用户意图
- 生成或修正计划
- 选择工具
- 填写工具参数
- 解释工具观察结果
- 生成最终回答
- 判断是否继续执行
- 异常恢复建议
- 输出格式遵循

模型不负责：

- 直接访问数据库
- 直接修改业务状态
- 绕过权限执行工具
- 判断用户是否有权限
- 决定高危操作是否可直接执行

模型路由建议：

- 快模型：意图识别、工具分类、简单闲聊。
- 强模型：复杂规划、多工具推理、反思校验。
- Embedding 模型：商品、知识库、历史记忆向量化。
- Reranker 模型：检索结果重排序。
- 备用模型：主模型超时、限流、失败时降级。

模型调用必须支持：

- 超时
- 重试
- 指数退避
- 熔断
- 降级
- Token 统计
- 成本统计
- Prompt 版本记录
- 模型版本记录

## 6. Prompt 系统

Prompt 不是一句“你是助手”，而是一套协议。

System Prompt 必须包含：

- Agent 角色
- 业务目标
- 任务边界
- 可用工具说明
- 工具调用格式
- 输出格式
- 安全规则
- 拒答规则
- 错误处理规则
- 引用规则
- 人工确认规则
- few-shot 示例

Prompt 分层：

- 系统层：角色、边界、安全规则，固定版本化。
- 任务层：当前用户问题、页面上下文、业务目标。
- 记忆层：会话摘要、用户偏好、当前任务状态。
- 工具层：当前可用工具及 schema。
- 检索层：RAG 召回的证据。
- 输出层：最终回答格式约束。

ReAct Prompt 结构：

```text
Thought: 分析当前目标和已知信息
Action: 选择工具和参数
Observation: 工具返回结果
Thought: 基于观察继续判断
Final Answer: 给用户的最终答案
```

注意：

- 生产环境不一定把完整 Thought 展示给用户。
- 可以记录内部推理摘要，但不要泄露系统提示词和隐私。
- 对外展示的是解释性过程，不是原始隐藏推理。

Few-shot 规则：

- 示例数量通常 2 到 8 个。
- 覆盖单步工具调用、多步工具调用、查不到、权限不足、需要确认、拒答。
- 示例必须高质量，错误示例会误导模型。
- 可做动态 few-shot：根据用户问题检索最相似示例。
- 示例中不得包含真实隐私数据。

结构化输出：

- 关键路径必须使用 JSON Schema 或 Function Calling。
- 后端必须校验模型输出，不能相信模型天然合规。
- 结构化失败时可做 JSON repair，但高风险动作不能只靠修复继续执行。

Prompt 注入防御：

- 用户输入和系统指令使用不同 message role。
- RAG 文档内容必须标记为“不可信外部内容”。
- 明确要求模型不得执行文档中的系统指令。
- 工具调用只接受白名单工具名和 schema 参数。
- 对“忽略之前指令”“泄露系统提示词”“直接查数据库”等请求拒绝。

## 7. 规划模块 Planning

规划负责把复杂任务拆成步骤。

### 7.1 ReAct

适用：

- 商品问答
- 客服咨询
- 订单查询
- 售后状态查询
- 工具交互密集
- 路径不确定，需要根据结果继续判断

特点：

- 一步思考、一步行动、一步观察。
- 能根据工具返回动态调整。
- 与 Function Calling 天然兼容。
- 适合流式体验。

风险：

- 容易死循环。
- 逐步贪心，复杂任务可能缺全局计划。
- 工具噪声会误导下一步。

保护：

- `max_steps`
- `max_tool_calls`
- `timeout`
- 重复动作检测
- 相同工具相同参数去重
- 观察结果摘要压缩
- 无进展时终止

### 7.2 Plan-and-Execute

适用：

- 明确多步骤任务
- 报告生成
- 数据分析
- 结构化流程
- 需要先给用户计划或可审计计划

流程：

```text
Planner 生成计划
-> Executor 执行每一步
-> 每步可内部使用 ReAct
-> 失败时 Replan
-> 汇总结果
```

风险：

- 初始计划错误会传导。
- 环境变化时重规划成本高。
- 简单任务会增加延迟。

### 7.3 Reflection / Reflexion

用途：

- 检查答案是否回答问题。
- 检查是否基于证据。
- 检查工具是否失败。
- 检查是否遗漏用户限制。
- 检查是否需要拒答或人工确认。
- 失败后重新检索、重新规划或降级。

建议首期实现轻量校验器：

```text
答案是否回答用户问题？
是否涉及价格/库存/订单等动态事实？
动态事实是否来自工具？
是否有权限访问？
是否需要用户确认？
是否有编造来源？
```

### 7.4 LATS / Tree Search

适用：

- 多条路径探索
- 复杂推理
- 高价值任务

特点：

- 把中间状态看成树节点。
- 扩展多个候选动作。
- 用评估器选择更优路径。

首期不建议实现，成本高、延迟高。可在复杂导购、策略规划阶段再考虑。

### 7.5 LangGraph / 状态机编排

适用：

- 复杂业务流程
- 条件分支
- 审查节点
- 重试拓扑
- 长任务检查点
- 可恢复执行

核心思想：

- 节点：意图识别、检索、工具执行、反思、输出。
- 边：条件路由。
- 状态：消息、计划、工具结果、错误、预算、引用。

对于 AIMall，建议后续将 Agent 编排显式建模为状态机，而不是散落在一个巨大函数里。

## 8. Agent 执行循环

最小 ReAct 循环：

```text
input -> load_context
for step in max_steps:
  llm_decide_next_action()
  if final_answer:
    break
  validate_tool_call()
  execute_tool()
  append_observation()
  if no_progress:
    break
reflection_check()
final_answer()
write_memory()
trace()
```

停止条件：

- 模型输出 Final Answer。
- 达到最大步数。
- 达到最大工具调用次数。
- 达到超时时间。
- Token 预算耗尽。
- 连续工具失败。
- 重复相同行动无新信息。
- 安全策略拒绝。
- 需要用户确认。

每一步必须记录：

- step index
- LLM 输入摘要
- LLM 输出动作
- 工具名
- 工具参数
- 工具结果摘要
- 耗时
- token
- 错误码
- 是否降级

## 9. 工具系统 Tools

工具是 Agent 的“手脚”，必须工程化封装。

工具定义必须包含：

```json
{
  "name": "search_products",
  "description": "按关键词、分类、预算搜索 AIMall 商品",
  "parameters": {
    "type": "object",
    "properties": {
      "keyword": { "type": "string" },
      "categoryId": { "type": "integer" },
      "minPrice": { "type": "number" },
      "maxPrice": { "type": "number" },
      "topK": { "type": "integer" }
    },
    "required": ["keyword"]
  },
  "permission": "PRODUCT_READ",
  "dangerous": false,
  "requiresConfirmation": false,
  "timeoutMs": 5000
}
```

工具返回必须结构化：

```json
{
  "ok": true,
  "code": "OK",
  "data": {},
  "summary": "找到 5 个商品",
  "citations": [],
  "retryable": false
}
```

工具工程要求：

- 参数校验
- 权限控制
- 超时控制
- 重试机制
- 幂等处理
- 错误归一化
- 调用日志
- 审计日志
- 敏感数据脱敏
- 高危操作人工确认

不要让模型直接操作数据库。正确方式：

```text
模型生成工具名和参数
-> 策略层校验权限和风险
-> 执行器调用后端 API 或服务
-> 返回结构化 Observation
```

## 10. AIMall 首期工具清单

商品工具：

- `search_products`：按关键词、分类、价格、销量、库存搜索商品。
- `get_product_detail`：查询商品详情、规格、图片、说明。
- `get_product_skus`：查询 SKU、价格、库存。
- `compare_products`：对比多个商品。

购物车工具：

- `get_cart`：查询购物车。
- `add_to_cart_preview`：生成加入购物车建议，不直接执行。
- `add_to_cart_confirmed`：用户确认后加入购物车。

订单工具：

- `list_my_orders`：查询当前用户订单。
- `get_my_order_detail`：查询当前用户订单详情。
- `cancel_order_preview`：说明取消影响。
- `cancel_order_confirmed`：用户确认后取消订单。

优惠券工具：

- `list_coupon_center`：查询可领取优惠券。
- `list_my_coupons`：查询我的优惠券。
- `claim_coupon_confirmed`：用户确认后领券。

地址工具：

- `list_my_addresses`：查询地址。
- `set_default_address_confirmed`：用户确认后设置默认地址。

售后工具：

- `list_my_returns`：查询售后。
- `get_return_detail`：查询售后详情。
- `apply_return_preview`：生成售后申请建议。
- `apply_return_confirmed`：用户确认后提交售后。

知识库工具：

- `search_policy_kb`：检索退换货、配送、优惠券、售后政策。
- `search_faq`：检索常见问题。

安全约束：

- 所有 `my_` 工具必须强制使用当前登录用户 ID，不能接受模型传入 userId。
- 所有写操作必须二次确认。
- 支付、退款、库存调整、后台管理不开放给普通用户 Agent。

## 11. 工具路由 Tool Routing

工具少时可以全部暴露给模型；工具多时必须路由。

不路由的风险：

- Prompt 太长。
- Token 成本高。
- 模型选错工具。
- 工具描述互相干扰。
- 响应变慢。

推荐分层路由：

```text
用户问题
-> 意图分类：商品 / 订单 / 售后 / 优惠券 / 地址 / 政策 / 闲聊
-> 工具组选择
-> 具体工具选择
-> 参数填写
```

路由方法：

- 规则路由：关键词、页面上下文、登录态。
- 分类模型路由：快模型输出工具组。
- 向量检索路由：检索最相关工具描述。
- 分组路由：先给模型工具组，再给具体工具。
- MCP Server：统一发现和治理工具。

工具描述要求：

- 写清何时使用。
- 写清何时不要使用。
- 写清输入输出。
- 写清权限。
- 写清失败场景。

## 12. RAG 知识检索系统

RAG 解决模型知识截止、企业私域知识、事实溯源和幻觉问题。

完整离线流程：

```text
文档采集
-> 文档解析 PDF / Word / Markdown / HTML / 表格 / OCR
-> 清洗
-> 去重
-> 质量评估
-> 分块
-> 元数据提取
-> Embedding
-> 向量库 / 关键词索引入库
```

完整在线流程：

```text
用户问题
-> 查询改写
-> 向量检索
-> BM25 关键词检索
-> 结构化过滤
-> RRF 融合
-> MMR 去冗余
-> Reranker 重排序
-> Top-K 证据
-> 拼 Prompt
-> 基于引用生成答案
-> 忠实度校验
```

分块策略：

- 固定长度分块：简单但可能切断语义。
- 递归字符分块：默认基线，按段落、句子、空格逐级切分。
- Markdown/标题分块：适合结构化文档。
- 语义分块：按语义变化切分，成本更高。
- Parent-Child Chunk：小块检索，大块生成，兼顾召回和上下文。
- 表格单独处理：保留表头、单位、行列关系。

检索策略：

- 向量检索：语义相似。
- BM25：关键词、编号、专有名词更稳。
- 混合检索：向量 + BM25。
- RRF：融合多路排序。
- Reranker：提高 Top-K 精度。
- MMR：降低重复内容。
- 元数据过滤：租户、权限、时间、文档类型。

高级 RAG：

- GraphRAG：适合关系型、多跳、全局聚合问题。
- Agentic RAG：Agent 决定是否检索、检索什么、是否继续检索。
- Self-RAG：模型自评是否需要检索、证据是否充分。
- Corrective RAG：检索质量差时重写查询或换检索策略。
- Adaptive RAG：按问题难度路由到不同检索管道。

首期 AIMall 推荐：

```text
商品结构化搜索 + 商城政策 RAG
-> 向量检索 + BM25
-> Top-K 5
-> 引用约束
-> 查不到拒答
```

回答规则：

- 政策类问题必须基于知识库引用。
- 商品价格、库存必须来自业务工具。
- 不能把模型记忆当事实来源。
- 无证据时回答“当前资料中没有查到”。

## 13. 记忆系统 Memory

记忆不是把所有对话都塞进 Prompt，而是分层管理上下文。

三层记忆：

### 13.1 工作记忆

当前任务内的临时状态：

- 当前目标
- 已执行步骤
- 工具观察结果
- 中间结论
- 当前计划
- 错误和重试状态

生命周期：

- 单次请求或单个任务结束即清理。

### 13.2 会话记忆

当前会话的上下文：

- 最近 N 轮对话
- 滑动窗口
- 会话摘要
- 用户在本会话中明确提出的限制

实现：

- Redis
- 数据库会话表
- 滑动窗口 + 摘要压缩

摘要压缩触发：

- 超过 N 轮
- 超过 Token 阈值
- 页面切换
- 任务结束

### 13.3 长期记忆

跨会话保留：

- 用户偏好：预算、常买品类、品牌偏好。
- 重要事实：用户主动声明的信息。
- 历史任务摘要。
- 企业知识。

长期记忆必须区分：

- 事实
- 用户偏好
- 推断
- 临时状态
- 已过期信息
- 来源
- 时间戳
- 权限范围

长期记忆写入规则：

- 不自动写敏感信息。
- 不把一次性需求写成长期偏好。
- 推断必须标记为推断。
- 用户可查看、修改、删除。
- 重要记忆要有来源。

记忆检索：

- 根据当前问题召回相关记忆。
- 只取与当前任务有关的 Top-K。
- 过期记忆不召回。
- 权限不匹配不召回。

## 14. Executor 执行器

模型只表达“想做什么”，执行器决定“能不能做、怎么安全做”。

执行器职责：

- 工具白名单
- 参数校验
- 权限判断
- 高危动作确认
- 幂等控制
- 超时控制
- 重试退避
- 熔断降级
- 错误归一化
- 结果摘要
- 审计日志

错误归一化示例：

```json
{
  "ok": false,
  "code": "ORDER_NOT_FOUND",
  "message": "订单不存在或无权访问",
  "retryable": false,
  "safeForUser": true
}
```

工具失败处理：

- 参数错误：把错误作为 Observation，让模型修正一次。
- 超时：重试一次或降级。
- 权限不足：停止并提示无权限。
- 业务状态不允许：解释原因并给替代建议。
- 连续失败：停止 Agent，返回可恢复说明。

## 15. Reflection 校验器

校验器不应只靠同一个模型自说自话，建议规则 + 模型混合。

输入校验：

- 是否违法违规。
- 是否包含 prompt 注入。
- 是否试图越权。
- 是否请求敏感信息。
- 是否要求高危操作。

过程校验：

- 工具是否可用。
- 参数是否合法。
- 是否重复调用。
- 是否超过预算。
- 是否存在无进展循环。

输出校验：

- 是否回答用户问题。
- 是否与工具结果冲突。
- 是否编造价格、库存、订单。
- 是否缺少引用。
- 是否泄露隐私。
- 是否需要人工确认。
- 格式是否符合协议。

不通过动作：

- 重新检索。
- 重新生成。
- 请求用户澄清。
- 降级为普通问答。
- 转人工。
- 拒答。

## 16. 安全与权限

安全层必须独立设计，不能只写在 Prompt 中。

基础安全：

- 用户鉴权
- 工具权限
- 租户隔离
- 数据访问控制
- 敏感字段脱敏
- Prompt 注入防御
- 最大步数
- 最大 Token
- 最大费用
- 最大工具调用次数
- 人在回路 HITL

高危操作：

- 支付
- 退款
- 删除
- 修改地址
- 修改订单
- 取消订单
- 提交售后
- 修改库存
- 发送消息或通知

处理原则：

- 默认不自动执行。
- 必须展示影响。
- 必须用户确认。
- 确认请求必须有短期 token 或 pending action id。
- 确认后重新校验权限和业务状态。

隐私保护：

- 日志中手机号、地址、邮箱脱敏。
- Trace 中不记录完整身份证、银行卡、token。
- RAG 检索按权限过滤。
- 长期记忆可删除。
- 管理后台可审计高危工具调用。

## 17. 可观测性与日志

生产级 Agent 必须能回答：

- 这一轮为什么这样回答？
- 用了哪些工具？
- 检索到了哪些内容？
- 哪一步失败？
- 花了多少 token？
- 哪个模型？
- 延迟卡在哪里？
- 是否发生降级？

Trace 结构：

```text
request span
  -> auth span
  -> guardrail span
  -> intent span
  -> memory_load span
  -> planning span
  -> rag_retrieval span
  -> tool_call span
  -> llm_call span
  -> reflection span
  -> memory_write span
  -> response_stream span
```

每次请求记录：

- trace_id
- user_id
- session_id
- page_type
- intent
- planner type
- model
- prompt version
- tool calls
- retrieval ids
- token input/output
- latency
- error code
- degraded
- final answer summary

日志分级：

- DEBUG：开发期详细上下文。
- INFO：请求摘要、状态转换。
- WARN：重试、降级、检索为空。
- ERROR：工具失败、模型失败、权限异常。
- AUDIT：高危工具调用、人工确认、数据修改。

最小可观测方案：

- 所有请求生成 `traceId`。
- 所有工具调用写结构化日志。
- 所有模型调用记录 token、模型名、耗时。
- 前端错误展示 traceId，便于排查。

推荐工具：

- OpenTelemetry
- Jaeger / Tempo
- Prometheus
- Grafana
- Langfuse / LangSmith
- 结构化日志

## 18. 模型路由、熔断与降级

三态熔断器：

- Closed：正常调用。
- Open：失败过多，直接拒绝或走备用。
- Half-Open：放少量请求探测恢复。

重试策略：

- 只对可重试错误重试。
- 使用指数退避。
- 总耗时不能超过请求预算。
- 不对高危写操作盲目重试，必须幂等。

降级链：

```text
强模型失败
-> 快模型简化回答
-> 纯 RAG 回答
-> 规则模板回答
-> 转人工 / 稍后再试
```

AIMall 示例：

- 商品推荐强模型超时：降级为结构化商品搜索 + 模板总结。
- 政策问答模型失败：返回检索到的政策摘要和引用。
- 订单工具失败：提示系统繁忙，不编造订单状态。

## 19. Token 与成本控制

控制手段：

- 工具路由，减少工具描述。
- 只注入相关工具。
- RAG 只取 Top-K。
- 检索内容摘要。
- 历史对话滑动窗口。
- 会话摘要压缩。
- System Prompt 精简并版本化。
- 简单任务走快模型。
- 缓存高频政策问答。
- Embedding 和 Rerank 批处理。

预算字段：

```json
{
  "maxSteps": 6,
  "maxTokens": 8000,
  "maxCostCents": 5,
  "timeoutMs": 180000
}
```

成本监控：

- 按用户
- 按会话
- 按模型
- 按工具
- 按功能模块
- 按租户

## 20. 多智能体 Multi-Agent

首期不建议上复杂多 Agent。

什么时候考虑：

- 工具数量超过 8 到 10 个，单 Agent 选错率上升。
- 任务天然分工明确。
- 需要并行探索。
- 需要审查者、批评者、总结者。
- 需要权限隔离。

可选角色：

- Supervisor Agent：意图识别、任务分派、汇总。
- RAG Agent：知识检索和基于引用回答。
- Tool Agent：业务 API 调用。
- Product Agent：商品推荐。
- Order Agent：订单和售后。
- Critic Agent：反思校验。
- Summary Agent：摘要压缩。

通信规则：

- 使用结构化 JSON，不用随意自然语言传递。
- 每个 Agent 有独立工具和权限。
- Supervisor 负责最终汇总。
- 子 Agent 失败可降级或跳过。
- 每个 Agent 调用有超时。

风险：

- 协调成本
- 延迟增加
- 错误级联
- 互相附和
- Trace 更复杂

## 21. 评估体系

Agent 不能只靠“看起来不错”。

Agent 指标：

- 任务完成率
- 答案正确率
- 工具调用成功率
- 工具选择准确率
- 平均步数
- 死循环率
- 幻觉率
- 拒答正确率
- 人工接管率
- 用户满意度
- 平均延迟
- P95 / P99 延迟
- Token 成本

RAG 指标：

- Faithfulness 忠实度
- Answer correctness 答案正确性
- Context relevance 上下文相关性
- Context precision
- Context recall
- Recall@K
- MRR
- 引用准确率
- 空召回率

测试集必须包含：

- 简单商品问答
- 预算导购
- 多条件推荐
- 商品对比
- 查不到商品
- 订单查询
- 越权订单查询
- 售后政策问答
- 需要工具的问题
- 需要 RAG 的问题
- 需要澄清的问题
- Prompt 注入攻击
- 高危操作确认
- 工具失败
- 模型超时

发布要求：

- Prompt 改动跑评估集。
- 模型切换跑评估集。
- 检索参数调整跑评估集。
- 工具 schema 变更跑回归。
- 新功能上线先灰度。

## 22. 部署与运维

服务要求：

- API 服务可水平扩展。
- 模型配置外置。
- Prompt 版本化。
- 工具清单版本化。
- RAG 索引版本化。
- 支持灰度发布。
- 支持快速回滚。
- 支持健康检查。
- 支持优雅停机。

部署组件：

- AI API 服务
- Agent Worker
- RAG 索引任务
- Redis
- MySQL / PostgreSQL
- 向量数据库
- 日志系统
- 监控告警

CI/CD：

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
-> 灰度 10% / 50% / 100%
-> 全量发布
```

运行告警：

- 模型错误率升高
- 工具错误率升高
- RAG 空召回升高
- P95 延迟升高
- Token 成本异常
- 死循环率升高
- 高危操作异常
- Prompt 注入命中增多

## 23. AIMall 推荐落地路线

### 阶段 0：整理旧 AI 功能

- 梳理现有 AI 接口。
- 删除无用占位逻辑。
- 明确流式响应协议。
- 增加 traceId。
- 配置模型超时、重试、降级。
- 明确 AI 服务和商城服务边界。

### 阶段 1：普通 ChatBot

- 接入模型。
- 支持流式输出。
- System Prompt 规范化。
- 保留会话 ID。
- 不调用业务工具。
- 不写长期记忆。

### 阶段 2：RAG Bot

- 建商城政策知识库。
- 导入退换货、配送、优惠券、售后、支付说明。
- 支持政策检索。
- 回答带引用。
- 查不到拒答。

### 阶段 3：ReAct + 工具调用

- 接入商品搜索工具。
- 接入商品详情工具。
- 接入订单查询工具。
- 接入优惠券查询工具。
- 接入售后查询工具。
- 支持工具 Observation。
- 设置 max_steps 和 timeout。

### 阶段 4：可确认动作

- 加入购物车确认。
- 领取优惠券确认。
- 取消订单确认。
- 提交售后确认。
- 前端展示确认卡片。
- 后端 pending action 校验。

### 阶段 5：记忆

- Redis 保存短期会话。
- 超长对话摘要压缩。
- 用户偏好长期记忆。
- 用户可管理记忆。

### 阶段 6：反思和安全

- 输出校验器。
- Prompt 注入检测。
- 权限校验。
- 工具调用审计。
- 高危动作拦截。

### 阶段 7：可观测和评估

- Trace 全链路。
- token / latency / error 指标。
- 评估集。
- Prompt A/B。
- 灰度发布。

### 阶段 8：多 Agent 和高级 RAG

- Supervisor + Product Agent + Order Agent。
- Agentic RAG。
- Corrective RAG。
- GraphRAG。
- 多模型路由。

## 24. AIMall 首期最小可行 Agent

首期 MVP 不做多 Agent，建议这样：

```text
用户问题
-> 输入安全检查
-> 意图识别
-> 加载最近会话
-> 根据意图选择工具组
-> ReAct 循环最多 4 步
   -> 商品/订单/售后/优惠券/政策工具
-> 轻量反思校验
-> 流式回答
-> 写入会话摘要
-> 记录 Trace
```

MVP 必须有：

- LLM
- System Prompt
- 1 个政策 RAG 工具
- 1 个商品搜索工具
- 1 个订单查询工具
- ReAct 循环
- 短期会话历史
- 最大步数限制
- 流式输出
- traceId
- 工具日志

MVP 暂不做：

- 多 Agent
- GraphRAG
- 长期记忆
- 自动下单
- 自动退款
- 自动修改业务数据

## 25. 开发检查清单

每个 Agent 功能上线前检查：

- [ ] 是否明确用户、输入、输出、成功标准？
- [ ] 是否明确允许自动做什么、必须确认什么？
- [ ] 是否有 System Prompt 版本？
- [ ] 是否有工具 schema？
- [ ] 是否有工具权限？
- [ ] 是否有参数校验？
- [ ] 是否有超时和重试？
- [ ] 是否有最大步数？
- [ ] 是否有 traceId？
- [ ] 是否记录 LLM 调用？
- [ ] 是否记录工具调用？
- [ ] 是否记录 token 和耗时？
- [ ] 是否有拒答规则？
- [ ] 是否能防 Prompt 注入？
- [ ] 是否脱敏日志？
- [ ] 是否有评估用例？
- [ ] 是否支持降级？
- [ ] 是否支持人工确认？
- [ ] 是否不会越权读取其他用户数据？
- [ ] 是否不会编造商品、价格、库存、订单？

## 26. AIMall 工程目录建议

AI 服务建议结构：

```text
aimall-ai-service/
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
      product_tools.py
      order_tools.py
      coupon_tools.py
      return_tools.py
      policy_tools.py
    rag/
      loader.py
      splitter.py
      retriever.py
      reranker.py
      citations.py
    memory/
      session_memory.py
      summary_memory.py
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

后端商城服务建议新增：

```text
aimall-server/
  ai/
    AiToolController.java
    dto/
      ProductSearchToolRequest.java
      ToolResponse.java
```

原则：

- AI 服务不直接查商城数据库。
- AI 服务通过商城后端受控工具 API 访问业务数据。
- 所有用户级工具 API 从 token 获取 userId。
- 所有写操作走确认机制。

## 27. 最重要的落地原则

- 先做小场景，不做万能助手。
- 先做可观测，再做复杂能力。
- 先做只读工具，再做写操作。
- 先做单 Agent，再做多 Agent。
- 先做 RAG + 工具，再做长期记忆。
- 所有动态事实必须来自工具或知识库。
- 所有高危动作必须用户确认。
- 所有工具调用必须可审计。
- 所有 Prompt、工具、模型、索引必须版本化。
- 每次改动都要能评估、能回滚。
