<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";

defineProps<{
  isLoggedIn: boolean;
  userNickname: string;
}>();

const router = useRouter();
const keyword = ref("");

function searchProducts() {
  if (keyword.value.trim()) {
    router.push(`/products?keyword=${encodeURIComponent(keyword.value.trim())}`);
  } else {
    router.push("/products");
  }
}
</script>

<template>
  <header class="app-header">
    <div class="header-main">
      <div class="header-inner">
        <router-link to="/" class="logo">AIMall</router-link>

        <div class="search-box">
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索商品、品牌、分类"
            @keydown.enter="searchProducts"
          />
          <button class="search-btn" aria-label="搜索" @click="searchProducts">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.35-4.35" />
            </svg>
          </button>
        </div>

        <div class="header-actions">
          <router-link to="/orders" class="action-link desktop-only">订单</router-link>
          <router-link to="/coupons" class="action-link desktop-only">领券</router-link>
          <router-link to="/cart" class="action-link">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="9" cy="21" r="1" />
              <circle cx="20" cy="21" r="1" />
              <path d="m1 1 4 0 2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
            </svg>
            <span class="action-text">购物车</span>
          </router-link>
          <router-link v-if="isLoggedIn" to="/account" class="user-name">{{ userNickname || "个人中心" }}</router-link>
          <router-link v-else to="/login" class="action-link login-btn">登录</router-link>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  background: var(--color-bg-white);
}

.header-main {
  border-bottom: 1px solid var(--color-border);
}

.header-inner {
  max-width: var(--content-width);
  margin: 0 auto;
  height: 72px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 24px;
}

.logo {
  font-size: 26px;
  font-weight: 800;
  color: var(--color-primary);
  text-decoration: none;
  flex-shrink: 0;
}

.logo:hover {
  text-decoration: none;
}

.search-box {
  flex: 1;
  max-width: 520px;
  display: flex;
  border: 2px solid var(--color-primary);
  border-radius: 8px;
  overflow: hidden;
}

.search-box input {
  flex: 1;
  padding: 10px 14px;
  border: none;
  outline: none;
  font-size: 14px;
  height: 42px;
  min-width: 0;
}

.search-btn {
  background: var(--color-primary);
  color: #fff;
  border: none;
  width: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.action-link,
.user-name {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--color-text-muted);
  text-decoration: none;
  font-size: 13px;
}

.action-link:hover,
.user-name:hover {
  color: var(--color-primary);
  text-decoration: none;
}

.action-text {
  display: none;
}

.desktop-only {
  display: none;
}

.user-name {
  color: var(--color-text);
  font-weight: 600;
}

.login-btn {
  padding: 6px 16px;
  background: var(--color-primary);
  color: #fff;
  border-radius: 6px;
  font-weight: 500;
}

.login-btn:hover {
  color: #fff;
}

@media (min-width: 640px) {
  .action-text {
    display: inline;
  }
}

@media (min-width: 980px) {
  .desktop-only {
    display: inline-flex;
  }
}

@media (max-width: 768px) {
  .header-inner {
    gap: 14px;
  }

  .logo {
    font-size: 22px;
  }
}
</style>
