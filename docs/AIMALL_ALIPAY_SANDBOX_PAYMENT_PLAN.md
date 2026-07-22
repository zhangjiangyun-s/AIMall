# AIMall 支付宝沙箱支付接入方案

> 状态：方案阶段，暂不修改交易代码
> 目标环境：支付宝沙箱
> 重要边界：沙箱支付只能用于联调和验收，不代表生产支付已完成，也不能解除生产支付门禁。

## 1. 目标和非目标

### 1.1 本阶段目标

完成一条可重复验收的支付宝沙箱链路：

```text
创建订单
  -> 创建支付宝沙箱支付单
  -> 返回 payment URL/form
  -> 沙箱买家完成支付
  -> 同步返回仅用于展示
  -> 异步 notify 回调落库
  -> 验签、金额核对、订单归属核对
  -> 幂等推进支付状态
  -> 扣减库存/确认订单可发货
  -> 支付状态查询和对账
```

同时覆盖：支付取消、支付失败、重复回调、回调乱序、金额不一致、回调超时、主动查单和订单超时关单竞态。

### 1.2 明确不包含

- 不接入生产支付宝应用。
- 不把 `/api/pay/simulate` 改造成生产支付接口。
- 不使用前端“支付成功页面”直接修改订单为已支付。
- 不在支付宝回调前发货。
- 不把沙箱退款结果视为生产退款闭环。
- 不在本阶段开放真实支付配置。

## 2. 当前代码适配边界

当前项目已有：

- `PayService` / `PayServiceImpl`
- `PayController`
- `OmsPaymentRecord`
- `payment_attempt`、`payment_callback_event`、`outbox_event` 基础迁移
- 订单支付状态与库存扣减逻辑
- 退款任务状态机和 `RefundGateway` 抽象
- 生产环境禁止模拟支付的配置校验

当前缺口：

- 没有 `AlipayPaymentProvider`。
- 没有沙箱支付下单接口。
- 没有支付宝 `notify_url` 回调接口。
- 没有支付宝回调验签和金额核验。
- 没有支付尝试与订单支付单的完整状态 CAS。
- 没有主动 `trade_query` 和 `trade_close` 任务。
- 退款接口仍不能宣称为真实渠道退款。

## 3. 支付宝沙箱准备工作

### 3.1 支付宝侧

在支付宝开放平台沙箱环境创建并记录：

- 沙箱应用 App ID。
- 应用私钥。
- 支付宝公钥。
- 签名类型，优先使用 RSA2。
- 沙箱买家账号。
- 沙箱手机/支付密码。
- 沙箱回调域名或可被支付宝访问的 HTTPS 地址。
- 沙箱退款能力和测试限制。

密钥不得写入 Git、README、Compose 文件或日志。使用本地 `.env` 或开发机 secret store 注入。

### 3.2 本地回调

支付宝异步回调必须能访问开发机：

- 优先使用 HTTPS 隧道映射本地回调端口。
- 隧道域名写入 `ALIPAY_NOTIFY_BASE_URL`。
- 不使用临时随机域名作为长期测试配置。
- 本地回调请求必须经过 Nginx/网关转发到 Java 服务。
- AI 服务不能接收或处理支付宝回调。

示例配置：

```env
AIMALL_ENVIRONMENT=sandbox
AIMALL_PAYMENT_PROVIDER=ALIPAY_SANDBOX
ALIPAY_ENABLED=true
ALIPAY_APP_ID=<sandbox-app-id>
ALIPAY_PRIVATE_KEY_FILE=<local-secret-file>
ALIPAY_PUBLIC_KEY_FILE=<local-secret-file>
ALIPAY_GATEWAY_URL=https://openapi-sandbox.dl.alipaydev.com/gateway.do
ALIPAY_NOTIFY_BASE_URL=https://<approved-tunnel-domain>
ALIPAY_RETURN_URL=https://<web-domain>/payment/alipay/return
ALIPAY_SIGN_TYPE=RSA2
ALIPAY_CHARSET=UTF-8
ALIPAY_FORMAT=json
```

生产配置必须使用独立变量、独立 key 和生产网关，禁止 sandbox 与 production 共用密钥。

## 4. 组件设计

### 4.1 Provider 接口

Java 业务层只依赖 provider 接口：

```text
PaymentProvider.createPayment(request)
PaymentProvider.queryPayment(request)
PaymentProvider.closePayment(request)
PaymentProvider.refund(request)
PaymentProvider.queryRefund(request)
```

实现：

- `AlipaySandboxPaymentProvider`：本阶段启用。
- `AlipayProductionPaymentProvider`：后续真实生产阶段实现，不在本阶段启用。
- `DisabledPaymentProvider`：provider 未配置时明确返回 `PAYMENT_PROVIDER_NOT_READY`。
- `SimulatedPaymentProvider`：仅 local/test profile，不能被 sandbox/prod 选中。

