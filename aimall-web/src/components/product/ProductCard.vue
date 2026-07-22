<script setup lang="ts">
import { computed } from "vue";
import type { ProductListItem } from "../../api/productApi";

const props = defineProps<{ product: ProductListItem }>();
const emit = defineEmits<{ click: [] }>();

const colors = ["#ff5a3d", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#8b5cf6", "#ec4899", "#06b6d4", "#14b8a6", "#a855f7"];
const bgColor = computed(() => {
  const id = props.product.productId || props.product.name.charCodeAt(0);
  return colors[id % colors.length];
});
</script>

<template>
  <div class="product-card" @click="emit('click')">
    <div class="card-img" :style="{ background: bgColor }">
      <img v-if="product.pic" :src="product.pic" :alt="product.name" class="product-image" />
      <span v-else class="img-text">{{ product.name[0] }}</span>
    </div>
    <div class="card-body">
      <h4 class="card-title">{{ product.name }}</h4>
      <p v-if="product.subTitle" class="card-subtitle">{{ product.subTitle }}</p>
      <div class="card-tags" v-if="product.sellingPoints && product.sellingPoints.length > 0">
        <span v-for="(point, i) in product.sellingPoints.slice(0, 2)" :key="i" class="tag">{{ point }}</span>
      </div>
      <div class="card-price-row">
        <span class="price">&yen;{{ product.price }}</span>
        <span v-if="product.originalPrice && product.originalPrice > product.price" class="original-price">&yen;{{ product.originalPrice }}</span>
      </div>
      <p v-if="product.brandName" class="card-brand">{{ product.brandName }}</p>
    </div>
  </div>
</template>

<style scoped>
.product-card {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s;
  box-shadow: var(--shadow-sm);
}
.product-card:hover {
  box-shadow: var(--shadow-card);
}

.card-img {
  width: 100%;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.product-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.img-text {
  font-size: 36px;
  font-weight: 700;
  color: #fff;
  opacity: 0.8;
}

.card-body {
  padding: 12px;
}

.card-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 4px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-subtitle {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-tags {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 3px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  border: 1px solid var(--color-primary-border);
}

.card-price-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 2px;
}

.price {
  color: var(--color-primary);
  font-size: 18px;
  font-weight: 700;
}

.original-price {
  color: var(--color-text-light);
  font-size: 12px;
  text-decoration: line-through;
}

.card-brand {
  font-size: 11px;
  color: var(--color-text-light);
}
</style>
