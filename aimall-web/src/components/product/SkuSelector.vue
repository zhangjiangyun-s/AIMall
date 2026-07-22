<script setup lang="ts">
import { ref, watch } from "vue";
import type { ProductSkuStock } from "../../api/productApi";

const props = defineProps<{ skuStocks: ProductSkuStock[] }>();
const emit = defineEmits<{ select: [sku: ProductSkuStock | null] }>();

const selectedSku = ref<ProductSkuStock | null>(null);

const keyMap: Record<string, string> = {
  color: "颜色",
  config: "配置",
  switch: "轴体",
  size: "尺寸",
  capacity: "容量",
};

const valueMap: Record<string, string> = {
  silver: "银色",
  gray: "灰色",
  black: "黑色",
  white: "白色",
  blue: "蓝色",
  pink: "粉色",
  green: "绿色",
  brown: "茶轴",
  linear: "线性轴",
};

watch(
  () => props.skuStocks,
  (list) => {
    if (list.length > 0) {
      selectedSku.value = list[0];
      emit("select", list[0]);
      return;
    }
    selectedSku.value = null;
    emit("select", null);
  },
  { immediate: true },
);

function formatValue(value: unknown) {
  if (typeof value !== "string") {
    return String(value ?? "");
  }
  return valueMap[value] ?? value;
}

function formatSpData(spData?: string) {
  if (!spData) {
    return "默认规格";
  }

  try {
    const parsed = JSON.parse(spData) as Record<string, unknown> | Array<{ key?: string; value?: unknown }>;
    const entries = Array.isArray(parsed)
      ? parsed
          .filter((item) => item.key && item.value !== undefined && item.value !== null && item.value !== "")
          .map((item) => `${keyMap[item.key as string] ?? item.key}：${formatValue(item.value)}`)
      : Object.entries(parsed)
          .filter(([, value]) => value !== undefined && value !== null && value !== "")
          .map(([key, value]) => `${keyMap[key] ?? key}：${formatValue(value)}`);

    return entries.length > 0 ? entries.join(" / ") : "默认规格";
  } catch {
    return spData;
  }
}

function select(sku: ProductSkuStock) {
  selectedSku.value = sku;
  emit("select", sku);
}
</script>

<template>
  <div v-if="skuStocks.length > 0" class="sku-selector">
    <p class="sku-label">规格选择</p>
    <div class="sku-list">
      <button
        v-for="sku in skuStocks"
        :key="sku.id"
        :class="['sku-btn', { active: selectedSku?.id === sku.id }]"
        :disabled="sku.stock <= 0"
        @click="select(sku)"
      >
        <span class="sku-attr">{{ formatSpData(sku.spData) }}</span>
        <span v-if="sku.stock <= 0" class="sku-stock">（无货）</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sku-selector {
  margin-bottom: 16px;
}

.sku-label {
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}

.sku-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.sku-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  color: #333;
}

.sku-btn.active {
  border-color: #e4393c;
  color: #e4393c;
  background: #fff5f5;
}

.sku-btn:disabled {
  color: #ccc;
  cursor: not-allowed;
  background: #f5f5f5;
}

.sku-stock {
  color: #999;
  font-size: 11px;
}
</style>
