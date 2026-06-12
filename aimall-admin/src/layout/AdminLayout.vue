<template>
  <div class="admin-layout">
    <el-container style="height: 100vh">
      <el-header class="admin-header">
        <h1 class="header-title">AIMall 管理后台</h1>
      </el-header>
      <el-container>
        <el-aside width="200px" class="admin-aside">
          <el-menu
            :default-active="activeMenu"
            router
            class="admin-menu"
          >
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
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { HomeFilled, Goods, Document } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.admin-layout {
  height: 100vh;
}
.admin-header {
  display: flex;
  align-items: center;
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
