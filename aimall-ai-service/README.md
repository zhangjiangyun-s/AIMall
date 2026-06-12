# aimall-ai-service

AI 服务后端，基于 FastAPI 构建。

> 当前版本为 mock 骨架，不接真实大模型和向量库。

## 安装

```bash
pip install -r requirements.txt
```

## 启动

```bash
uvicorn main:app --reload --port 8000
```

## 运行端口

8000

## 已实现接口

| 方法   | 路径                        | 说明                                    |
| ------ | --------------------------- | --------------------------------------- |
| GET    | `/health`                   | 健康检查                                |
| GET    | `/health/integration`       | 联调自检（返回 AI 服务 mock 能力状态）  |
| POST   | `/ai/chat`                  | AI 聊天 mock（按意图返回固定业务回复 + 结构化推荐商品） |
| POST   | `/ai/knowledge/rebuild`     | 知识库重建                              |

## 项目结构

```
main.py
app/
├── api/
│   ├── chat_api.py
│   ├── health_api.py
│   └── knowledge_api.py
├── config/
│   └── settings.py
├── router/
│   └── intent_router.py
├── schemas/
│   └── chat_schema.py
└── tools/
    └── java_client.py
```

## 说明

- 本轮是 mock AI 服务，不连接真实大模型和向量库
- 意图识别基于固定规则，不调用大模型
- `JavaClient` 只包含占位方法，不发真实 HTTP 请求
