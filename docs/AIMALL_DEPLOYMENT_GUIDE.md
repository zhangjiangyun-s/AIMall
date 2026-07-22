# AIMall 1.0.0 部署手册

本文说明 AIMall 的本地开发与完整 Docker 部署方式。完整 Docker 栈是当前推荐的可复现运行方式。

## 1. 部署模式

| 模式 | 用途 | 入口 |
| --- | --- | --- |
| 本地开发 | 修改代码、热更新和调试 | `start.bat` |
| Docker 全栈 | 联调、演示和完整依赖验收 | `docker-full-start.bat` |
| Tunnel 扩展 | 支付宝公网异步回调 | `docker-full-start.bat -Tunnel` |

两种运行模式使用不同端口。Docker Compose 项目名为 `aimall-docker`，不会主动删除本地数据库或历史 volume。

## 2. 前置条件

本地开发需要 JDK 17、Maven 3.9+、Node.js 22+、Python 3.10+，以及 MySQL、Redis 和 Milvus。

Docker 模式需要 Docker Desktop、Compose v2、充足的磁盘空间，并建议为 Docker 分配至少 8 GB 内存。

## 3. 配置管理

本地开发从 `.env.example` 创建 `.env`：

```powershell
Copy-Item .env.example .env
```

Docker 部署从 `.env.docker.example` 创建 `.env.docker.local`：

```powershell
Copy-Item .env.docker.example .env.docker.local
```

所有示例密码和密钥都必须替换。不要提交以下内容：

- `.env`、`.env.docker.local`
- `secrets/`、`.docker-secrets/`
- 支付宝私钥、API Key、HMAC 密钥和 Cloudflare token
- 数据库备份、验收响应和运行日志

## 4. Docker 部署

准备观测 token 文件，并确保内容与环境变量一致：

```powershell
New-Item -ItemType Directory -Force .docker-secrets
Set-Content -NoNewline .docker-secrets/observability-token.txt "你的观测Token"
```

没有支付宝沙箱配置时，在 `.env.docker.local` 中设置：

```dotenv
ALIPAY_ENABLED=false
```

启动服务：

```powershell
.\docker-full-start.bat
```

检查状态：

```powershell
docker compose --env-file .env.docker.local -f docker-compose.full.yml ps
docker compose --env-file .env.docker.local -f docker-compose.full.yml config --quiet
```

检查核心端点：

```text
http://localhost:15173
http://localhost:15174
http://localhost:18080/api/health
http://localhost:18000/health
http://localhost:19090/targets
http://localhost:13000/api/health
```

Prometheus 中的 `aimall-server` 与 `aimall-ai-service` target 应为 `UP`。

## 5. 本地开发

确认 `.env` 已配置且 MySQL、Redis、Milvus 可访问，然后执行：

```powershell
.\start.bat
```

默认地址：

- 用户商城：http://localhost:5173
- 管理后台：http://localhost:5174
- Java API：http://localhost:8080
- AI 服务：http://localhost:8000

仅在本地开发或测试环境需要彻底重建数据库时，可以执行：

```powershell
.\scripts\dev\reset-local-db.bat -ConfirmReset
```

该命令具有破坏性，会拒绝生产环境，并且不会使用默认数据库密码。

## 6. 支付部署

支付宝沙箱至少需要：

- APPID 和商户 PID
- RSA2 应用私钥和支付宝公钥
- 沙箱网关地址
- 前端同步返回地址
- 可公网访问的 HTTPS 异步通知地址

Cloudflare Named Tunnel 需要 `CLOUDFLARE_TUNNEL_TOKEN`。启用后还应验证支付下单、回调验签、主动查单、超时关单、退款、对账和 Outbox 恢复。

## 7. 数据库升级

数据库结构由 Flyway 管理。部署前执行备份，部署时确认 `validate` 和 `migrate` 成功。禁止在未记录迁移版本的情况下直接修改生产表结构。

迁移失败时应停止应用发布，保留现场并根据 Flyway 历史和数据库实际结构决定修复方式，不应直接删除迁移历史记录。

## 8. 停止、重启和回滚

停止并保留数据：

```powershell
.\docker-full-stop.bat
```

直接重启：

```powershell
docker compose --env-file .env.docker.local -f docker-compose.full.yml restart
```

不要使用 `down -v`，除非确认需要永久删除 Docker 数据库、缓存、对象、向量、上传文件和监控数据。

应用回滚应使用上一版本镜像或构建物。数据库采用向前兼容的 Expand/Contract 方式，不能把应用回滚到不兼容的 schema。支付、退款和 Outbox 在回滚期间仍必须保持幂等。

## 9. 上线验收

正式部署至少应确认：

1. Flyway 迁移成功且数据库备份可恢复。
2. Java、AI、商城和后台健康正常。
3. MySQL、Redis、Milvus、MinIO 与邮件服务连接正常。
4. Prometheus targets 为 `UP`，Grafana 数据源可用，Loki 可以查询日志。
5. 管理员初始化密码已修改，模拟支付和 API 文档已关闭。
6. 支付回调、查单、关单、退款、对账和异常恢复通过真实环境验证。
7. 外部 LLM、Embedding、Redis 和 Milvus 故障时具备明确降级与恢复行为。

更多 Docker 细节参见 [AIMALL_DOCKER_FULL_STACK.md](AIMALL_DOCKER_FULL_STACK.md)。
