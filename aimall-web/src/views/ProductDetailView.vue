<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchProductById, type Product } from "../api/productApi";

const route = useRoute();

const mockData: Record<number, Product> = {
  1001: {
    productId: 1001, name: "学习平板 A1", price: 2999, category: "平板电脑", stock: 100,
    description: "10.4 英寸高清屏幕，8GB 运行内存，128GB 存储空间，支持手写笔，适合在线学习和办公。",
  },
  1002: {
    productId: 1002, name: "轻薄笔记本 B2", price: 3999, category: "笔记本电脑", stock: 50,
    description: "14 英寸全高清屏，16GB 内存，512GB 固态硬盘，续航 10 小时，重量仅 1.3kg。",
  },
  1003: {
    productId: 1003, name: "无线蓝牙耳机 C3", price: 399, category: "耳机", stock: 200,
    description: "真无线立体声，主动降噪，IPX5 防水，单次续航 8 小时，充电盒支持无线充电。",
  },
};

const product = ref<Product | null>(null);
const cartMsg = ref("");

const productId = computed(() => Number(route.params.id));

async function loadProduct(id: number) {
  try {
    const res = await fetchProductById(id);
    if (res.data.code === 0) {
      product.value = res.data.data;
      return;
    }
  } catch {
    // fallback below
  }
  product.value = mockData[id] ?? null;
}

onMounted(() => loadProduct(productId.value));
watch(productId, (id) => loadProduct(id));

function addToCart() {
  cartMsg.value = "本轮暂未接入购物车";
  setTimeout(() => (cartMsg.value = ""), 2000);
}
</script>

<template>
  <div class="product-detail" v-if="product">
    <router-link to="/products" class="back-link">&larr; 返回商品列表</router-link>

    <div class="detail-card">
      <h1>{{ product.name }}</h1>
      <p class="price">&yen;{{ product.price }}</p>
      <p><strong>分类：</strong>{{ product.category }}</p>
      <p><strong>库存：</strong>{{ product.stock }}</p>
      <p class="desc"><strong>商品说明：</strong>{{ product.description }}</p>

      <button class="primary cart-btn" @click="addToCart">加入购物车</button>
      <p v-if="cartMsg" class="cart-tip">{{ cartMsg }}</p>
    </div>
  </div>

  <div class="not-found" v-else>
    <p>商品不存在</p>
    <router-link to="/products">返回商品列表</router-link>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 16px;
  font-size: 14px;
}

.detail-card {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.detail-card h1 {
  font-size: 22px;
  margin-bottom: 12px;
}

.price {
  color: #e4393c;
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 12px;
}

.detail-card p {
  margin-bottom: 8px;
  font-size: 14px;
}

.desc {
  line-height: 1.6;
}

.cart-btn {
  margin-top: 16px;
  padding: 10px 24px;
  font-size: 16px;
}

.cart-tip {
  color: #e6a23c;
  margin-top: 8px !important;
}

.not-found {
  text-align: center;
  padding: 60px 0;
  color: #999;
}
</style>
