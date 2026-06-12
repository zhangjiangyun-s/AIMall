<script setup lang="ts">
import { ref, onMounted } from "vue";
import AiAssistant from "./components/AiAssistant.vue";
import { fetchIntegrationHealth } from "./api/healthApi";

const integrationStatus = ref("OFFLINE");
const integrationService = ref("aimall-server");
const databaseConnected = ref("未接入");

onMounted(async () => {
  try {
    const res = await fetchIntegrationHealth();
    if (res.data.code === 0) {
      integrationStatus.value = res.data.data.status;
      integrationService.value = res.data.data.service;
      databaseConnected.value = res.data.data.modules.database ? "已接入" : "未接入";
    }
  } catch {
    // fallback — keep default OFFLINE / aimall-server / 未接入
  }
});
</script>

<template>
  <div id="app-layout">
    <header class="app-header">
      <div class="header-inner">
        <router-link to="/products" class="logo">aimall-web</router-link>
        <nav class="nav-links">
          <router-link to="/products">商品</router-link>
          <router-link to="/orders">订单</router-link>
        </nav>
      </div>
      <div class="health-bar">
        <span :class="['health-status', integrationStatus === 'UP' ? 'up' : 'offline']">
          联调状态：{{ integrationStatus }}
        </span>
        <span class="health-divider">|</span>
        <span>后端：{{ integrationService }}</span>
        <span class="health-divider">|</span>
        <span>数据库：{{ databaseConnected }}</span>
      </div>
    </header>
    <main class="app-main">
      <router-view />
    </main>
    <AiAssistant />
  </div>
</template>

<style scoped>
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  height: 56px;
  padding: 0 20px;
}

.logo {
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
}

.nav-links {
  margin-left: 32px;
  display: flex;
  gap: 16px;
}

.nav-links a {
  font-size: 14px;
  color: #333;
  padding: 4px 8px;
  border-radius: 4px;
}

.nav-links a:hover,
.nav-links a.router-link-exact-active {
  color: #409eff;
  background: #ecf5ff;
  text-decoration: none;
}

.health-bar {
  max-width: 1200px;
  margin: 0 auto;
  padding: 6px 20px;
  font-size: 12px;
  color: #666;
  display: flex;
  gap: 8px;
  align-items: center;
}

.health-status {
  font-weight: 600;
}

.health-status.up {
  color: #67c23a;
}

.health-status.offline {
  color: #f56c6c;
}

.health-divider {
  color: #dcdfe6;
}

.app-main {
  max-width: 1200px;
  margin: 20px auto;
  padding: 0 20px;
}
</style>
