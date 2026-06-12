<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchOrderById, type Order } from "../api/orderApi";

const route = useRoute();

const mockData: Record<number, Order> = {
  9001: {
    orderId: 9001,
    orderNo: "OD202606110001",
    status: "WAIT_DELIVER",
    statusText: "待发货",
    totalAmount: 399,
    items: [{ productName: "无线蓝牙耳机 C3", quantity: 1, price: 399 }],
  },
};

const order = ref<Order | null>(null);

const orderId = computed(() => Number(route.params.id));

async function loadOrder(id: number) {
  try {
    const res = await fetchOrderById(id);
    if (res.data.code === 0) {
      order.value = res.data.data;
      return;
    }
  } catch {
    // fallback below
  }
  order.value = mockData[id] ?? null;
}

onMounted(() => loadOrder(orderId.value));
watch(orderId, (id) => loadOrder(id));
</script>

<template>
  <div class="order-detail" v-if="order">
    <router-link to="/orders" class="back-link">&larr; 返回订单列表</router-link>

    <div class="detail-card">
      <h1>订单详情</h1>

      <div class="order-meta">
        <p><strong>订单号：</strong>{{ order.orderNo }}</p>
        <p><strong>订单状态：</strong><span class="status">{{ order.statusText }}</span></p>
      </div>

      <table class="order-items">
        <thead>
          <tr>
            <th>商品名称</th>
            <th>数量</th>
            <th>价格</th>
            <th>小计</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, i) in order.items" :key="i">
            <td>{{ item.productName }}</td>
            <td>{{ item.quantity }}</td>
            <td>&yen;{{ item.price }}</td>
            <td>&yen;{{ item.price * item.quantity }}</td>
          </tr>
        </tbody>
      </table>

      <p class="total"><strong>总金额：</strong>&yen;{{ order.totalAmount }}</p>
    </div>
  </div>

  <div class="not-found" v-else>
    <p>订单不存在</p>
    <router-link to="/orders">返回订单列表</router-link>
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
  margin-bottom: 16px;
}

.order-meta {
  margin-bottom: 16px;
}

.order-meta p {
  margin-bottom: 6px;
  font-size: 14px;
}

.status {
  color: #e6a23c;
  font-weight: 600;
}

.order-items {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 16px;
  font-size: 14px;
}

.order-items th,
.order-items td {
  text-align: left;
  padding: 8px 12px;
  border-bottom: 1px solid #e4e7ed;
}

.order-items th {
  background: #f5f7fa;
  font-weight: 600;
}

.total {
  text-align: right;
  font-size: 16px;
}

.not-found {
  text-align: center;
  padding: 60px 0;
  color: #999;
}
</style>
