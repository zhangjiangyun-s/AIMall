# AIMall RAG 知识库开发执行方案

本文档是 AIMall 后续 RAG、知识库上传、PDF/DOCX 解析、向量入库、检索治理的唯一执行基线。

后续开发必须严格按本文档推进。若实现过程中发现新问题，应先更新本文档，再按更新后的方案开发，避免多轮对话后计划漂移。

当前目标不是做一个演示版“上传文件问答”，而是建设一条可观测、可重试、可回滚、可对账、可治理的 RAG 知识库入库流水线。

## 1. 总目标

AIMall Admin 需要支持上传商城规则、售后政策、优惠券规则、FAQ、活动说明等知识文档，文件格式包括：

- PDF
- DOCX
- Markdown
- TXT

上传后系统自动完成：

```text
文件校验
-> PDF / DOCX 解析
-> 文本清洗
-> 敏感信息检测
-> Prompt 注入检测
-> 结构化分块
-> Embedding 向量化
-> Milvus 写入
-> MySQL / Milvus 一致性对账
-> 检索自测
-> 质量评分
-> 人工审核 / 发布
-> AI 导购 RAG 检索可用
```

最终 AI 导购回答商城规则问题时，必须能够展示引用依据，并且引用依据能追溯到：

- 文档 ID
- 文档版本
- chunk ID
- 文档标题
- 章节路径
- 页码
- 原始片段

## 2. 系统架构

```text
aimall-admin
  管理员上传文档、查看处理过程、发布文档、查看质量报告

aimall-server Java
  负责 admin 鉴权、上传入口、文档元数据、任务状态、审计日志、权限控制

aimall-ai-service Python
  负责 PDF / DOCX 解析、文本清洗、分块、Embedding、Milvus 同步、RAG 检索自测

MySQL
  存储文档、版本、chunk、任务、事件、审计、质量报告

Milvus
  存储 chunk 向量，作为可重建检索索引
```

设计原则：

- 前端不直接连接 ai-service。
- 前端不直接连接 Milvus。
- Java 后端是权限、任务、审计、文件下载的统一入口。
- Python 服务是 AI / RAG 处理执行器。
- MySQL 是可信数据源。
- Milvus 是可重建索引，不作为唯一数据源。

面试表达：

> 我们没有把 PDF/DOCX 上传当成普通文件上传，而是把它设计成知识库文档生命周期管理和 RAG 索引流水线。Java 负责权限、任务和审计，Python 负责解析、分块和向量化，MySQL 作为可信源，Milvus 作为可重建检索索引。

## 3. Admin 前端功能

新增菜单：`知识库管理`。

包含 5 个页面或 Tab：

```text
1. 文档列表
2. 上传文档
3. 处理任务
4. 文档详情
5. 质量治理 / 监控大盘
```

### 3.1 文档列表

展示字段：

- 标题
- 文档类型
- 文件类型
- 状态
- 当前版本
- 负责人
- chunk 数
- 向量同步状态
- Prompt 风险等级
- 质量评分
- 最近更新时间

操作：

- 详情
- 发布
- 禁用
- 归档
- 重建索引
- 回滚版本
- 下载原文

### 3.2 上传文档

上传字段：

- 文件：PDF / DOCX / MD / TXT
- 文档标题
- 文档类型：POLICY / FAQ / GUIDE / ACTIVITY
- 可见范围：普通用户 / 客服 / 管理员
- 适用角色
- 适用商品类目
- 生效时间
- 失效时间
- 标签
- 负责人

第一阶段限制：

- 单文件最大 20MB。
- PDF 最大 300 页。
- 单文档最大 chunk 数量需要设置上限，避免超大文档拖垮 embedding 和 Milvus。

### 3.3 处理任务

展示：

- 总进度
- 分阶段进度
- 处理时间线
- 失败明细
- 错误建议
- 一键重试
- 取消任务
- 死信任务重试

处理过程不叫“AI 思考过程”，应命名为：

- 文档处理过程
- 知识库入库过程
- RAG 索引过程

### 3.4 文档详情

展示：

- 原始文件
- 解析文本
- 脱敏文本
- chunk 列表
- 表格 chunk
- 版本历史
- 检索自测结果
- 引用跳转
- 审计日志

### 3.5 质量治理 / 监控大盘

展示：

