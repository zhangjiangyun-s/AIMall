<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchMyCoupons, type OwnedCoupon } from "../api/couponApi";

const loading = ref(true);
const coupons = ref<OwnedCoupon[]>([]);
const activeTab = ref<"all" | "unused" | "used" | "expired">("all");
const msg = ref("");

const tabs = [
  { key: "all", label: "全部" },
  { key: "unused", label: "可使用" },
  { key: "used", label: "已使用" },
  { key: "expired", label: "已失效" },
] as const;

const filteredCoupons = computed(() => {
  if (activeTab.value === "unused") {
    return coupons.value.filter((item) => item.status === 0 && item.active);
  }
  if (activeTab.value === "used") {
    return coupons.value.filter((item) => item.status === 1);
  }
  if (activeTab.value === "expired") {
    return coupons.value.filter((item) => item.status !== 1 && !item.active);
  }
  return coupons.value;
});

const availableCount = computed(() => coupons.value.filter((item) => item.status === 0 && item.active).length);

async function loadCoupons() {
  loading.value = true;
  msg.value = "";
  try {
    const response = await fetchMyCoupons();
    if (response.data.code === 0) {
      coupons.value = response.data.data;
    } else {
      msg.value = response.data.message || "优惠券加载失败";
    }
  } catch {
    msg.value = "优惠券加载失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}

onMounted(loadCoupons);
</script>

<template>
  <div class="my-coupons-page page-container">
    <div class="page-header">
      <div>
        <h1>我的优惠券</h1>
        <p>当前有 {{ availableCount }} 张优惠券可在结算时使用。</p>
      </div>
      <router-link to="/coupons" class="primary-link">去领券</router-link>
    </div>

    <p v-if="msg" class="page-msg">{{ msg }}</p>

    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="['tab-btn', activeTab === tab.key ? 'active' : '']"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading" class="placeholder">加载中...</div>
    <div v-else-if="filteredCoupons.length === 0" class="placeholder">暂无优惠券</div>

    <div v-else class="coupon-grid">
      <article v-for="coupon in filteredCoupons" :key="coupon.memberCouponId" class="coupon-card">
        <div class="coupon-amount">
          <span class="currency">&yen;</span>
          <strong>{{ coupon.amount }}</strong>
        </div>
        <div class="coupon-body">
          <div class="coupon-title-row">
            <h2>{{ coupon.name }}</h2>
            <span :class="['status-tag', coupon.active && coupon.status === 0 ? 'available' : 'disabled']">
              {{ coupon.statusText }}
            </span>
          </div>
          <p>满 &yen;{{ coupon.minPoint }} 可用</p>
          <p v-if="coupon.note">{{ coupon.note }}</p>
          <small v-if="coupon.orderSn">已用于订单：{{ coupon.orderSn }}</small>
          <small v-else>领取时间：{{ coupon.createTime || "-" }}</small>
        </div>
        <router-link v-if="coupon.status === 0 && coupon.active" to="/cart" class="use-btn">去使用</router-link>
        <span v-else class="use-btn disabled">不可使用</span>
      </article>
    </div>
  </div>
</template>

<style scoped>
.my-coupons-page {
  padding-top: 20px;
  padding-bottom: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.page-header h1 {
  font-size: 24px;
  margin-bottom: 6px;
}

.page-header p {
  color: var(--color-text-muted);
}

.primary-link,
.use-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
  border-radius: var(--radius-sm);
}

.primary-link {
  height: 38px;
  padding: 0 16px;
  color: #fff;
  background: var(--color-primary);
}

.page-msg {
  color: #dc2626;
  margin-bottom: 12px;
}

.tabs {
  display: flex;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 16px;
  overflow-x: auto;
}

.tab-btn {
  padding: 10px 18px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  white-space: nowrap;
}

.tab-btn.active {
  border-bottom-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: 600;
}

.placeholder {
  padding: 48px 0;
  color: var(--color-text-muted);
  text-align: center;
}

.coupon-grid {
  display: grid;
  gap: 16px;
}

.coupon-card {
  display: grid;
  grid-template-columns: 120px 1fr 120px;
  align-items: center;
  gap: 18px;
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  padding: 20px;
  box-shadow: var(--shadow-sm);
  border: 1px dashed var(--color-border);
}

.coupon-amount {
  color: var(--color-primary);
  text-align: center;
}

.currency {
  font-size: 18px;
  margin-right: 2px;
}

.coupon-amount strong {
  font-size: 34px;
}

.coupon-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.coupon-body h2 {
  margin: 0;
  font-size: 16px;
}

.coupon-body p,
.coupon-body small {
  display: block;
  color: var(--color-text-muted);
  margin-bottom: 4px;
  font-size: 13px;
}

.status-tag {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.status-tag.available {
  background: #f0fdf4;
  color: #16a34a;
}

.status-tag.disabled {
  background: #f3f4f6;
  color: #6b7280;
}

.use-btn {
  height: 40px;
  background: var(--color-primary);
  color: #fff;
}

.use-btn.disabled {
  background: #d1d5db;
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
  }

  .coupon-card {
    grid-template-columns: 1fr;
  }

  .coupon-amount {
    text-align: left;
  }
}
</style>
