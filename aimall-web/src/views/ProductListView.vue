<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  fetchProductCategories,
  fetchProducts,
  type ProductCategory,
  type ProductListItem,
} from "../api/productApi";
import ProductCard from "../components/product/ProductCard.vue";

type ProductSort = "DEFAULT" | "SALES" | "PRICE_ASC" | "PRICE_DESC" | "NEWEST";

const route = useRoute();
const router = useRouter();
const products = ref<ProductListItem[]>([]);
const categories = ref<ProductCategory[]>([]);
const loading = ref(true);
const sortBy = ref<ProductSort>("DEFAULT");
const inStockOnly = ref(false);
const page = ref(1);
const pageSize = 20;
const total = ref(0);

const keyword = computed(() => (route.query.keyword as string) || "");
const categoryId = computed(() => (route.query.categoryId ? Number(route.query.categoryId) : undefined));
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));

async function loadProducts() {
  loading.value = true;
  try {
    const [productResponse, categoryResponse] = await Promise.all([
      fetchProducts({
        keyword: keyword.value || undefined,
        categoryId: categoryId.value,
        sort: sortBy.value,
        inStock: inStockOnly.value,
        page: page.value,
        size: pageSize,
      }),
      fetchProductCategories(),
    ]);
    if (productResponse.data.code === 0) {
      products.value = productResponse.data.data.list;
      total.value = productResponse.data.data.total;
    }
    if (categoryResponse.data.code === 0) categories.value = categoryResponse.data.data;
  } finally {
    loading.value = false;
  }
}

function selectCategory(id?: number) {
  router.push({
    query: {
      ...(keyword.value ? { keyword: keyword.value } : {}),
      ...(id ? { categoryId: id } : {}),
    },
  });
}

function changeSort(value: ProductSort) {
  if (sortBy.value === value) return;
  sortBy.value = value;
  page.value = 1;
  loadProducts();
}

function toggleInStock() {
  inStockOnly.value = !inStockOnly.value;
  page.value = 1;
  loadProducts();
}

function changePage(nextPage: number) {
  if (nextPage < 1 || nextPage > totalPages.value) return;
  page.value = nextPage;
  loadProducts();
}

function goProduct(id: number) {
  router.push(`/products/${id}`);
}

onMounted(loadProducts);
watch([keyword, categoryId], () => {
  page.value = 1;
  loadProducts();
});
</script>

<template>
  <div class="product-list-page">
    <div class="breadcrumb">
      <router-link to="/">首页</router-link>
      <span class="sep">/</span>
      <span>全部商品</span>
      <span v-if="keyword" class="crumb-keyword">/ 搜索：{{ keyword }}</span>
    </div>

    <div v-if="categories.length" class="category-bar">
      <button :class="['cat-btn', !categoryId ? 'active' : '']" @click="selectCategory()">全部分类</button>
      <button
        v-for="category in categories"
        :key="category.id"
        :class="['cat-btn', categoryId === category.id ? 'active' : '']"
        @click="selectCategory(category.id)"
      >
        {{ category.name }}
      </button>
    </div>

    <div class="result-header">
      <p class="result-summary">
        <template v-if="keyword">搜索“{{ keyword }}”，</template>共 {{ total }} 件商品
      </p>
      <div class="sort-bar" aria-label="商品排序与库存筛选">
        <label class="stock-filter">
          <input type="checkbox" :checked="inStockOnly" @change="toggleInStock" />
          仅看有货
        </label>
        <button :class="['sort-btn', sortBy === 'DEFAULT' ? 'active' : '']" @click="changeSort('DEFAULT')">综合</button>
        <button :class="['sort-btn', sortBy === 'SALES' ? 'active' : '']" @click="changeSort('SALES')">销量</button>
        <button :class="['sort-btn', sortBy === 'PRICE_ASC' ? 'active' : '']" @click="changeSort('PRICE_ASC')">价格升序</button>
        <button :class="['sort-btn', sortBy === 'PRICE_DESC' ? 'active' : '']" @click="changeSort('PRICE_DESC')">价格降序</button>
        <button :class="['sort-btn', sortBy === 'NEWEST' ? 'active' : '']" @click="changeSort('NEWEST')">最新</button>
      </div>
    </div>

    <div v-if="loading" class="loading-state">加载中...</div>
    <div v-else-if="products.length === 0" class="empty-state">暂无商品</div>
    <div v-else class="product-grid">
      <ProductCard v-for="product in products" :key="product.productId" :product="product" @click="goProduct(product.productId)" />
    </div>

    <div v-if="!loading && totalPages > 1" class="pagination-bar">
      <button class="page-btn" :disabled="page === 1" @click="changePage(page - 1)">上一页</button>
      <span class="page-info">{{ page }} / {{ totalPages }}</span>
      <button class="page-btn" :disabled="page === totalPages" @click="changePage(page + 1)">下一页</button>
    </div>
  </div>
</template>

<style scoped>
.product-list-page { padding-top: 20px; }
.category-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--color-border); }
.cat-btn, .sort-btn { padding: 6px 16px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-bg-white); font-size: 13px; cursor: pointer; color: var(--color-text-body); transition: all 0.2s; }
.cat-btn.active, .sort-btn.active { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-primary-light); }
.cat-btn:hover, .sort-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.result-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.result-summary { font-size: 14px; color: var(--color-text-muted); }
.sort-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 4px; }
.stock-filter { display: inline-flex; align-items: center; gap: 6px; margin-right: 6px; font-size: 13px; color: var(--color-text-body); cursor: pointer; }
.sort-btn { padding: 5px 12px; }
.loading-state, .empty-state { text-align: center; padding: 60px 0; color: var(--color-text-muted); font-size: 14px; }
.product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 16px; }
.pagination-bar { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 24px; }
.page-btn { height: 34px; padding: 0 14px; border: 1px solid var(--color-border); border-radius: 6px; background: var(--color-bg-white); color: var(--color-text-body); cursor: pointer; }
.page-btn:disabled { color: var(--color-text-muted); cursor: not-allowed; opacity: 0.5; }
.page-info { min-width: 72px; text-align: center; font-size: 13px; color: var(--color-text-muted); }
@media (max-width: 760px) {
  .result-header { flex-direction: column; align-items: flex-start; }
  .sort-bar { justify-content: flex-start; }
  .product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
}
</style>