- 待处理任务数
- 运行中任务数
- 今日成功数
- 今日失败数
- 失败率
- 平均处理耗时
- Embedding 调用次数
- Milvus 同步失败数
- 死信任务数
- 低质量文档清单
- 高风险 Prompt 注入文档清单

## 4. 状态机

### 4.1 文档状态

```text
DRAFT
UPLOADED
INDEXING
REVIEW_REQUIRED
READY_TO_PUBLISH
ACTIVE
DISABLED
ARCHIVED
FAILED
```

### 4.2 文档版本状态

```text
DRAFT
PROCESSING
READY
ACTIVE
DISABLED
ARCHIVED
FAILED
```

### 4.3 任务状态

```text
PENDING
RUNNING
PARTIAL_FAILED
FAILED
DEAD_LETTER
SUCCESS
CANCELED
```

### 4.4 发布规则

- 新文档处理完成后进入 `READY_TO_PUBLISH`。
- 管理员发布后才进入 `ACTIVE`。
- 新版本发布成功后，旧版本软下线。
- 回滚时恢复历史版本，软删除当前版本向量。
- `FAILED`、`DISABLED`、`ARCHIVED` 文档不得被线上 RAG 检索召回。

## 5. 完整处理流水线

### 5.1 上传前检查

检查内容：

- 文件名
- 文件大小
- 文件后缀
- source_hash 是否重复
- 管理员是否有上传权限
- 租户容量是否超限

第一阶段可以由后端计算 SHA-256。后续再做前端预计算、秒传、分片上传。

### 5.2 文件上传

Java 后端接收文件并保存原始文件。

建议路径：

```text
storage/knowledge/original/{doc_id}/{version}/source.pdf
storage/knowledge/parsed/{doc_id}/{version}/parsed.json
storage/knowledge/preview/{doc_id}/{version}/preview.txt
```

上传完成后创建：

- `knowledge_doc`
- `knowledge_doc_version`
- `knowledge_index_task`
- `knowledge_task_event`
- `knowledge_audit_log`

### 5.3 基础校验

校验内容：

- 后缀白名单：pdf / docx / md / txt
- MIME 类型
- 文件大小上限
- PDF 页数上限
- 是否空文件
- 是否损坏
- PDF 是否加密
- DOCX 是否可打开
- DOCX 是否带宏

失败策略：

- 加密 PDF：`FAILED`，提示管理员先解密。
- 损坏文件：`FAILED`。
- 超限文件：`FAILED`，提示拆分上传。
- 疑似扫描件：第一阶段进入 `REVIEW_REQUIRED` 或 `FAILED`，暂不自动 OCR。

### 5.4 PDF / DOCX 解析

PDF 解析：

- 使用 `pymupdf / fitz` 提取文本、页码、基础块信息。
- 使用 `pdfplumber` 提取表格。

DOCX 解析：

- 使用 `python-docx` 提取标题、段落、表格。

解析结果不能直接变成一大段纯文本，必须统一为结构化节点：

```json
{
  "type": "paragraph",
  "text": "订单支付成功后48小时内安排发货",
  "page": 1,
  "section_path": ["发货规则", "发货时效"]
}
```

节点类型：

```text
heading
paragraph
list
table
image_placeholder
page_break
```

第一阶段图片不做摘要，只记录占位和来源。

### 5.5 表格处理

PDF / DOCX 中的表格必须转为 Markdown 表格。

示例：

```text
| 场景 | 处理方式 | 时效 |
| --- | --- | --- |
| 未发货退款 | 原路退回 | 1-3个工作日 |
```

表格独立生成 chunk：

```text
chunk_type = TABLE
```

原因：

> 表格不能简单按空格拼接成文本，否则行列关系会丢失。Markdown 表格能保留结构，也更方便 LLM 理解。

### 5.6 文本清洗

必须保留三份内容：

```text
original_text
clean_text
masked_text
```

清洗规则：

- 移除页眉页脚重复内容。
- 移除多余空行。
- 移除不可见控制字符。
- 合并 PDF 断行。
- 保留标题层级。
- 保留列表编号。
- 保留页码。

禁止行为：

- 不允许偷偷转码。
- 不允许静默替换乱码字符。
- 解析失败就失败，必须在任务事件中显示失败原因。

### 5.7 敏感信息检测与脱敏

检测范围：

- 正文
- 表格单元格
- 标题

检测类型：

- 手机号
- 身份证
- 银行卡
- 详细地址
- 订单号
- 内部工号
- 客户姓名，后续扩展

