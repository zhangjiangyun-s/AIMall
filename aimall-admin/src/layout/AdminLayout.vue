<template>
  <div class="admin-layout">
    <el-container style="height: 100vh">
      <el-header class="admin-header">
        <h1 class="header-title">AIMall 管理后台</h1>
        <div class="header-right">
          <span class="admin-user" v-if="adminName">
            <el-icon><UserFilled /></el-icon>
            {{ adminName }}
          </span>
          <el-button size="small" type="danger" plain @click="handleLogout">退出</el-button>
        </div>
      </el-header>
      <el-container>
        <el-aside width="200px" class="admin-aside">
          <el-menu :default-active="activeMenu" router class="admin-menu">
            <el-menu-item index="/">
              <el-icon><HomeFilled /></el-icon>
              <span>工作台</span>
            </el-menu-item>
            <el-menu-item index="/products">
              <el-icon><Goods /></el-icon>
              <span>商品管理</span>
            </el-menu-item>
            <el-menu-item index="/knowledge-docs">
              <el-icon><Document /></el-icon>
              <span>知识库文档</span>
            </el-menu-item>
            <el-menu-item index="/orders">
              <el-icon><List /></el-icon>
              <span>订单管理</span>
            </el-menu-item>
            <el-menu-item index="/returns">
              <el-icon><RefreshLeft /></el-icon>
              <span>售后管理</span>
            </el-menu-item>
            <el-menu-item index="/payment-reconciliation">
              <el-icon><CreditCard /></el-icon>
              <span>支付对账</span>
            </el-menu-item>
          </el-menu>
        </el-aside>
        <el-main class="admin-main">
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { HomeFilled, Goods, Document, List, UserFilled, RefreshLeft, CreditCard } from "@element-plus/icons-vue";
import { ElAside, ElButton, ElContainer, ElHeader, ElIcon, ElMain, ElMenu, ElMenuItem } from "element-plus";
import { adminLogout } from "../api/adminLoginApi";

const route = useRoute();
const router = useRouter();
const activeMenu = computed(() => route.path);

const adminName = ref("");

onMounted(() => {
  const token = localStorage.getItem("admin_token");
  if (!token && route.path !== "/login") {
    router.push("/login");
    return;
  }
  const infoStr = localStorage.getItem("admin_info");
  if (infoStr) {
    try {
      const info = JSON.parse(infoStr);
      adminName.value = info.nickName || info.username;
    } catch {
      adminName.value = "";
    }
  }
});

async function handleLogout() {
  try {
    await adminLogout();
  } finally {
    localStorage.removeItem("admin_token");
    localStorage.removeItem("admin_info");
    router.push("/login");
  }
}
</script>

<style scoped>
.admin-layout {
  height: 100vh;
}
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #409eff;
  color: #fff;
  height: 60px;
  padding: 0 20px;
}
.header-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.admin-user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
}
.admin-aside {
  background-color: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}
.admin-menu {
  border-right: none;
  height: 100%;
}
.admin-main {
  background-color: #fff;
  padding: 20px;
}
</style>
