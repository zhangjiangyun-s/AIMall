# aimall-server

AI Mall 后端 — Spring Boot 3 业务系统（已接入 MySQL 数据库）。

## 启动前准备

需要先启动 MySQL，并手动创建数据库：

```bash
# 确保 MySQL 运行在 localhost:3306
# 用户名/密码：root/123456
# 手动创建数据库（schema.sql 不再包含 CREATE DATABASE）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS aimall DEFAULT CHARACTER SET utf8mb4;"
```

建表和数据初始化由 `schema.sql`（DDL）+ `data.sql`（DML）自动执行（`spring.sql.init.mode=never` 时为手动执行）。

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
- MyBatis-Plus (数据库 ORM)
- MySQL
- Knife4j (API 文档)
- Sa-Token（认证框架，已开启路由拦截）

## 已实现的接口

### 公开接口（无需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 |
| GET | `/api/health/integration` | 联调自检 |
| GET | `/api/products` | 商品列表 |
| GET | `/api/products/{id}` | 商品详情（含兜底mock） |
| POST | `/api/user/register` | 用户注册 |
| POST | `/api/user/login` | 用户登录 |

### 需登录接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/user/info` | 当前用户信息 |
| GET | `/api/orders` | 订单列表 |
| GET | `/api/orders/{id}` | 订单详情（含兜底mock） |
| POST | `/api/orders/create` | 创建订单 |
| POST | `/api/cart/add` | 添加购物车 |
| GET | `/api/cart/list` | 购物车列表 |
| POST | `/api/cart/update` | 更新购物车数量 |
| POST | `/api/cart/delete` | 删除购物车商品 |
| POST | `/api/pay/simulate` | 模拟支付 |

### 管理端接口（暂未限制登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/products` | 管理端商品列表 |
| POST | `/api/admin/products` | 新增商品 |
| PUT | `/api/admin/products/{id}` | 编辑商品 |
| DELETE | `/api/admin/products/{id}` | 删除商品 |
| GET | `/api/admin/knowledge-docs` | 知识库文档列表 |
| POST | `/api/admin/knowledge-docs` | 新增文档 |
| PUT | `/api/admin/knowledge-docs/{id}` | 编辑文档 |
| DELETE | `/api/admin/knowledge-docs/{id}` | 删除文档 |
| POST | `/api/admin/knowledge-docs/rebuild` | 重建知识库 |
| GET | `/api/admin/orders` | 订单列表 |
| PUT | `/api/admin/orders/{id}/status` | 修改订单状态 |
| GET | `/api/admin/dashboard` | 管理端仪表盘 |

### 内部接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/chat` | AI 对话网关 |
| GET | `/internal/ai/products/{productId}` | AI 内部商品详情 |

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
        │               │   └── AdminController.java
        │               ├── ai
        │               │   ├── AiGatewayController.java
        │               │   └── InternalAiController.java
        │               ├── cart
        │               │   └── CartController.java
        │               ├── common
        │               │   └── ApiResponse.java
        │               ├── config
        │               │   ├── CorsConfig.java
        │               │   └── SaTokenConfigure.java
        │               ├── entity
        │               │   ├── KnowledgeDoc.java
        │               │   ├── OmsCartItem.java
        │               │   ├── OmsOrder.java
        │               │   ├── OmsOrderItem.java
        │               │   ├── PmsProduct.java
        │               │   ├── PmsProductCategory.java
        │               │   ├── UmsAdmin.java
        │               │   └── UmsMember.java
        │               ├── health
        │               │   └── HealthController.java
        │               ├── mapper
        │               │   ├── KnowledgeDocMapper.java
        │               │   ├── OmsCartItemMapper.java
        │               │   ├── OmsOrderItemMapper.java
        │               │   ├── OmsOrderMapper.java
        │               │   ├── PmsProductCategoryMapper.java
        │               │   ├── PmsProductMapper.java
        │               │   ├── UmsAdminMapper.java
        │               │   └── UmsMemberMapper.java
        │               ├── order
        │               │   └── OrderController.java
        │               ├── payment
        │               │   └── PayController.java
        │               ├── product
        │               │   └── ProductController.java
        │               ├── service
        │               │   ├── AdminService.java
        │               │   ├── CartService.java
        │               │   ├── KnowledgeDocService.java
        │               │   ├── OrderService.java
        │               │   ├── PayService.java
        │               │   ├── ProductService.java
        │               │   ├── UserService.java
        │               │   └── impl
        │               │       ├── AdminServiceImpl.java
        │               │       ├── CartServiceImpl.java
        │               │       ├── KnowledgeDocServiceImpl.java
        │               │       ├── OrderServiceImpl.java
        │               │       ├── PayServiceImpl.java
        │               │       ├── ProductServiceImpl.java
        │               │       └── UserServiceImpl.java
        │               └── user
        │                   └── UserController.java
        └── resources
            ├── application.yml
            └── schema.sql
```

## 注意事项

- 已接入 MySQL 数据库，启动前需确保 MySQL 可用
- Sa-Token 已开启路由拦截，公开路径见 SaTokenConfigure.java
- 商品详情和订单详情的未知 id 仍然返回兜底 mock
- CORS 已配置，允许 `http://localhost:5173` 和 `http://localhost:5174`
- `/internal/ai/*` 为内部接口，未在 Knife4j/Swagger 文档中展示
