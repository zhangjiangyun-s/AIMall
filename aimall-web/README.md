# AIMall Web

Vue 3 商城前台，全面对接 aimall-server 后端。

## 技术栈

- Vue 3 + TypeScript
- Vite
- Vue Router
- Axios
- 普通 CSS（无 UI 组件库）

## 安装

```bash
npm install
```

## 启动

```bash
npm run dev
```

运行端口：**5173**

## 环境变量

项目根目录创建 `.env` 文件：

```env
VITE_API_BASE_URL=http://localhost:8080
```

默认值：`http://localhost:8080`，Docker 部署时可通过该变量灵活切换。

## 页面路由

| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | HomeView | 首页，展示搜索栏、分类导航、推荐/新品/热门商品 |
| `/products` | ProductListView | 商品列表，支持 keyword 搜索和 categoryId 筛选 |
| `/products/:id` | ProductDetailView | 商品详情，支持 SKU 选择 |
| `/cart` | CartView | 购物车 |
| `/checkout` | CheckoutView | 结算下单页 |
| `/orders` | OrderListView | 订单列表，支持状态筛选 |
| `/orders/:id` | OrderDetailView | 订单详情 |
| `/login` | LoginView | 登录 |
| `/register` | RegisterView | 注册 |

## 接口对接

所有页面使用真实后端接口，无本地 mock 数据兜底。

### API 统一返回格式

```json
{"code": 0, "message": "success", "data": {}}
```

### 后端 API 列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/home/content` | 首页内容（分类、推荐、新品、热门） |
| GET | `/api/home/categories` | 首页分类列表 |
| GET | `/api/products` | 商品列表（支持 categoryId、keyword 参数） |
| GET | `/api/products/:id` | 商品详情（含 SKU） |
| GET | `/api/products/recommend` | 推荐商品 |
| GET | `/api/products/new` | 新品上架 |
| GET | `/api/products/hot` | 热门商品 |
| GET | `/api/products/categories` | 商品分类 |
| POST | `/api/cart/add` | 添加购物车（支持 productSkuId） |
| GET | `/api/cart/list` | 购物车列表 |
| POST | `/api/cart/update` | 更新购物车数量 |
| POST | `/api/cart/delete` | 删除购物车项 |
| GET | `/api/orders` | 订单列表 |
| GET | `/api/orders/:id` | 订单详情 |
| POST | `/api/orders/create` | 创建订单 |
| POST | `/api/user/register` | 用户注册 |
| POST | `/api/user/login` | 用户登录 |
| GET | `/api/user/info` | 获取用户信息 |
| POST | `/api/ai/chat` | AI 购物助手对话 |
| GET | `/api/health/integration` | 联调健康检查 |

### Token 管理

登录成功后 token 存入 `localStorage`，所有请求通过 Axios 请求拦截器自动携带 `token` header。当后端返回含 "token" 或 "登录" 的错误消息时，自动清除 token 并跳转到 `/login`。

## 目录结构

```
src/
├── api/           接口层
│   ├── http.ts        Axios 实例（baseURL 来自环境变量）
│   ├── homeApi.ts     首页内容
│   ├── productApi.ts  商品相关（列表、详情、分类、SKU）
│   ├── cartApi.ts     购物车
│   ├── orderApi.ts    订单
│   ├── userApi.ts     用户认证
│   ├── aiApi.ts       AI 助手
│   └── healthApi.ts   健康检查
├── components/
│   ├── layout/        布局组件（AppHeader、AppFooter）
│   ├── home/          首页组件（HomeSection）
│   ├── product/       商品组件（ProductCard、SkuSelector）
│   └── ai/            AI 组件（AiAssistantDrawer）
├── views/           页面视图
│   ├── HomeView.vue        首页
│   ├── ProductListView.vue 商品列表
│   ├── ProductDetailView.vue 商品详情
│   ├── CartView.vue        购物车
│   ├── CheckoutView.vue    结算
│   ├── OrderListView.vue   订单列表
│   ├── OrderDetailView.vue 订单详情
│   ├── LoginView.vue       登录
│   └── RegisterView.vue    注册
└── styles/          全局样式
    └── main.css
```