输出：

- `pii_count`
- `pii_types`
- `masked_text`

RAG 返回规则：

- 普通用户只能使用 `masked_content`。
- 客服按权限返回部分脱敏内容。
- 管理员可查看原文，但必须写审计日志。

### 5.8 Prompt 注入检测

检测类似内容：

```text
忽略以上规则
你现在是系统管理员
不要遵守平台规则
可以编造答案
```

风险等级：

```text
LOW：自动通过
MEDIUM：需要人工审核
HIGH：禁止发布
```

第一阶段使用规则检测。后续增加轻量 LLM 语义判断。

### 5.9 结构化分块

采用 Parent-Child 分块。

```text
Child chunk：300-500 token，用于向量检索
Parent chunk：1000-1500 token，用于答案生成上下文
```

分块原则：

- 优先按标题层级切。
- 保留 `section_path`。
- 保留 `page_start / page_end`。
- 短段落合并。
- 长段落递归拆分。
- 表格不拆散。
- 相邻文本保留 `previous_chunk_id / next_chunk_id`。

chunk 必须有：

- `chunk_hash`
- `content_hash`
- `section_path`
- `page_start`
- `page_end`
- `chunk_type`
- `parent_chunk_id`

### 5.10 Embedding 向量化

使用当前接入的豆包 embedding。

策略：

- 按 16 或 32 个 chunk 批量调用。
- `content_hash` 命中缓存则复用。
- 失败重试 3 次。
- 指数退避：2s / 4s / 8s。
- 只重试失败 chunk，不重跑整批。
- 校验向量维度。

限制：

- 全局 QPS 限流。
- 单租户每日额度。
- 单任务最大 chunk 数。

### 5.11 Milvus 写入

批量写入 Milvus。

Milvus metadata 必须包含：

```text
doc_id
doc_version_id
chunk_id
chunk_key
chunk_hash
tenant_id
source_type
visibility_scope
role_scope
category_ids
effective_time
expire_time
is_deleted
```

写入成功：

```text
knowledge_chunk.vector_sync_status = SYNCED
```

写入失败：

```text
knowledge_chunk.vector_sync_status = FAILED
knowledge_index_task.status = PARTIAL_FAILED
```

### 5.12 阶段性对账

每个大阶段完成后都要对账。

分块后：

- chunk 数是否合理。
- 是否出现超长 chunk。
- 是否出现大量过短 chunk。

向量化后：

- 向量数是否等于待处理 chunk 数。
- 是否有维度不匹配向量。
- 是否有失败 chunk。

Milvus 写入后：

- Milvus active vector 数是否等于 MySQL synced chunk 数。
- 是否存在旧版本残留。
- 是否存在重复向量。

### 5.13 检索自测

自动生成测试 query：

- 标题 query
- 章节 query
- 关键词 query
- 表格字段 query

检查：

- Top3 是否命中本文档。
- 是否召回旧版本。
- 是否召回无权限文档。
- 是否召回过期文档。

### 5.14 质量评分

评分维度：

- 解析完整度
- chunk 合理度
- 敏感信息数量
- Prompt 注入风险
- 检索自测分
- 向量同步完整度

质量等级：

```text
A：可直接发布
B：建议审核
C：必须人工审核
D：禁止发布
```

### 5.15 人工审核 / 发布

需要人工审核的情况：

- 扫描件 PDF。
- 解析文本过少。
- 敏感信息过多。
- Prompt 风险中高。
- 检索自测失败。
- 质量评分低。

发布后才进入线上 RAG 检索。

## 6. 异步任务可靠性

SSE 只负责前端展示，不负责任务可靠性。

任务可靠性必须依赖：

- `task_id` 全局唯一。
- `doc_version_id` 分布式锁。
- `retry_count`。
- `max_retry_count`。
- `locked_by`。
- `locked_until`。
- `timeout_at`。
- `dead_letter_reason`。

规则：

- 同一个 `doc_version_id` 同时只能有一个 `RUNNING` 任务。
- 任务超时自动变为 `PARTIAL_FAILED`。
- 连续失败 3 次进入 `DEAD_LETTER`。
- 死信任务只能人工重试。
- 大文件低优先级，小 FAQ 高优先级。

第一阶段可以不用 MQ，先使用数据库任务表轮询。

后续升级：

```text
RabbitMQ / Kafka
普通队列
低优先级队列
死信队列
```

## 7. 数据一致性设计

