<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchReturnList, type ReturnApply } from "../api/returnApi";

const loading = ref(true);
const returns = ref<ReturnApply[]>([]);

onMounted(async () => {
  try {
    const response = await fetchReturnList();
    if (response.data.code === 0) {
      returns.value = response.data.data;
    }
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="return-list-page">
    <h1>售后服务</h1>
    <div v-if="loading" class="placeholder">加载中...</div>
    <div v-else-if="returns.length === 0" class="placeholder">暂时没有售后申请</div>
    <div v-else class="return-list">
      <div v-for="item in returns" :key="item.id" class="return-card">
        <div class="return-header">
          <div>
            <h2>售后单 #{{ item.id }}</h2>
            <p>关联订单：{{ item.orderNo }}</p>
          </div>
          <span :class="['status-badge', item.status]">{{ item.statusText }}</span>
        </div>
        <div class="return-body">
          <p><strong>申请原因：</strong>{{ item.reason }}</p>
          <p><strong>退款金额：</strong>&yen;{{ item.returnAmount }}</p>
          <p><strong>提交时间：</strong>{{ item.createTime || "-" }}</p>
          <p v-if="item.handleNote"><strong>处理说明：</strong>{{ item.handleNote }}</p>
        </div>
        <router-link :to="`/returns/${item.id}`" class="detail-link">查看详情</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.return-list-page {
  padding-top: 20px;
}

.return-list-page h1 {
  font-size: 22px;
  margin-bottom: 20px;
}

.placeholder {
  text-align: center;
  padding: 60px 0;
  color: var(--color-text-muted);
}

.return-list {
  display: grid;
  gap: 16px;
}

.return-card {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-sm);
  padding: 20px;
}

.return-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.return-header h2 {
  margin: 0 0 6px;
  font-size: 18px;
}

.return-header p,
.return-body p {
  margin: 0 0 8px;
  color: var(--color-text-body);
}

.status-badge {
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.status-badge.PENDING_REVIEW {
  background: #fffbeb;
  color: #d97706;
}

.status-badge.APPROVED {
  background: #eff6ff;
  color: #2563eb;
}

.status-badge.REJECTED,
.status-badge.CANCELLED {
  background: #fef2f2;
  color: #dc2626;
}

.status-badge.REFUNDED {
  background: #f0fdf4;
  color: #16a34a;
}

.detail-link {
  display: inline-flex;
  margin-top: 8px;
  color: var(--color-primary);
  text-decoration: none;
}
</style>