Provider 只负责支付宝协议和 SDK 调用，不负责订单状态、库存和优惠券。

### 4.2 支付请求参数

创建支付时由服务端生成：

```text
out_trade_no       = 本地 payment_attempt.attempt_id
total_amount       = 服务端订单应付金额
subject            = 服务端订单标题，限制长度并脱敏
product_code       = FAST_INSTANT_TRADE_PAY
notify_url         = 服务端固定回调地址
return_url         = 前端展示地址，不作为支付事实来源
```

客户端只能提交 `orderId` 和支付意图，不能提交金额、订单号、回调地址、支付状态或支付宝交易号。

### 4.3 数据表使用

#### `oms_payment_record`

保存订单级支付汇总：

- `order_id`
- `pay_channel = ALIPAY`
- `pay_status`
- `payment_state`
- `amount`
- `paid_amount`
- `transaction_no`：支付宝交易号
- `provider_reference`
- `pay_time`
- `callback_time`
- `raw_callback`：只保存加密/脱敏或受限存储版本

#### `payment_attempt`

每次发起支付一条：

- `attempt_id` 作为支付宝 `out_trade_no`
- `request_id` 作为应用幂等键
- `provider = ALIPAY_SANDBOX`
- `amount`、`currency`
- `state`
- `provider_reference`
- `expires_at`
- `last_query_at`

#### `payment_callback_event`

回调先落库再处理：

- `provider + event_id` 唯一。
- `payload_hash` 防止同一事件内容被替换。
- 保存验签结果、处理状态和失败原因。
- 原始回调中的姓名、买家账号等敏感字段必须脱敏或加密。

#### `outbox_event`

支付事实提交后投递：

- `PaymentSucceeded`
- `PaymentFailed`
- `PaymentUnknown`
- `InventoryDeductRequested`
- `OrderShipEligibilityChanged`

## 5. 支付状态机

### 5.1 PaymentOrderState

```text
INIT
  -> CREATED
  -> WAITING_PAYMENT
  -> PROCESSING
  -> PAID

PROCESSING -> FAILED
PROCESSING -> UNKNOWN
UNKNOWN -> QUERYING -> PAID / FAILED / MANUAL_REVIEW_REQUIRED
WAITING_PAYMENT -> CLOSING -> CLOSED / CLOSE_UNKNOWN
PAID -> PARTIALLY_REFUNDED -> REFUNDED
PAID -> RISK_HOLD / CHARGEBACK / MANUAL_REVIEW_REQUIRED
```

### 5.2 关键约束

- 当前版本禁止部分支付发货。
- 只有 `PaymentOrderState=PAID` 且 `paid_amount == payable_amount` 才允许发货。
- 同一订单只能有一个有效支付事实，但可以有多个历史支付尝试。
- 同一 `provider + request_id` 只能创建一个支付尝试。
- 同一支付宝 `out_trade_no` 不能被不同订单复用。
- 回调成功不能覆盖已经完成退款或人工冻结的支付状态。
- 订单关闭后晚到支付必须进入 `LATE_PAYMENT_AFTER_CLOSE` 对账队列，不能直接发货。

## 6. 支付下单流程

```text
POST /api/payments
  -> 登录身份和订单归属校验
  -> 订单状态必须 WAIT_PAY
  -> 读取服务端 payable_amount
  -> 检查订单未过期、无退款/风控阻断
  -> 创建 payment_attempt + payment record（本地事务）
  -> 提交后调用支付宝 trade_page_pay
  -> 成功得到支付 form/url，更新 attempt CREATED
  -> HTTP 超时标记 UNKNOWN，交给 query worker
```

外部调用不能放在创建数据库事实的事务中。支付宝请求超时不能判定失败，必须主动查单。

## 7. 异步回调流程

### 7.1 接收顺序

```text
接收 notify
  -> 读取原始参数
  -> 验证必填字段和编码
  -> 验证 RSA2 签名
  -> 计算 payload_hash
  -> 插入 payment_callback_event（唯一键）
  -> 立即返回支付宝要求的成功响应
  -> Outbox/worker 异步处理业务
```

只有验签成功的回调才能进入业务处理队列。重复回调返回成功但不重复执行业务。

### 7.2 业务回调校验

必须同时验证：

- `out_trade_no` 对应本地 payment_attempt。
- `seller_id/app_id` 属于当前沙箱配置。
- `trade_status` 属于允许状态。
- `total_amount == attempt.amount`，使用统一金额比较规则。
- `currency` 和业务币种一致。
- 支付宝交易号未绑定其他订单。
- 当前订单仍属于该 member/tenant。

金额不一致、订单不存在、签名错误、商户号不一致进入拒绝/人工队列，不推进支付成功。

### 7.3 业务落库

处理 `TRADE_SUCCESS` 或 `TRADE_FINISHED`：