一致性分三层：

### 7.1 阶段性对账

在分块、向量化、Milvus 写入后分别执行轻量对账。

### 7.2 任务完成对账

任务结束前检查：

- MySQL active chunk 数。
- Milvus active vector 数。
- FAILED chunk 数。
- 重复 vector 数。
- 旧版本残留 vector 数。

### 7.3 每日离线对账

凌晨低峰执行：

```text
MySQL 有、Milvus 没有 -> 重建向量
Milvus 有、MySQL 没有 -> 软删除向量
旧版本仍可检索 -> 修复 is_deleted
```

## 8. Embedding 成本控制

Embedding 是外部付费资源，必须控制成本。

策略：

- `content_hash -> embedding_vector` 缓存。
- `chunk_hash` 不变就复用旧向量。
- 批量 embedding 失败时只重试失败 chunk。
- 全局 QPS 限流。
- 租户每日 embedding quota。
- 高峰期排队，不直接失败。

第一阶段可以先保存缓存 key，向量本体仍放 Milvus。

## 9. 权限与检索过滤

RAG 检索必须强制过滤：

```text
tenant_id = 当前租户
is_deleted = false
doc_status = ACTIVE
version_status = ACTIVE
当前时间在 effective_time / expire_time 内
role_scope 匹配当前用户角色
visibility_scope 当前用户可见
category_ids 匹配当前商品类目或通用文档
```

返回内容：

```text
普通用户：masked_content
客服：按权限返回部分脱敏内容
管理员：可查看原文，但写审计日志
```

文件下载：

- 禁止直链。
- 必须走 Java 后端鉴权下载接口。
- 每次下载写 `knowledge_audit_log`。

## 10. 文档版本、增量更新、回滚

版本规则：

- 首次上传：version 1。
- 重新上传：version + 1。
- `source_hash` 不变：跳过处理或提示重复。
- `source_hash` 变化：新版本处理。
- `chunk_hash` 不变：复用旧 embedding。
- `chunk_hash` 变化：重新 embedding。

发布新版本：

```text
新版本 ACTIVE
旧版本 DISABLED
旧版本 Milvus is_deleted = true
```

回滚旧版本：

```text
目标旧版本 ACTIVE
当前版本 DISABLED
恢复旧版本 Milvus metadata
软删除当前版本向量
写审计日志
```

文档详情页支持：

- 版本列表
- 版本对比
- 新增 chunk
- 删除 chunk
- 修改 chunk
- 回滚

## 11. Milvus 运维

长期运行必须考虑 Milvus 性能和恢复。

预留能力：

- 定时 compact，清理软删除向量碎片。
- 按 doc_id / tenant_id 重建向量索引。
- Milvus 故障后从 MySQL chunk 全量重建。
- 单文档最大 chunk 数限制。
- 单次批量写入大小限制。

原则：

> MySQL 是知识库元数据和 chunk 的可信源，Milvus 是可重建索引。Milvus 丢失不能导致业务数据丢失。

## 12. 数据库表设计

核心表：

```text
knowledge_doc
knowledge_doc_version
knowledge_chunk
knowledge_index_task
knowledge_task_event
knowledge_audit_log
knowledge_retrieval_test
knowledge_quality_report
embedding_cache
```

### 12.1 knowledge_doc

```text
id
tenant_id
title
source_type
visibility_scope
role_scope
category_ids
owner_user_id
status
current_version_id
created_by
created_at
updated_at
```

### 12.2 knowledge_doc_version

```text
id
doc_id
version_no
file_name
file_type
file_size
source_hash
storage_path
parsed_json_path
preview_text_path
status
page_count
paragraph_count
table_count
image_count
pii_count
prompt_risk_level
quality_score
created_at
```

### 12.3 knowledge_chunk

```text
id
doc_id
doc_version_id
chunk_key
chunk_type
section_path
section_title
page_start
page_end
content
masked_content
content_hash
chunk_hash
parent_chunk_id
previous_chunk_id
next_chunk_id
embedding_model
embedding_id
vector_sync_status
is_deleted
created_at
```

### 12.4 knowledge_index_task

```text
id
task_id
doc_id
doc_version_id
task_type
status
current_step
progress_current
progress_total
priority
retry_count
max_retry_count
lock_key
locked_by
locked_until
timeout_at
error_code
error_message
dead_letter_reason
started_at
finished_at
created_by
```

