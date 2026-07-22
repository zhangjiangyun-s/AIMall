# AIMall 阶段 9 可观测性与告警 Runbook

## 1. 责任与升级

| 领域 | Primary owner | Secondary owner | P0 升级 | P1 升级 |
|---|---|---|---|---|
| 支付、退款、对账 | payment-oncall | DBA | 立即通知，10 分钟内建立事故指挥 | 15 分钟内响应 |
| 订单、库存 | commerce-oncall | DBA | 立即通知并冻结受影响写流量 | 30 分钟内响应 |
| Java、MySQL、Flyway | platform-oncall | DBA | 立即通知 | 15 分钟内响应 |
| AI、Redis、SSE | ai-platform-oncall | platform-oncall | 立即通知 | 30 分钟内响应 |
| RAG、Milvus、知识发布 | rag-quality | ai-platform-oncall | 立即通知 | 30 分钟内响应 |

生产必须把上述逻辑 owner 映射到真实值班组、电话、IM 群和升级负责人。默认 Alertmanager webhook 只是模板，未配置真实接收方前不能认定生产告警闭环完成。

## 2. 指标入口

- Java：`GET /actuator/prometheus`
- AI：`GET /observability/prometheus`
- 管理诊断：`GET /api/admin/observability`
- Java 集成健康：`GET /api/health/integration`
- AI 集成健康：`GET /health/integration`

Prometheus 使用同一个只读观测令牌文件，通过 Bearer Token 抓取。生产令牌至少 32 字符，不得与 Java/AI HMAC、管理员 token 或数据库密码复用。

## 3. 通用处置顺序

1. 记录告警首次时间、当前值、阈值、影响范围和 traceId。
2. 判断是数据不一致、依赖不可用、容量不足还是代码回归。
3. 对资金和库存问题先阻止扩大影响，再做恢复；禁止先重试后取证。
4. 只通过受支持的人工恢复接口、任务重试接口或新 Flyway 前滚迁移修复。
5. 恢复后核对业务数据、渠道数据和指标归零，保留事故记录与时间线。

## Payment Inconsistent

- 触发：`aimall_payment_unknown > 0` 或 `aimall_payment_reconciliation_open > 0`。
- 影响：订单状态与支付宝资金状态可能不一致。
- 立即动作：暂停受影响订单发货；按订单号、支付号和渠道交易号查单。
- 恢复：使用支付恢复/对账处理接口，确认 fencing 和幂等键后重放 Outbox。
- 禁止：直接改订单为已支付、删除支付流水或重复发起扣款。

## Refund Stuck

- 触发：`aimall_refund_unresolved > 0` 持续 5 分钟。
- 立即动作：按退款 requestId 查渠道状态，确认是否已真实出款。
- 恢复：渠道成功则只完成本地状态；渠道未退款才允许受控重试；无法确认进入人工处理。
- 核对：售后状态、退款流水、订单退款金额和退货数量预占一致。

## Inventory Invariant

- 触发：SPU/SKU 出现负库存、负锁定或 `stock < lock_stock`。
- 立即动作：下架受影响商品并停止库存调整。
- 恢复：核对库存流水、订单预占、支付扣减和退款恢复，使用审计库存调整修复。
- 禁止：无流水依据直接覆盖 stock/lock_stock。

## Database Or Migration

- 触发：Java scrape `up == 0`，或启动日志出现 Flyway validate/migrate 失败。
- 立即动作：检查 MySQL 可用性、连接池、锁等待、磁盘和 `flyway_schema_history`。
- 恢复：DDL 失败采用新的前滚迁移；checksum 异常必须完成备份和结构审计后受控 repair。
- 禁止：自动 repair、删除 history 或在生产手工重复执行版本 SQL。

## Redis Unavailable

- 影响：Pending Action、会话、执行租约和防重放 nonce 失败关闭。
- 恢复：恢复 Redis 连接、认证和持久化；验证 keyspace、过期和锁脚本后再开放 AI Action。
- 禁止：生产切换到 memory backend 绕过故障。

## AI Degraded

- 检查：LLM 路由、超时率、Java 工具、Redis、Milvus、SSE 活跃连接。
- 恢复：先保持商城核心业务独立；必要时关闭 AI 入口或昂贵 fallback，不伪造业务答案。

## Milvus Inconsistent

- 立即动作：停止新知识版本发布。
- 检查：MySQL chunk 数、Milvus row count、版本状态、execution token、删除积压。
- 恢复：运行一致性检查和 fenced 重建；确认目标版本全量 ACTIVE 后再下线旧版本。

## Dead Letters

- 对象：Outbox、知识任务、向量删除、Pending Action。
- 检查：最后错误、retry count、traceId、execution token、租约和依赖状态。
- 恢复：修复根因后使用人工 retry/resolve API；旧 attempt 不得覆盖新 attempt。

## RAG Quality

- 触发：RAG no-match rate 超过 40% 持续 15 分钟。
- 检查：查询分布、发布版本、embedding 失败、检索过滤和版本化评估集。
- 恢复：回滚有问题的知识版本或重建当前版本，禁止降低引用校验掩盖问题。

## LLM Cost

- 检查：模型、purpose、token、fallback、反思重试和异常来源。
- 恢复：限制异常来源、关闭昂贵 fallback、修复循环调用；保留成本审计。

## SSE Limit

- 检查：活跃连接、P95 时延、来源分布和 429 数量。
- 恢复：先处理慢调用或异常来源，再基于容量测试调整上限。

## Login Failure Burst

- 检查：失败账号、客户端 IP、设备指纹、User-Agent 和管理员权限级别。
- 恢复：启用或收紧账号/IP/设备限流，冻结被攻击账号，必要时强制改密和撤销设备。
- 升级：涉及管理员、批量账号或异常成功登录时立即升级为安全事件。

## 4. 日志与保留

- Java 与 AI 应用主日志输出 stdout/stderr，由 Loki、ELK 或云日志采集。
- 日志必须包含 service、level、timestamp、logger 和 traceId，不记录密码、token、完整手机号、地址或支付原文。
- 普通运行日志默认保留 30 天；管理员操作、Agent Trace、Action 审计和反馈默认保留 180 天。
- Java 每日分批清理过期管理员审计；AI 写入审计副本时清理过期 JSONL。
- Loki 示例配置保留 30 天。生产保留期必须由业务、Security 和合规负责人审批。
- 删除日志前确认不存在未关闭的资金、隐私、安全或诉讼保全事件。

## 5. 验收证据

生产闭环至少需要：Prometheus targets 全绿、告警测试通知、Loki 查询 traceId、一次 P0 和一次 P1 演练、值班接收确认、保留期和删除任务执行记录。
