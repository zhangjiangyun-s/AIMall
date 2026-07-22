<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { fetchProductCategories } from "../../api/productApi";
import type { ProductCategory } from "../../api/productApi";

const router = useRouter();
const categories = ref<ProductCategory[]>([]);

onMounted(async () => {
  try {
    const res = await fetchProductCategories();
    if (res.data.code === 0) {
      categories.value = res.data.data;
    }
  } catch {
    // ignore
  }
});

function goCategory(id?: number) {
  if (id) {
    router.push(`/products?categoryId=${id}`);
  } else {
    router.push("/products");
  }
}
</script>

<template>
  <nav class="category-nav">
    <div class="nav-inner">
      <router-link to="/" class="nav-link" exact-active-class="active">首页</router-link>
      <router-link to="/products" class="nav-link" active-class="active">全部商品</router-link>
      <template v-if="categories.length > 0">
        <button v-for="cat in categories.slice(0, 6)" :key="cat.id" class="nav-link cat-btn" @click="goCategory(cat.id)">
          {{ cat.name }}
        </button>
      </template>
      <router-link to="/products" class="nav-link more-link">更多 &rsaquo;</router-link>
      <div class="nav-ai">
        <span class="ai-badge">AI</span>
        <span>智能导购</span>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.category-nav {
  background: var(--color-bg-white);
  border-bottom: 1px solid var(--color-border);
}

.nav-inner {
  max-width: var(--content-width);
  margin: 0 auto;
  height: 42px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 2px;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.nav-link {
  padding: 8px 14px;
  font-size: 13px;
  color: var(--color-text-muted);
  text-decoration: none;
  white-space: nowrap;
  border-radius: 6px;
  transition: all 0.2s;
}

.nav-link:hover {
  color: var(--color-primary);
  background: var(--color-primary-light);
  text-decoration: none;
}

.nav-link.active {
  color: var(--color-primary);
  font-weight: 600;
}

.cat-btn {
  border: none;
  background: none;
  cursor: pointer;
  font-family: inherit;
}

.more-link {
  color: var(--color-text-light);
  font-size: 12px;
}

.nav-ai {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  font-size: 13px;
  color: var(--color-primary);
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 6px;
  background: var(--color-primary-light);
}

.nav-ai:hover {
  background: #ffe0d8;
}

.ai-badge {
  font-size: 10px;
  font-weight: 700;
  background: var(--color-primary);
  color: #fff;
  padding: 2px 6px;
  border-radius: 3px;
}
</style>