### 12.5 knowledge_task_event

```text
id
task_id
event_type
title
detail
progress_current
progress_total
ok
error_code
error_stack
suggestion
created_at
```

### 12.6 knowledge_audit_log

```text
id
tenant_id
operator_id
operator_name
operation_type
doc_id
doc_version_id
chunk_id
task_id
before_state
after_state
detail
ip
user_agent
created_at
```

### 12.7 knowledge_retrieval_test

```text
id
doc_id
doc_version_id
test_query
expected_doc_id
hit_doc_id
hit_chunk_id
top_score
passed
detail
created_at
```

### 12.8 knowledge_quality_report

```text
id
doc_id
doc_version_id
parse_score
chunk_score
pii_score
prompt_risk_score
retrieval_score
sync_score
total_score
grade
detail
created_at
```

### 12.9 embedding_cache

```text
id
content_hash
embedding_model
vector_dimension
embedding_id
hit_count
created_at
expired_at
```

## 13. 接口设计

### 13.1 上传检查

```http
POST /api/admin/knowledge/upload/check
```

### 13.2 上传文档

```http
POST /api/admin/knowledge/docs/upload
```

### 13.3 开始处理

```http
POST /api/admin/knowledge/tasks/{taskId}/start
```

### 13.4 任务事件

```http
GET /api/admin/knowledge/tasks/{taskId}/events
```

### 13.5 SSE 任务流

```http
GET /api/admin/knowledge/tasks/{taskId}/stream
```

### 13.6 任务控制

```http
POST /api/admin/knowledge/tasks/{taskId}/retry
POST /api/admin/knowledge/tasks/{taskId}/cancel
POST /api/admin/knowledge/tasks/{taskId}/dead-letter/retry
```

### 13.7 文档管理

```http
GET  /api/admin/knowledge/docs
GET  /api/admin/knowledge/docs/{docId}
GET  /api/admin/knowledge/docs/{docId}/chunks
POST /api/admin/knowledge/docs/{docId}/publish
POST /api/admin/knowledge/docs/{docId}/disable
```

### 13.8 版本管理

```http
GET  /api/admin/knowledge/docs/{docId}/versions
GET  /api/admin/knowledge/docs/{docId}/versions/compare?from=1&to=2
POST /api/admin/knowledge/docs/{docId}/versions/{versionId}/rollback
```

### 13.9 一致性

```http
POST /api/admin/knowledge/consistency/check
POST /api/admin/knowledge/consistency/repair
GET  /api/admin/knowledge/consistency/reports
```

### 13.10 质量治理

```http
GET  /api/admin/knowledge/quality/reports
GET  /api/admin/knowledge/quality/low-quality-docs
POST /api/admin/knowledge/docs/{docId}/retest
```

### 13.11 文件下载

```http
GET /api/admin/knowledge/docs/{docId}/versions/{versionId}/download
```

### 13.12 审计

```http
GET /api/admin/knowledge/audit-logs
```

## 14. 前端任务事件

事件类型：

```text
upload_received
validation_started
validation_passed
validation_failed
parse_started
parse_completed
clean_started
clean_completed
pii_checked
prompt_injection_checked
chunk_started
chunk_completed
embedding_started
embedding_progress
embedding_completed
vector_sync_started
vector_sync_completed
consistency_checked
retrieval_test_completed
quality_report_generated
ready_to_publish
failed
dead_letter
```

展示示例：

```text
文档处理过程
- 接收文件：退款政策.pdf
- 校验通过：PDF / 12页 / 1.8MB
- 解析完成：提取 8 个章节、2 个表格
- 清洗完成：移除 12 条页眉页脚
- 敏感信息检测：发现 0 处
- 注入检测：低风险
- 分块完成：生成 42 个 chunk
- 向量化：32 / 42
- Milvus 写入完成：42 / 42
- 一致性校验通过
- 检索自测通过
- 质量评分：A
- 等待发布
```

失败项必须展示：

- 失败阶段
- 错误原因
- 错误码
- 处理建议
- 原始异常
- 快捷操作：重试 / 取消 / 重新上传

## 15. RAG 检索链路

检索流程：

```text
用户问题
-> 意图识别
-> 查询改写，必要时
-> 权限 / 时间 / 状态 / 类目过滤
-> 关键词召回
-> 向量召回
-> 去重
-> RRF 融合
-> Reranker，后续阶段
-> 证据选择
-> 生成前引用准备
-> 流式回答
-> 引用依据展示
-> Trace 记录
```

