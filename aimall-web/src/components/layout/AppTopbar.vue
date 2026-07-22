<script setup lang="ts">
defineProps<{ isLoggedIn: boolean; userNickname: string }>();
const emit = defineEmits<{ (e: "logout"): void }>();
</script>

<template>
  <div class="topbar">
    <div class="topbar-inner">
      <span class="topbar-welcome">欢迎来到 AIMall，专注高效、安心的购物体验</span>
      <div class="topbar-links">
        <template v-if="isLoggedIn">
          <router-link to="/account" class="top-link">{{ userNickname || "个人中心" }}</router-link>
          <router-link to="/orders" class="top-link">我的订单</router-link>
          <router-link to="/account/coupons" class="top-link">我的优惠券</router-link>
          <router-link to="/returns" class="top-link">售后服务</router-link>
          <router-link to="/account/address" class="top-link">收货地址</router-link>
          <router-link to="/cart" class="top-link">购物车</router-link>
          <a class="top-link logout" href="#" @click.prevent="emit('logout')">退出</a>
        </template>
        <template v-else>
          <router-link to="/login" class="top-link">登录</router-link>
          <router-link to="/register" class="top-link">注册</router-link>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topbar {
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-border);
  font-size: 12px;
}

.topbar-inner {
  max-width: var(--content-width);
  margin: 0 auto;
  min-height: 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  gap: 16px;
}

.topbar-welcome {
  color: var(--color-text-muted);
}

.topbar-links {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.top-link {
  color: var(--color-text-muted);
  text-decoration: none;
  white-space: nowrap;
}

.top-link:hover {
  color: var(--color-primary);
  text-decoration: none;
}

.logout {
  cursor: pointer;
}

@media (max-width: 980px) {
  .topbar-links {
    gap: 10px;
    font-size: 11px;
  }
}

@media (max-width: 768px) {
  .topbar-welcome {
    display: none;
  }

  .topbar-inner {
    justify-content: flex-end;
  }
}
</style>
