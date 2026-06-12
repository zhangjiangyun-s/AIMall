# aimall-server

AI Mall 后端 — Spring Boot 3 单体骨架（MVP 阶段，Mock 数据）。

## 启动命令

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/aimall-server-0.0.1-SNAPSHOT.jar

# 或直接用 Maven 启动
mvn spring-boot:run
```

## 运行端口

**8080**

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Maven
- Knife4j (API 文档)
- Sa-Token（已集成，本轮所有接口公开）

## 已实现的接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/health/integration` | 联调自检 |
| GET | `/api/products` | 商品列表（3 个固定商品） |
| GET | `/api/products/{id}` | 商品详情 |
| GET | `/api/orders` | 订单列表（1 个固定订单） |
| GET | `/api/orders/{id}` | 订单详情 |
| POST | `/api/ai/chat` | AI 对话网关 |
| GET | `/internal/ai/products/{productId}` | AI 内部商品详情 |
| GET | `/api/admin/products` | 管理端商品列表 |
| GET | `/api/admin/knowledge-docs` | 知识库文档列表 |
| POST | `/api/admin/knowledge-docs/rebuild` | 触发知识库重建 |
| GET | `/api/admin/dashboard` | 管理端仪表盘 |

## 统一返回结构

所有接口统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

## API 文档

启动后访问：http://localhost:8080/doc.html

## 测试方式

```bash
# 健康检查
curl http://localhost:8080/api/health

# 商品列表
curl http://localhost:8080/api/products

# 商品详情
curl http://localhost:8080/api/products/1001

# 订单列表
curl http://localhost:8080/api/orders

# AI 聊天
curl -X POST http://localhost:8080/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"这个适合学生吗？","sessionId":"ai_session_001","pageContext":{"pageType":"PRODUCT_DETAIL","productId":1001}}'

# AI 内部商品详情
curl http://localhost:8080/internal/ai/products/1001
```

## 项目结构

```
aimall-server
├── pom.xml
├── README.md
└── src
    └── main
        ├── java
        │   └── com
        │       └── aimall
        │           └── server
        │               ├── AimallServerApplication.java
        │               ├── admin
        │               │   └── AdminMockController.java
        │               ├── common
        │               │   └── ApiResponse.java
        │               ├── config
        │               │   └── CorsConfig.java
        │               ├── health
        │               │   └── HealthController.java
        │               ├── product
        │               │   └── ProductController.java
        │               ├── order
        │               │   └── OrderController.java
        │               └── ai
        │                   ├── AiGatewayController.java
        │                   └── InternalAiController.java
        └── resources
            └── application.yml
```

## 注意事项（本轮范围）

- **本轮所有接口均为 Mock 数据，未连接真实数据库。**
- **没有用户、购物车、支付、后台管理业务。**
- Sa-Token 已引入依赖和基础配置，但未开启路由拦截
- `/internal/ai/*` 为内部接口，未在 Knife4j/Swagger 文档中展示
- CORS 已配置，允许 `http://localhost:5173`（用户端）和 `http://localhost:5174`（管理端）跨域请求