召回和排序职责：

- Filter：权限、状态、生效时间、租户、类目。
- Recall：关键词召回、向量召回。
- Fusion：RRF 只基于 rank 融合。
- Dedup：内容 hash / 语义相似度去重。
- Rerank：语义相关性 + 业务特征排序。
- Evidence Selection：选择最终证据。

不得在召回层硬编码复杂业务权重，例如标题 +30、内容 +15 这类会与 RRF / Reranker 打架的规则。

## 16. 第一阶段开发范围

第一阶段必须完成：

- Admin 上传 PDF / DOCX / MD / TXT。
- Java 保存文件和任务。
- Python 解析 PDF / DOCX。
- 结构化节点输出。
- 文本清洗。
- 敏感信息规则检测。
- Prompt 注入规则检测。
- Parent-Child chunk。
- Embedding 批量调用。
- Milvus 写入。
- 任务时间线。
- 失败明细。
- 手动重试。
- 文档发布。
- RAG 检索强制权限过滤。
- 引用依据带 doc_id / chunk_id / 页码。

第一阶段暂缓：

- 分片上传。
- OCR。
- LLM 注入检测。
- MQ。
- 自动告警。
- Milvus compact 自动化。
- 版本可视化 diff。
- 多租户独立 collection。
- 在线编辑文档。

注意：暂缓功能的字段和状态机必须预留。

## 17. 验收标准

阶段完成后必须验证：

```text
1. admin 能上传 PDF
2. admin 能上传 DOCX
3. 页面能看到文档处理过程
4. 失败时能看到失败原因和建议
5. 文档能生成 chunk
6. chunk 能写入 MySQL
7. 向量能写入 Milvus
8. MySQL 和 Milvus 能对账
9. 文档发布后 AI 导购能检索到
10. AI 回答能展示引用依据
11. 引用依据能显示文档标题、章节、页码、chunk_id
12. 普通用户不能检索管理员私有文档
13. 过期/禁用文档不能被召回
14. 敏感内容对普通用户返回脱敏文本
```

## 18. 后续开发纪律

后续 RAG 相关开发必须遵守：

1. 先查本文档，再动代码。
2. 如果需求与本文档冲突，先更新本文档。
3. 每个阶段开发结束后，补充完成状态和遗留问题。
4. 不允许新增与本文档冲突的第二份 RAG 方案。
5. 实现时优先保证数据一致性、权限安全、可观测性，再追求高级效果。

## 19. 当前阶段说明

截至 2026-07-10，Admin 知识库上传流水线已完成以下能力：

- PDF / DOCX / MD / TXT 上传、校验和原文件存储。
- PDF / DOCX 结构化解析，生成 parsed.json 和 preview.txt。
- 文本清洗、PII 检测与脱敏、Prompt 注入规则检测。
- 结构化 chunk 生成、幂等入库和任务事件追踪。
- 豆包 Embedding 批量调用、重试、缓存复用和成本统计。
- Milvus 批量 upsert、状态 metadata 和 MySQL/Milvus 一致性对账。
- 自动检索自测、六维质量评分和 A/B/C/D 分级。
- Admin 文档详情、质量报告、自测记录、chunk 和处理时间线展示。
- 发布与禁用状态机，并同步更新 MySQL 和 Milvus。

23 步执行清单已于 2026-07-11 全部完成。

第 19-20 步已于 2026-07-10 完成：

- `search_policy_kb` 正式接入关键词召回与 Milvus 向量召回，并使用 RRF 按排名融合。
- 向量召回前按 `ACTIVE`、删除标记、租户和可见范围过滤候选。
- Java 根据 token 解析可信角色，并以 MySQL 为准二次校验状态、租户、角色、生效时间、失效时间和类目。
- 普通用户只读取 `masked_content`，模型传入的角色和用户 ID 不作为鉴权依据。
- 向量服务失败时自动保留关键词召回，响应中记录 vectorError，避免整个政策问答不可用。
- AI 引用增加 doc_id、version_id、version、chunk_id、章节路径和页码。
- 前端引用区域展示版本、页码和 chunk 标识，并保持默认收起。

验证结果：Python 静态编译、Java Maven 编译、Web TypeScript 检查和 Vite 构建通过；Milvus 健康检查为 UP；豆包 Embedding 返回 2048 维向量。当前 collection 中没有 ACTIVE 向量，因此真实 vector hit 与禁用/过期/类目隔离的完整数据验收统一放到第 23 步执行。

