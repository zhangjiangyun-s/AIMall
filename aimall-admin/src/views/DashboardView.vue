<template>
  <div class="dashboard">
    <h2>工作台</h2>
    <el-card shadow="never" class="health-card">
      <div class="health-info">
        <span>后端状态：<el-tag :type="healthStatus === 'UP' ? 'success' : 'danger'" size="small">{{ healthStatus }}</el-tag></span>
        <span>服务名称：<strong>{{ serviceName }}</strong></span>
      </div>
    </el-card>

    <el-card shadow="never" class="modules-card">
      <template #header>
        <span>联调模块状态</span>
      </template>
      <el-row :gutter="16">
        <el-col :span="8" v-for="mod in moduleList" :key="mod.key" class="module-item">
          <span class="module-label">{{ mod.label }}：</span>
          <el-tag :type="mod.value ? 'success' : 'info'" size="small">
            {{ mod.isDb ? (mod.value ? '已接入' : '未接入') : (mod.value ? '已启用' : '未启用') }}
          </el-tag>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="20" class="stat-cards">
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.productCount }}</div>
          <div class="stat-label">商品数量</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.docCount }}</div>
          <div class="stat-label">知识文档</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value">{{ stats.pendingOrderCount }}</div>
          <div class="stat-label">待处理订单</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElCard, ElCol, ElMessage, ElRow } from 'element-plus'
import http from '../api/http'
import type { ApiResponse } from '../api/productAdminApi'

interface DashboardStats {
  productCount: number
  docCount: number
  pendingOrderCount: number
}

interface ModuleMap {
  product: boolean
  order: boolean
  admin: boolean
  aiGateway: boolean
  database: boolean
}

interface IntegrationHealthData {
  status: string
  service: string
  modules: ModuleMap
}

const emptyStats: DashboardStats = {
  productCount: 0,
  docCount: 0,
  pendingOrderCount: 0
}

const defaultModules: ModuleMap = {
  product: false,
  order: false,
  admin: false,
  aiGateway: false,
  database: false
}

const stats = ref<DashboardStats>({ ...emptyStats })
const healthStatus = ref('检查中...')
const serviceName = ref('aimall-server')
const moduleStatus = ref<ModuleMap>({ ...defaultModules })

const moduleList = computed(() => [
  { key: 'product', label: '商品模块', value: moduleStatus.value.product, isDb: false },
  { key: 'order', label: '订单模块', value: moduleStatus.value.order, isDb: false },
  { key: 'admin', label: '管理模块', value: moduleStatus.value.admin, isDb: false },
  { key: 'aiGateway', label: 'AI 网关', value: moduleStatus.value.aiGateway, isDb: false },
  { key: 'database', label: '数据库', value: moduleStatus.value.database, isDb: true }
])

async function loadDashboard() {
  try {
    const response = await http.get<ApiResponse<DashboardStats>>('/api/admin/dashboard')
    stats.value = response.data.data
  } catch (error) {
    stats.value = { ...emptyStats }
    ElMessage.error(error instanceof Error ? error.message : '工作台数据加载失败')
  }
}

async function checkHealth() {
  try {
    const response = await http.get<ApiResponse<IntegrationHealthData>>('/api/health/integration')
    healthStatus.value = response.data.data.status
    serviceName.value = response.data.data.service
    moduleStatus.value = response.data.data.modules
  } catch {
    healthStatus.value = 'OFFLINE'
    serviceName.value = 'aimall-server'
    moduleStatus.value = { ...defaultModules }
  }
}

onMounted(() => {
  loadDashboard()
  checkHealth()
})
</script>

<style scoped>
.dashboard h2 {
  margin-bottom: 20px;
}
.health-card {
  margin-bottom: 20px;
}
.health-info {
  display: flex;
  gap: 24px;
  align-items: center;
  font-size: 14px;
}
.health-info span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.modules-card {
  margin-bottom: 20px;
}
.module-item {
  margin-bottom: 10px;
}
.module-label {
  font-size: 14px;
  color: #606266;
  margin-right: 4px;
}
.stat-cards {
  margin-top: 10px;
}
.stat-card {
  text-align: center;
  padding: 20px 0;
}
.stat-value {
  font-size: 36px;
  font-weight: 700;
  color: #409eff;
}
.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 8px;
}
</style>
