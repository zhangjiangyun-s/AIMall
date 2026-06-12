# aimall-web

Vue 3 用户端商城前端骨架。

## 技术栈

- Vue 3 + TypeScript
- Vite
- Pinia
- Vue Router
- Axios
- 普通 CSS

## 安装

```bash
npm install
```

## 启动

```bash
npm run dev
```

运行端口：**5173**

## 已实现页面

| 路径 | 说明 |
|------|------|
| `/` | 重定向到 `/products` |
| `/products` | 商品列表页 |
| `/products/:id` | 商品详情页 |
| `/orders` | 订单列表页 |
| `/orders/:id` | 订单详情页 |

## 数据说明

页面启动时优先请求后端 `http://localhost:8080`，成功时使用后端数据，失败时自动回退到本地 mock 数据。

### API 统一返回格式

```json
{"code": 0, "message": "success", "data": {}}
```

### 后端 API 地址

- `GET /api/products` — 商品列表
- `GET /api/products/:id` — 商品详情
- `GET /api/orders` — 订单列表
- `GET /api/orders/:id` — 订单详情
- `GET /api/health/integration` — 联调健康检查（页面顶部状态条）