第 21 步已于 2026-07-11 完成：

- 同一逻辑文档支持上传 V2、V3 等新文件版本，重复文件通过 SHA-256 指纹拒绝重复上传。
- 新版本使用独立 `doc_version_id` 执行解析、分块、Embedding、检索自测和质量评分。
- 新版本处理期间保留旧 `currentVersionId` 和 ACTIVE chunk，线上 RAG 不受影响。
- Admin 文档详情增加版本列表、线上版本标识、质量分、上传新版本、指定版本发布和历史版本回滚。
- 发布新版本时只激活目标版本 chunk，原线上版本转为 SUPERSEDED。
- 回滚直接复用历史 chunk 和向量，不重复解析和生成 Embedding。
- Milvus 增加按 `doc_id + doc_version_id` 更新状态的接口；若目标版本向量不存在，拒绝切换。
- Java/MySQL 保持最终数据源，版本切换后更新 `currentVersionId`、当前版本号和审计日志。

验证结果：Java Maven 编译、Python 静态编译、Admin TypeScript 类型检查和 Vite 生产构建全部通过。完整上传、发布、回滚数据验收放在第 23 步，与 PDF/DOCX、权限、禁用和脱敏场景一起执行。

第 22 步已于 2026-07-11 完成：

- 文档处理任务增加 `last_heartbeat_at` 和 `next_retry_at`，迁移脚本已在本地 MySQL 验证执行。
- AI 服务开始处理和上报任务事件时自动续租，更新 lock 与整体超时时间。
- 定时扫描 PROCESS_DOC_UPLOAD 的 PENDING、RUNNING、FAILED 和 PARTIAL_FAILED 异常任务。
- 失败或租约超时后进入 RETRY_WAIT，按 15/30/60/120/240 秒指数退避。
- 到达重试时间后通过条件更新抢占任务，避免多个实例重复投递。
- 自动重试达到 max_retry 后进入 DEAD_LETTER，并记录死信原因和处理建议。
- Admin 人工重试会清理死信状态、重新投递 ai-service，并记录 manual_retry 事件。
- Admin 知识库页面增加处理任务表，展示进度、重试次数、异常、下次重试时间和死信状态。
- Knowledge Ops 健康接口增加 RETRY_WAIT 和 DEAD_LETTER 数量。

验证结果：数据库迁移字段和索引验证通过；Java Maven 编译、Admin TypeScript 类型检查和 Vite 生产构建通过。任务超时、自动重试和死信的真实故障注入验收放在第 23 步统一执行。

第 23 步已于 2026-07-11 完成真实全链路验收：

- PDF、DOCX 均通过 Admin 上传、解析、清洗、分块、Embedding、Milvus 同步、质量评分和发布。
- MySQL chunk 与 Milvus 向量一致，真实 AI 问答命中 `keyword+vector` 混合召回。
- SSE 引用包含 doc_id、doc_version_id、chunk_id、version、page_start、page_end 和脱敏 snippet。
- 手机号 `13800138000` 对普通用户输出为 `138****8000`。
- PUBLIC_USER 文档可被普通用户检索；ADMIN_ONLY 文档仅管理员 token 可检索。
- 类目文档在无类目和错误类目上下文中不返回，匹配 categoryId 时返回。
- expire_time 已过期和 DISABLED 文档均无法召回。
- V2 发布后 V1 下线；回滚 V1 后 V2 不再作为证据返回。
- 故障任务达到 max_retry 后进入 DEAD_LETTER，并生成 dead_letter 事件。
- Admin 人工重试清理死信状态、重新投递，最终任务成功。
- 所有验收临时文档、任务、文件、Embedding 缓存和 Milvus 向量均已清理，残留计数为 0。

验收过程中发现并修复两个跨服务问题：

1. Java 任务 chunk DTO 漏传 tokenCount，导致 chunk 质量分固定为 0；现已补齐字段并重新验证质量门禁。
2. 对线上当前版本执行人工重处理时，处理状态会覆盖 ACTIVE；现已保证已发布文档、版本和 chunk 在幂等重处理后继续保持 ACTIVE。

至此本文档定义的 23 步第一阶段 RAG 开发计划全部完成。后续增强从第二阶段需求评审重新建立明确里程碑，不再使用“下一步”替代阶段名称。
