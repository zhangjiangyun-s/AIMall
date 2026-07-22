# 阶段 25 生产证据采集清单

本清单只用于收集第 25 阶段生产证据。`local`、`test`、支付宝沙箱、Mock、临时 Docker 应用容器和开发机日志都不能直接填入生产证据表。

## 使用规则

1. 每一项必须由真实 Owner 填写 `owner`、`approver`、`verifiedAt` 和不可变 `evidenceRefs`。
2. 证据链接必须指向只读对象、工单、报告或审计记录，并记录 SHA-256；不能只填写“已验证”。
3. 发现失败时保留原始证据，状态填 `UNVERIFIED`，写明阻断原因，不得删除失败记录后重跑。
4. 全部 9 项达到 `VERIFIED` 前，`tools/stage25_v12_gate.py --require-production` 必须保持退出码 `3`。

## S25-01 金额

需要：全量旧列到高精度列转换差异报告为零；订单、支付、退款抽样三方对账；包含优惠分摊和最后一项尾差的完整结算周期；迁移前加密备份及恢复校验。

证据最低字段：数据库快照 ID、转换行数、差异行数、抽样范围、对账结果、结算周期起止时间、备份对象版本、恢复校验结果。

## S25-02 AI 模式

需要：生产启动日志证明模式为 `PRODUCTION`；变更单、兼容性检查、灰度租户/用户范围、观察指标和回退记录；确认配置篡改会 fail-closed，且不存在 MOCK 资金工具路径。

## S25-03 Outbox

需要：至少一次投递重复场景；Worker 崩溃后租约接管；超过 45 秒续租；处理超时；死信进入 `MANUAL_REVIEW`；管理员重试和关闭；业务幂等结果不重复扣款/扣库存。

证据必须包含事件 ID、Payload Hash、Trace ID、原 Worker、接管 Worker、时间线和最终本地事实。

## S25-04 发货

需要：全额支付、部分支付、退款、拒付/风控冻结、部分退款、并发发货和相同物流幂等键场景。每个场景都要证明发货条件、订单项数量和支付事实没有被绕过。

## S25-05 租户

需要：生产配置明确 `SINGLE_TENANT`；非默认 tenant 的 HTTP、Redis、Milvus、Outbox、导出和错误响应泄漏测试；部署配置和网络边界审查记录。

## S25-06 健康端点

需要：从公网、应用内网和管理网络分别执行访问矩阵；证明只有 liveness 公开；core、AI、payment readiness 使用 HMAC；管理员诊断脱敏；Redis、Milvus、支付 provider 故障时只摘除对应能力。

## S25-07 RAG

需要：固定 manifest hash；固定种子 `25001/25002/25003` 的三次独立报告；两名独立标注人和第三名裁决人；检索 chunk、提示词、训练材料和答案的泄漏审计。

## S25-08 恢复与性能

需要：MySQL binlog/PITR 恢复、Redis AOF 恢复、Milvus 从 MySQL chunk 重建、恢复期间 worker 闸门和租户数据校验；生产等价拓扑持续至少 3,600 秒，并记录 CPU、内存、DB 连接池、P99 锁等待、业务错误率、缓存命中率和降级行为。

## S25-09 Owner

需要为 `PAY-001`、`INV-001`、`TENANT-001`、`RAG-001`、`DR-001` 填写真实 Owner、Backup、Approver、截止日期、依赖、证据链接、阻断原因和更新时间。角色名称不能替代姓名或团队责任主体。

## 验收命令

工程验收：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stage25-quality-gate.ps1 -RequireEngineering
```

生产验收：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\stage25-quality-gate.ps1 -RequireProduction
```

生产验收只有在 9 项证据真实填写并通过审计后才允许返回 0。当前生产证据仍为空，因此预期返回非零退出码。
