# AIMall 阶段 6 验收与恢复 Runbook

## 1. 验收边界

阶段 6 只有同时取得以下证据才可标记为全量完成：

1. Redis 多实例状态共享和执行 fencing 使用真实 Redis 通过。
2. Milvus 租户、角色、生效时间隔离使用真实 Milvus 通过。
3. 当前业务知识版本能够重新生成任务、完成处理，并逐 chunk 验证物理向量存在。
4. `DOC_ONLY`、`HYBRID`、`VECTOR` 各执行至少 3 次独立评估，以每项指标最差值判定门禁。
5. Redis、Milvus 和 AI 进程故障演练有时间戳、命令、原始输出、恢复时间及数据核对证据。
6. 生产或等价预发布环境完成部署、回滚和 owner 签字。

单元测试、Mock、只验证端口可达或只运行一次基准，均不能替代上述证据。

## 2. 前置条件

- Java 服务由本机开发进程启动，禁止使用历史 Docker `aimall-server` 容器。
- Docker 只运行 MySQL、Redis、Milvus、etcd、MinIO 等支撑服务。
- AI 服务必须配置与 Java 相同的 `AIMALL_INTERNAL_API_SECRET`。
- 真实验收环境设置 `STATE_BACKEND=redis`，Milvus collection 和 embedding 配置必须与待验收环境一致。
- 运行前备份 MySQL，并记录当前 Milvus collection 名称和实体数量。

不得将密钥、数据库密码、用户 token 或原始业务文档写入验收报告。

## 3. 自动化回归

```powershell
Set-Location aimall-ai-service
.\.venv\Scripts\python.exe -m pytest -q

$env:RUN_REAL_REDIS_TESTS='1'
.\.venv\Scripts\python.exe -m pytest -q tests\test_stage6_real_redis.py

$env:RUN_REAL_MILVUS_TESTS='1'
.\.venv\Scripts\python.exe -m pytest -q tests\test_stage6_real_milvus.py
```

真实测试运行后应清除两个开关，避免影响普通回归：

```powershell
Remove-Item Env:RUN_REAL_REDIS_TESTS -ErrorAction SilentlyContinue
Remove-Item Env:RUN_REAL_MILVUS_TESTS -ErrorAction SilentlyContinue
Set-Location ..
```

## 4. 业务知识 Milvus 重建

该操作会为每个未删除文档的当前版本创建或复用处理任务，必须在备份后显式传入 `--execute`：

```powershell
.\aimall-ai-service\.venv\Scripts\python.exe tools\stage6_milvus_rebuild_acceptance.py --execute
```

验收工具执行以下检查：

- 重建接口必须返回每个版本对应的 `taskId`。
- 所有任务必须进入 `SUCCESS`，失败、死信或超时立即阻断。
- 逐任务读取 chunk，逐个检查 `embeddingId` 对应物理向量是否存在。
- `REVIEW_REQUIRED` chunk 或缺失向量均导致报告 `passed=false`。
- 报告默认写入 `.acceptance/stage6/milvus-business-rebuild.json`。

若当前没有业务知识文档，不能将空结果当作重建通过。仅在验证空库行为时才允许使用 `--allow-empty`。

## 5. 三模式基准

每种模式需以相同数据集、模型版本和知识 publication 运行 3 次。每次运行必须使用独立输出文件。服务启动时分别设置：

```text
RAG_RETRIEVAL_MODE=DOC_ONLY
RAG_RETRIEVAL_MODE=HYBRID
RAG_RETRIEVAL_MODE=VECTOR
```

每次先运行评估，再生成 RAG scored 文件：

```powershell
python tools\run_agent_evaluation.py --output .acceptance\stage6\DOC_ONLY-1.json
python tools\score_rag_evaluation.py .acceptance\stage6\DOC_ONLY-1.json
```

三种模式共 9 份 scored 文件完成后汇总：

```powershell
python tools\stage6_rag_benchmark.py `
  --run DOC_ONLY=.acceptance\stage6\DOC_ONLY-1.rag-scored.json `
  --run DOC_ONLY=.acceptance\stage6\DOC_ONLY-2.rag-scored.json `
  --run DOC_ONLY=.acceptance\stage6\DOC_ONLY-3.rag-scored.json `
  --run HYBRID=.acceptance\stage6\HYBRID-1.rag-scored.json `
  --run HYBRID=.acceptance\stage6\HYBRID-2.rag-scored.json `
  --run HYBRID=.acceptance\stage6\HYBRID-3.rag-scored.json `
  --run VECTOR=.acceptance\stage6\VECTOR-1.rag-scored.json `
  --run VECTOR=.acceptance\stage6\VECTOR-2.rag-scored.json `
  --run VECTOR=.acceptance\stage6\VECTOR-3.rag-scored.json `
  --output .acceptance\stage6\rag-all-mode-gate.json
```

汇总器强制每种模式至少 3 次，并使用 Recall@K、MRR、引用准确率和引用忠实度的最差运行值执行门禁。

## 6. 故障与恢复演练

每个演练必须记录开始时间、故障注入时间、告警出现时间、恢复时间、RTO、数据核对和执行人。

### 6.1 Redis 中断

1. 创建一个需要确认的 Pending Action，记录 action ID。
2. 暂停 Redis 支撑容器或阻断 AI 到 Redis 的网络。
3. 验证写操作返回 `503/AI_STATE_UNAVAILABLE`，不得降级为无状态执行。
4. 恢复 Redis，确认原 Action 状态仍可读取。
5. 两个 AI 实例同时确认同一 Action，确认只有当前 fencing token 能提交终态。

### 6.2 Milvus 中断

1. 记录当前已发布版本和可检索基准问题。
2. 暂停 Milvus，验证 RAG 明确降级或失败，不返回伪造引用。
3. 恢复 Milvus并执行第 4 节业务重建工具。
4. 重放基准问题，核对引用的 doc/version/chunk 与 MySQL 当前发布版本一致。

### 6.3 AI 进程崩溃

1. 在知识任务 embedding 或向量写入期间终止 AI 进程。
2. 等待租约过期并由新 attempt 接管。
3. 验证旧 attempt 回写被拒绝，旧 attempt 向量被补偿清理。
4. 验证任务时间线没有失效 attempt 的伪失败事件。

## 7. 回滚

- AI 模式回滚：通过受审计配置切回 `SANDBOX` 或只读，禁止切到无状态写操作模式。
- RAG 模式回滚：恢复上一配置版本并重启 AI；记录 capability hash 和变更单。
- 知识版本回滚：使用后台版本回滚接口，不直接修改 Milvus。
- Milvus 恢复：以 MySQL 当前版本和 chunk 为事实源重新生成任务并核对物理向量。
- Redis 恢复：恢复 AOF/RDB 后先核对 Pending Action 和租约，再开放写操作。
- Schema 问题采用 Flyway 前滚修复或备份恢复，不直接修改 Flyway 历史记录。

## 8. 完成判定

任何一项缺少原始报告、真实实例证据或恢复记录，阶段 6 结论必须是“未全量完成”。Runbook 本身只证明流程已定义，不能替代演练结果。
