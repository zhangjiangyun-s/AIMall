<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchAddresses, type Address } from "../api/addressApi";
import { fetchCartList, type CartItem } from "../api/cartApi";
import { fetchAvailableCoupons, previewOrder, type MemberCoupon, type OrderPreview } from "../api/couponApi";
import { createOrder } from "../api/orderApi";

const router = useRouter();
const cartItems = ref<CartItem[]>([]);
const addresses = ref<Address[]>([]);
const coupons = ref<MemberCoupon[]>([]);
const selectedAddressId = ref<number | null>(null);
const selectedMemberCouponId = ref<number | null>(null);
const summary = ref<OrderPreview | null>(null);
const submitting = ref(false);
const loadingSummary = ref(false);
const msg = ref("");
const orderRequestId = ref(crypto.randomUUID());

const goodsAmount = computed(() => cartItems.value.reduce((sum, item) => sum + item.productPrice * item.quantity, 0));
const selectedAddress = computed(() => addresses.value.find((item) => item.id === selectedAddressId.value) || null);
const selectedCoupon = computed(() => coupons.value.find((item) => item.memberCouponId === selectedMemberCouponId.value) || null);
const payAmount = computed(() => summary.value?.payAmount ?? goodsAmount.value);
const couponAmount = computed(() => summary.value?.couponAmount ?? 0);
const freightAmount = computed(() => summary.value?.freightAmount ?? 0);

async function loadCoupons() {
  const res = await fetchAvailableCoupons(goodsAmount.value);
  if (res.data.code === 0) {
    coupons.value = res.data.data;
  }
}

async function loadSummary() {
  if (cartItems.value.length === 0) {
    summary.value = null;
    return;
  }

  loadingSummary.value = true;
  msg.value = "";
  try {
    const res = await previewOrder({
      cartItemIds: cartItems.value.map((item) => item.id),
      memberCouponId: selectedMemberCouponId.value,
    });

    if (res.data.code === 0) {
      summary.value = res.data.data;
      coupons.value = res.data.data.availableCoupons;
      if (selectedMemberCouponId.value) {
        const stillExists = coupons.value.some(
          (item) => item.memberCouponId === selectedMemberCouponId.value && item.available
        );
        if (!stillExists) {
          selectedMemberCouponId.value = null;
        }
      }
    } else {
      msg.value = res.data.message || "结算金额计算失败";
    }
  } catch {
    msg.value = "结算金额计算失败";
  } finally {
    loadingSummary.value = false;
  }
}

async function submitOrder() {
  if (cartItems.value.length === 0) return;
  if (!selectedAddressId.value) {
    msg.value = "请先选择收货地址";
    return;
  }

  submitting.value = true;
  msg.value = "";
  try {
    const res = await createOrder({
      requestId: orderRequestId.value,
      cartItemIds: cartItems.value.map((item) => item.id),
      addressId: selectedAddressId.value,
      memberCouponId: selectedMemberCouponId.value,
    });

    if (res.data.code === 0) {
      orderRequestId.value = crypto.randomUUID();
      router.push(`/orders/${res.data.data.orderId}`);
    } else {
      msg.value = res.data.message || "下单失败";
    }
  } catch {
    msg.value = "网络错误，请重试";
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  try {
    const [cartRes, addressRes] = await Promise.all([fetchCartList(), fetchAddresses()]);

    if (cartRes.data.code === 0) {
      cartItems.value = cartRes.data.data;
    }

    if (addressRes.data.code === 0) {
      addresses.value = addressRes.data.data;
      const defaultAddress = addresses.value.find((item) => item.defaultStatus === 1) || addresses.value[0];
      selectedAddressId.value = defaultAddress ? defaultAddress.id : null;
    }

    await Promise.all([loadCoupons(), loadSummary()]);
  } catch {
    msg.value = "加载结算信息失败";
  }
});

watch(selectedMemberCouponId, () => {
  void loadSummary();
});
</script>

