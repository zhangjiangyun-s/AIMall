<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchOrders, type Order } from "../api/orderApi";

const allOrders = ref<Order[]>([]);
const loading = ref(true);
const activeTab = ref("all");

const statusTabs = [
  { key: "all", label: "全部" },
  { key: "WAIT_PAY", label: "待支付" },
  { key: "WAIT_SHIP", label: "待发货" },
  { key: "SHIPPED", label: "已发货" },
  { key: "COMPLETED", label: "已完成" },
  { key: "CLOSED", label: "已关闭" },
];

const filteredOrders = computed(() => {
  if (activeTab.value === "all") return allOrders.value;
  return allOrders.value.filter((order) => order.status === activeTab.value);
});

onMounted(async () => {
  try {
    const res = await fetchOrders();
    if (res.data.code === 0) {
      allOrders.value = res.data.data;
    }
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="order-list-page">
    <h1>订单列表</h1>

    <div class="status-tabs">
      <button
        v-for="tab in statusTabs"
        :key="tab.key"
        :class="['tab-btn', activeTab === tab.key ? 'active' : '']"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="filteredOrders.length === 0" class="empty">暂无订单</div>

    <div v-else class="order-list">
      <div v-for="order in filteredOrders" :key="order.orderId" class="order-card">
        <div class="order-header">
          <span class="order-no">订单号：{{ order.orderNo }}</span>
          <span :class="['order-status', order.status]">{{ order.statusText }}</span>
        </div>
        <div class="order-items-preview">
          <div
            v-for="item in order.items"
            :key="`${order.orderId}-${item.productId}-${item.skuCode || ''}`"
            class="item-row"
          >
            <span class="item-name">{{ item.productName }}</span>
            <span class="item-qty">x{{ item.quantity }}</span>
            <span class="item-price">&yen;{{ item.price }}</span>
          </div>
        </div>
        <div class="order-footer">
          <span class="order-total">合计：&yen;{{ order.totalAmount }}</span>
          <router-link :to="`/orders/${order.orderId}`" class="detail-link">查看详情</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-list-page {
  padding-top: 20px;
}

.order-list-page h1 {
  font-size: 22px;
  margin-bottom: 20px;
}

.status-tabs {
  display: flex;
  gap: 0;
  margin-bottom: 20px;
  border-bottom: 2px solid var(--color-border);
  overflow-x: auto;
}

.tab-btn {
  padding: 10px 20px;
  border: none;
  background: none;
  font-size: 14px;
  cursor: pointer;
  color: var(--color-text-muted);
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
  white-space: nowrap;
}

.tab-btn.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}

.tab-btn:hover {
  color: var(--color-primary);
}

.loading,
.empty {
  text-align: center;
  padding: 40px 0;
  color: var(--color-text-muted);
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-card {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-sm);
}

.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid var(--color-border-light);
}

.order-no {
  font-size: 13px;
  color: var(--color-text-muted);
}

.order-status {
  font-size: 13px;
  font-weight: 600;
}

.order-status.WAIT_PAY {
  color: var(--color-danger);
}

.order-status.WAIT_SHIP {
  color: var(--color-warning);
}

.order-status.SHIPPED {
  color: #3b82f6;
}

.order-status.COMPLETED {
  color: var(--color-success);
}

.order-status.CLOSED {
  color: #6b7280;
}

.order-items-preview {
  padding: 12px 20px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  font-size: 14px;
}

.item-name {
  flex: 1;
  color: var(--color-text);
}

.item-qty {
  color: var(--color-text-muted);
  font-size: 13px;
}

.item-price {
  color: var(--color-primary);
  min-width: 60px;
  text-align: right;
  font-weight: 500;
}

.order-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-top: 1px solid var(--color-border-light);
}

.order-total {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
}

.detail-link {
  padding: 6px 16px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  color: var(--color-primary);
  font-size: 13px;
  text-decoration: none;
}

.detail-link:hover {
  background: var(--color-primary-light);
  text-decoration: none;
}
</style>
