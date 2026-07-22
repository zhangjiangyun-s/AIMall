# AIMall 管理后台

基于 Vue 3 + Vite + TypeScript + Element Plus 的管理后台项目。

## 安装

```bash
npm install
```

## 启动

```bash
npm run dev
```

运行端口：**5174**

## 已实现页面

| 路径 | 页面 | 说明 |
|------|------|------|
| `/login` | 管理员登录 | 用户名密码登录，成功后跳转工作台 |
| `/` | 工作台 | 后端健康状态 + 联调模块状态 + 3 个统计卡片 |
| `/products` | 商品管理 | 商品列表展示、新增、编辑、删除（对接后端，失败回退 mock） |
| `/knowledge-docs` | 知识库文档 | 文档列表展示、新增、编辑、删除、重建知识库（对接后端，失败回退 mock） |
| `/orders` | 订单管理 | 订单列表展示、修改状态（对接后端，失败回退 mock） |

## API 对接说明

所有 CRUD 操作优先请求后端接口，失败时自动回退本地 mock 数据：

| 方法 | 接口 | 说明 |
|------|------|------|
| GET | `/api/admin/products` | 商品列表 |
| POST | `/api/admin/products` | 新增商品 |
| PUT | `/api/admin/products/{id}` | 更新商品 |
| DELETE | `/api/admin/products/{id}` | 删除商品 |
| GET | `/api/admin/knowledge-docs` | 文档列表 |
| POST | `/api/admin/knowledge-docs` | 新增文档 |
| PUT | `/api/admin/knowledge-docs/{id}` | 更新文档 |
| DELETE | `/api/admin/knowledge-docs/{id}` | 删除文档 |
| POST | `/api/admin/knowledge-docs/rebuild` | 重建知识库 |
| GET | `/api/admin/dashboard` | 工作台统计 |
| GET | `/api/health/integration` | 健康检查 + 模块状态 |
| GET | `/api/admin/orders` | 订单列表 |
| PUT | `/api/admin/orders/{id}/status` | 修改订单状态 |
| POST | `/api/admin/login` | 管理员登录 |

## 管理员登录

- 登录页路径 `/login`
- 登录成功后 `admin_token` 和 `admin_info` 存入 localStorage
- 所有请求自动携带 token（通过请求拦截器）
- 右上角显示当前管理员用户名
- 点击"退出"清除 token 并返回登录页

## 说明

- API 基础地址为 `http://localhost:8080`
- 所有接口调后端失败时自动回退本地 mock 数据
- 不接 aimall-ai-service
