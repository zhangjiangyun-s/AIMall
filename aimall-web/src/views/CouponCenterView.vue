<script setup lang="ts">
import { onMounted, ref } from "vue";
import { claimCoupon, fetchCouponCenter, type CouponCenterItem } from "../api/couponApi";

const loading = ref(true);
const claimingId = ref<number | null>(null);
const coupons = ref<CouponCenterItem[]>([]);
const msg = ref("");

async function loadData() {
  loading.value = true;
  msg.value = "";
  try {
    const response = await fetchCouponCenter();
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

async function handleClaim(couponId: number) {
  if (claimingId.value) return;
  claimingId.value = couponId;
  msg.value = "";
  try {
    const response = await claimCoupon(couponId);
    if (response.data.code === 0) {
      msg.value = "领取成功";
      await loadData();
    } else {
      msg.value = response.data.message || "领取失败";
    }
  } catch {
    msg.value = "领取失败，请稍后重试";
  } finally {
    claimingId.value = null;
  }
}

onMounted(loadData);
</script>

<template>
  <div class="coupon-page page-container">
    <div class="page-header">
      <div>
        <h1>优惠券中心</h1>
        <p>先领券再下单，结算时可直接选择符合条件的优惠券。</p>
      </div>
      <div class="header-actions">
        <router-link to="/account/coupons" class="plain-link">我的优惠券</router-link>
        <router-link to="/products" class="primary-link">去挑商品</router-link>
      </div>
    </div>

    <p v-if="msg" class="page-msg">{{ msg }}</p>
    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else-if="coupons.length === 0" class="empty-state">
      暂时没有可领取的优惠券
    </div>

    <div v-else class="coupon-grid">
      <article v-for="coupon in coupons" :key="coupon.couponId" class="coupon-card">
        <div class="coupon-amount">
          <span class="currency">&yen;</span>
          <strong>{{ coupon.amount }}</strong>
        </div>
        <div class="coupon-body">
          <div class="coupon-title-row">
            <h2>{{ coupon.name }}</h2>
            <span v-if="coupon.claimed" class="status-tag">已领取</span>
          </div>
          <p>满 &yen;{{ coupon.minPoint }} 可用</p>
          <p v-if="coupon.note">{{ coupon.note }}</p>
          <small v-if="coupon.startTime || coupon.endTime">
            有效期：{{ coupon.startTime || "不限" }} 至 {{ coupon.endTime || "不限" }}
          </small>
        </div>
        <button
          class="claim-btn"
          :disabled="!coupon.claimable || claimingId === coupon.couponId"
          @click="handleClaim(coupon.couponId)"
        >
          {{ coupon.claimed ? "已领取" : coupon.claimable ? (claimingId === coupon.couponId ? "领取中..." : "立即领取") : "暂不可领" }}
        </button>
      </article>
    </div>
  </div>
</template>

<style scoped>
.coupon-page {
  padding-top: 20px;
  padding-bottom: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}

.page-header h1 {
  font-size: 24px;
  margin-bottom: 6px;
}

.page-header p {
  color: var(--color-text-muted);
  font-size: 14px;
}

.header-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.plain-link,
.primary-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 14px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  text-decoration: none;
}

.plain-link {
  color: var(--color-primary);
  border: 1px solid var(--color-primary);
  background: #fff;
}

.primary-link {
  color: #fff;
  background: var(--color-primary);
}

.page-msg {
  margin-bottom: 16px;
  color: var(--color-primary);
}

.loading-state,
.empty-state {
  padding: 40px 0;
  color: var(--color-text-muted);
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
  border: 1px solid var(--color-border-light);
}

.coupon-amount {
  text-align: center;
  color: var(--color-primary);
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
  font-size: 16px;
  margin: 0;
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
  background: #f3f4f6;
  color: #6b7280;
}

.claim-btn {
  height: 40px;
  border-radius: var(--radius-sm);
  border: none;
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
}

.claim-btn:disabled {
  background: #d1d5db;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .page-header,
  .coupon-card {
    grid-template-columns: 1fr;
  }

  .page-header {
    flex-direction: column;
  }

  .coupon-card {
    display: grid;
    text-align: left;
  }

  .coupon-amount {
    text-align: left;
  }
}
</style>
