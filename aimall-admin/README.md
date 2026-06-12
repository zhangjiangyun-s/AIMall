# AIMall 管理后台

基于 Vue 3 + Vite + TypeScript + Element Plus 的管理后台骨架项目。

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
| `/` | 工作台 | 后端健康状态 + 3 个统计卡片（商品数量、规则文档、待处理订单） |
| `/products` | 商品管理 | 商品列表展示、新增、编辑、删除（mock 数据） |
| `/knowledge-docs` | 知识库文档 | 文档列表展示、新增、编辑、删除、重建知识库（mock 数据） |

## 说明

- **查询接口**：优先请求后端 API，失败时自动回退为本地 mock 数据
  - `GET /api/admin/products` → 回退 mock 商品
  - `GET /api/admin/knowledge-docs` → 回退 mock 文档
  - `GET /api/admin/dashboard` → 回退 mock 统计
  - `POST /api/admin/knowledge-docs/rebuild` → 回退 mock 提示
  - `GET /api/health/integration` → 显示 UP/OFFLINE 及模块状态
  - `GET /api/health`（旧，已不再使用）
- **新增、编辑、删除**：仅操作前端内存数组，不调用后端接口
- API 基础地址为 `http://localhost:8080`