<template>
  <div class="checkout-page">
    <h1>确认订单</h1>

    <p v-if="msg" class="msg">{{ msg }}</p>

    <section class="address-section">
      <div class="section-head">
        <h2>收货地址</h2>
        <router-link to="/account/address" class="manage-link">管理地址</router-link>
      </div>

      <div v-if="addresses.length === 0" class="empty-address">
        还没有收货地址，先去新增一个吧。
      </div>

      <div v-else class="address-list">
        <label v-for="address in addresses" :key="address.id" class="address-option">
          <input v-model="selectedAddressId" :value="address.id" type="radio" name="address" />
          <div class="address-card" :class="{ active: selectedAddressId === address.id }">
            <div class="address-top">
              <strong>{{ address.name }}</strong>
              <span>{{ address.phone }}</span>
              <span v-if="address.defaultStatus === 1" class="default-tag">默认地址</span>
            </div>
            <p>{{ address.fullAddress }}</p>
          </div>
        </label>
      </div>
    </section>

    <section v-if="coupons.length > 0" class="coupon-section">
      <div class="section-head">
        <h2>优惠券</h2>
        <span class="coupon-tip">每单仅可使用一张</span>
      </div>

      <div class="coupon-list">
        <label class="coupon-card coupon-card--none" :class="{ active: selectedMemberCouponId === null }">
          <input v-model="selectedMemberCouponId" :value="null" type="radio" name="coupon" />
          <div>
            <strong>不使用优惠券</strong>
            <p>按原价结算</p>
          </div>
        </label>

        <label
          v-for="coupon in coupons"
          :key="coupon.memberCouponId"
          class="coupon-card"
          :class="{ active: selectedMemberCouponId === coupon.memberCouponId, disabled: !coupon.available }"
        >
          <input
            v-model="selectedMemberCouponId"
            :value="coupon.memberCouponId"
            type="radio"
            name="coupon"
            :disabled="!coupon.available"
          />
          <div class="coupon-content">
            <div class="coupon-main">
              <strong>{{ coupon.name }}</strong>
              <span class="coupon-amount">-&yen;{{ coupon.amount }}</span>
            </div>
            <p>满 &yen;{{ coupon.minPoint }} 可用</p>
            <p v-if="coupon.note">{{ coupon.note }}</p>
            <p v-if="!coupon.available" class="coupon-disabled-tip">{{ coupon.unavailableReason }}</p>
          </div>
        </label>
      </div>
    </section>

    <div v-if="cartItems.length > 0" class="checkout-items">
      <div v-for="item in cartItems" :key="item.id" class="item">
        <div class="item-info">
          <span class="item-name">{{ item.productName }}</span>
          <span v-if="item.productAttr" class="item-attr">{{ item.productAttr }}</span>
        </div>
        <div class="item-detail">
          <span>&yen;{{ item.productPrice }} x {{ item.quantity }}</span>
          <span class="item-total">&yen;{{ item.productPrice * item.quantity }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="!msg" class="empty">加载中...</div>

    <div class="checkout-footer">
      <div class="summary">
        <p v-if="selectedAddress" class="summary-address">
          配送到：{{ selectedAddress.name }} {{ selectedAddress.phone }}，{{ selectedAddress.fullAddress }}
        </p>
        <p v-if="selectedCoupon" class="summary-coupon">已选优惠：{{ selectedCoupon.name }}</p>

        <div class="summary-rows">
          <div class="summary-row">
            <span>商品金额</span>
            <span>&yen;{{ goodsAmount }}</span>
          </div>
          <div class="summary-row">
            <span>运费</span>
            <span>&yen;{{ freightAmount }}</span>
          </div>
          <div class="summary-row discount">
            <span>优惠抵扣</span>
            <span>-&yen;{{ couponAmount }}</span>
          </div>
        </div>

        <div class="total-row">
          <span class="total-label">实付金额</span>
          <span class="total-amount">&yen;{{ payAmount }}</span>
        </div>
      </div>

      <button
        class="primary submit-btn"
        :disabled="submitting || loadingSummary || cartItems.length === 0 || !selectedAddressId"
        @click="submitOrder"
      >
        {{ submitting ? "提交中..." : "提交订单" }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.checkout-page {
  max-width: 860px;
  margin: 0 auto;
}

.checkout-page h1 {
  font-size: 22px;
  margin-bottom: 20px;
}

.address-section,
.coupon-section {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  margin-bottom: 20px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-head h2 {
  margin: 0;
  font-size: 18px;
}

.manage-link,
.coupon-tip {
  color: #2563eb;
  text-decoration: none;
  font-size: 14px;
}

.msg {
  color: #f56c6c;
  margin-bottom: 12px;
  font-size: 14px;
}

.empty,
.empty-address {
  text-align: center;
  padding: 24px 0;
  color: #999;
}

.address-list,
.coupon-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.address-option,
.coupon-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.address-card,
.coupon-card {
  flex: 1;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
}

.address-card.active,
.coupon-card.active {
  border-color: #2563eb;
  background: #f8fbff;
}

.coupon-card.disabled {
  opacity: 0.55;
}

.address-top,
.coupon-main {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 8px;
}

.address-card p,
.coupon-card p {
  margin: 0;
  color: #4b5563;
  line-height: 1.6;
}

.coupon-amount {
  color: #e4393c;
  font-weight: 700;
}

.coupon-disabled-tip {
  color: #dc2626;
}

.default-tag {
  font-size: 12px;
  color: #e11d48;
  background: #fff1f2;
  border-radius: 999px;
  padding: 2px 8px;
}

.checkout-items {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.item {
  background: #fff;
  border-radius: 8px;
  padding: 16px 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.item-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.item-name {
  font-size: 14px;
  font-weight: 500;
}

.item-attr {
  font-size: 12px;
  color: #999;
}

.item-detail {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: #666;
}

.item-total {
  color: #e4393c;
}

.checkout-footer {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.summary {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.summary-address,
.summary-coupon {
  margin: 0;
  color: #4b5563;
  line-height: 1.6;
}

.summary-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  color: #666;
  font-size: 14px;
}

.summary-row.discount {
  color: #16a34a;
}

.total-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.total-label {
  font-size: 14px;
  color: #666;
}

.total-amount {
  font-size: 24px;
  font-weight: 700;
  color: #e4393c;
}

.submit-btn {
  padding: 12px 32px;
  font-size: 16px;
}

@media (max-width: 768px) {
  .checkout-footer {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