1. 锁定 payment attempt 和 payment record。
2. 判断当前状态是否已经 PAID。
3. 已 PAID：只记录重复回调，不重复扣库存。
4. 未 PAID：CAS 更新支付状态和已付金额。
5. 同一事务写 `PaymentSucceeded` Outbox。
6. 提交后由库存消费者执行扣减或确认预占。
7. 发货服务重新计算发货允许谓词。

## 8. 主动查单和关单

### 8.1 查单 worker

扫描：

- `PROCESSING`
- `UNKNOWN`
- `QUERYING`
- 支付请求响应丢失的 attempt

处理规则：

- 查询前使用数据库 lease，避免多实例重复查单。
- 查到成功走与回调完全相同的幂等处理函数。
- 查到关闭/失败释放支付尝试，但库存释放必须依赖订单 CAS。
- 连续查询超过阈值进入 `MANUAL_REVIEW_REQUIRED`。

### 8.2 关单竞态

关单和支付可能并发：

- 订单超时任务先 CAS `WAIT_PAY -> CLOSING`。
- CLOSING 期间先查支付宝。
- 支付已成功：转 `PAID`，不得关闭。
- 支付未创建或确认失败：调用 `trade_close`，成功后 `CLOSED`。
- `trade_close` 超时：`CLOSE_UNKNOWN`，禁止释放未确认的资金事实，进入查单队列。
- 订单已 CLOSED 后收到支付成功：进入晚到支付人工队列，不能自动发货。

## 9. 退款沙箱范围

本阶段只接入支付宝沙箱退款验证，不宣称生产退款完成。

退款请求规则：

- `refund_request_id` 必须全局唯一。
- 发起前先查询本地退款记录，再查询支付宝退款状态。
- 渠道请求超时进入 `REFUND_UNKNOWN`，不能直接重试。
- 重试前必须 `refund_query`。
- 只有渠道确认成功后才更新支付单、订单、库存和优惠券。
- 回调/查询成功与本地事务分离，用 refund outbox 恢复。
- 渠道成功、本地落库失败时，后续只能查单，不得再次提交退款。

## 10. 测试方案

### 10.1 Provider 契约测试

使用 sandbox stub 固定验证：

- 签名正确/错误。
- 金额相等/不等。
- `TRADE_SUCCESS`、`TRADE_FINISHED`、失败、关闭、未知。
- 同一回调重复 10 次。
- 回调乱序。
- provider 超时但实际成功。
- 查询结果与回调结果冲突。

### 10.2 真实沙箱 E2E

每条用例都检查：

- HTTP 响应。
- `payment_attempt` 最终状态。
- `oms_payment_record` 最终状态和金额。
- 订单是否允许发货。
- 库存流水是否只产生一次。
- Outbox 是否 `SUCCEEDED` 或进入人工队列。
- 审计日志、traceId 和 callback event 是否完整。

### 10.3 沙箱验收门禁

- 正常支付成功：连续 3 次成功。
- 重复回调：业务扣库存次数始终为 1。
- 金额篡改：0 次进入 PAID。
- 签名篡改：0 次进入 PAID。
- 关单/支付竞态：不出现 CLOSED 且自动发货。
- 响应丢失：查单可恢复，不能重复创建有效支付事实。
- 沙箱退款：渠道成功后本地最终状态一致。
- 退款未知：不会重复提交，能进入人工复核。

## 11. 配置和回滚

### 11.1 沙箱配置门禁

```text
AIMALL_ENVIRONMENT=sandbox
AIMALL_PAYMENT_PROVIDER=ALIPAY_SANDBOX
ALIPAY_ENABLED=true
AIMALL_PAYMENT_SIMULATION_ENABLED=false
```

生产环境不得使用 `ALIPAY_SANDBOX`。provider、App ID、私钥、公钥、回调域名必须按环境独立。

### 11.2 回滚

- 支付入口：关闭 `ALIPAY_ENABLED`，订单查询仍可用。
- 回调入口：保留接收和落库，暂停业务消费，避免丢事件。
- Outbox：停止消费者，不删除事件，保留人工恢复。
- 数据迁移：只做前滚兼容；表和字段暂不删除。
- 沙箱支付失败不回退到 `/api/pay/simulate`，而是返回 `PAYMENT_PROVIDER_NOT_READY`。

## 12. 本阶段结论

本方案执行完成后，阶段状态只能按以下规则判断：

| 状态 | 条件 |
|---|---|
| 基础设施完成 | 迁移、provider 接口、状态机、Outbox 和配置校验通过 |
| 沙箱联调完成 | 支付下单、回调、查单、关单、退款场景全部通过 |
| P0-3 全量完成 | 沙箱验收、真实 MySQL 并发、故障恢复、对账、监控和 Runbook 全部通过 |
| 生产支付完成 | 生产渠道凭据、生产回调、生产前演练、双人复核和生产灰度全部通过 |

即使沙箱联调完成，`P0-3` 仍不能直接标记为生产完成；真实生产支付和退款仍需单独 Gate。
