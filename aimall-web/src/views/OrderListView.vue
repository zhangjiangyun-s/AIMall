<script setup lang="ts">
import { ref, onMounted } from "vue";
import { fetchOrders, type Order } from "../api/orderApi";

const mockOrders: Order[] = [
  {
    orderId: 9001,
    orderNo: "OD202606110001",
    status: "WAIT_DELIVER",
    statusText: "待发货",
    totalAmount: 399,
    items: [{ productName: "无线蓝牙耳机 C3", quantity: 1, price: 399 }],
  },
];

const orders = ref<Order[]>(mockOrders);

onMounted(async () => {
  try {
    const res = await fetchOrders();
    if (res.data.code === 0) {
      orders.value = res.data.data;
    }
  } catch {
    // fallback to local mock
  }
});
</script>

<template>
  <div class="order-list">
    <h1>订单列表</h1>

    <div v-if="orders.length === 0" class="empty">暂无订单</div>

    <div v-for="o in orders" :key="o.orderId" class="order-card">
      <div class="order-info">
        <p><strong>订单号：</strong>{{ o.orderNo }}</p>
        <p><strong>状态：</strong><span class="status">{{ o.statusText }}</span></p>
        <p><strong>金额：</strong>&yen;{{ o.totalAmount }}</p>
        <p><strong>商品：</strong>{{ o.items.map(i => i.productName).join("、") }}</p>
      </div>
      <router-link :to="`/orders/${o.orderId}`" class="primary">查看订单</router-link>
    </div>
  </div>
</template>

<style scoped>
.order-list h1 {
  margin-bottom: 20px;
  font-size: 22px;
}

.empty {
  text-align: center;
  padding: 60px 0;
  color: #999;
}

.order-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.order-card + .order-card {
  margin-top: 12px;
}

.order-info p {
  margin-bottom: 4px;
  font-size: 14px;
}

.status {
  color: #e6a23c;
  font-weight: 600;
}
</style>
