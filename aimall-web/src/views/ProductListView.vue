<script setup lang="ts">
import { ref, onMounted } from "vue";
import { fetchProducts, type Product } from "../api/productApi";

const mockProducts: Product[] = [
  { productId: 1001, name: "学习平板 A1", price: 2999, category: "平板电脑", stock: 100, description: "" },
  { productId: 1002, name: "轻薄笔记本 B2", price: 3999, category: "笔记本电脑", stock: 50, description: "" },
  { productId: 1003, name: "无线蓝牙耳机 C3", price: 399, category: "耳机", stock: 200, description: "" },
];

const products = ref<Product[]>(mockProducts);

onMounted(async () => {
  try {
    const res = await fetchProducts();
    if (res.data.code === 0) {
      products.value = res.data.data;
    }
  } catch {
    // fallback to local mock
  }
});
</script>

<template>
  <div class="product-list">
    <h1>商品列表</h1>
    <div class="product-grid">
      <div v-for="p in products" :key="p.productId" class="product-card">
        <h3>{{ p.name }}</h3>
        <p class="price">&yen;{{ p.price }}</p>
        <p class="category">{{ p.category }}</p>
        <router-link :to="`/products/${p.productId}`" class="primary detail-btn">
          查看详情
        </router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.product-list h1 {
  margin-bottom: 20px;
  font-size: 22px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.product-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.product-card h3 {
  font-size: 16px;
  margin-bottom: 8px;
}

.price {
  color: #e4393c;
  font-size: 20px;
  font-weight: 700;
  margin-bottom: 4px;
}

.category {
  color: #999;
  font-size: 13px;
  margin-bottom: 12px;
}

.detail-btn {
  display: inline-block;
  padding: 6px 16px;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  font-size: 14px;
  text-decoration: none;
}

.detail-btn:hover {
  background: #66b1ff;
  text-decoration: none;
}
</style>
