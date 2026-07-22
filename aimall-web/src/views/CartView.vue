<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { deleteCartItem, fetchCartList, updateCartItem, type CartItem } from "../api/cartApi";

const router = useRouter();
const cartItems = ref<CartItem[]>([]);
const loading = ref(false);
const msg = ref("");
const msgType = ref<"success" | "error" | "">("");

onMounted(async () => {
  await loadCart();
});

async function loadCart() {
  loading.value = true;
  try {
    const res = await fetchCartList();
    if (res.data.code === 0) {
      cartItems.value = res.data.data;
    }
  } catch {
    cartItems.value = [];
  } finally {
    loading.value = false;
  }
}

async function handleQuantityChange(item: CartItem, delta: number) {
  const newQty = item.quantity + delta;
  if (newQty < 1) return;
  try {
    const res = await updateCartItem({ cartItemId: item.id, quantity: newQty });
    if (res.data.code === 0) {
      item.quantity = newQty;
    } else {
      showMsg(res.data.message || "数量更新失败", "error");
    }
  } catch {
    showMsg("数量更新失败，请稍后重试", "error");
  }
}

async function handleDelete(item: CartItem) {
  try {
    const res = await deleteCartItem({ cartItemId: item.id });
    if (res.data.code === 0) {
      cartItems.value = cartItems.value.filter((i) => i.id !== item.id);
      showMsg("商品已从购物车移除", "success");
    } else {
      showMsg(res.data.message || "删除失败", "error");
    }
  } catch {
    showMsg("删除失败，请稍后重试", "error");
  }
}

const totalAmount = computed(() => cartItems.value.reduce((sum, i) => sum + i.productPrice * i.quantity, 0));
const totalCount = computed(() => cartItems.value.reduce((sum, i) => sum + i.quantity, 0));

function goCheckout() {
  if (cartItems.value.length === 0) return;
  router.push("/checkout");
}

function showMsg(text: string, type: "success" | "error" | "") {
  msg.value = text;
  msgType.value = type;
  setTimeout(() => {
    msg.value = "";
    msgType.value = "";
  }, 2000);
}
</script>

<template>
  <div class="cart-page page-container">
    <h1>购物车</h1>

    <div class="cart-steps">
      <span class="step active">1. 购物车</span>
      <span class="step-arrow">&rsaquo;</span>
      <span class="step">2. 确认订单</span>
      <span class="step-arrow">&rsaquo;</span>
      <span class="step">3. 完成支付</span>
    </div>

    <p v-if="msg" :class="['page-msg', msgType]">{{ msg }}</p>

    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else-if="cartItems.length === 0" class="empty-cart">
      <div class="empty-icon">
        <svg width="80" height="80" viewBox="0 0 24 24" fill="none" stroke="#d1d5db" stroke-width="1.5">
          <circle cx="9" cy="21" r="1" />
          <circle cx="20" cy="21" r="1" />
          <path d="m1 1 4 0 2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6" />
        </svg>
      </div>
      <p class="empty-title">购物车还是空的</p>
      <p class="empty-desc">去挑几件喜欢的商品，结算时还可以选择优惠券。</p>
      <router-link to="/products" class="btn-primary empty-btn">去逛逛</router-link>
    </div>

    <div v-else class="cart-content">
      <div class="cart-items">
        <div v-for="item in cartItems" :key="item.id" class="cart-item">
          <div class="item-info">
            <div class="item-name-row">
              <h3>{{ item.productName }}</h3>
              <p v-if="item.productAttr" class="item-attr">{{ item.productAttr }}</p>
            </div>
            <p class="item-unit-price">&yen;{{ item.productPrice }}</p>
          </div>
          <div class="item-actions">
            <div class="qty-control">
              <button @click="handleQuantityChange(item, -1)" :disabled="item.quantity <= 1">-</button>
              <span>{{ item.quantity }}</span>
              <button @click="handleQuantityChange(item, 1)">+</button>
            </div>
            <p class="item-subtotal">&yen;{{ item.productPrice * item.quantity }}</p>
            <button class="delete-btn" @click="handleDelete(item)">删除</button>
          </div>
        </div>
      </div>

      <div class="cart-summary">
        <div class="summary-left">
          <span class="summary-count">共 {{ totalCount }} 件商品</span>
        </div>
        <div class="summary-right">
          <div class="summary-amounts">
            <div class="amount-row">
              <span>商品总额</span>
              <span>&yen;{{ totalAmount }}</span>
            </div>
            <div class="amount-row muted">
              <span>优惠</span>
              <span>结算页选择</span>
            </div>
          </div>
          <div class="summary-total">
            <span class="total-label">预计金额</span>
            <span class="total-amount">&yen;{{ totalAmount }}</span>
          </div>
          <button class="btn-primary checkout-btn" @click="goCheckout">去结算</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cart-page {
  padding-top: 20px;
  padding-bottom: 20px;
}

.cart-page h1 {
  font-size: 22px;
  margin-bottom: 16px;
}

.cart-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 24px;
  font-size: 13px;
}

.step {
  color: var(--color-text-light);
}

.step.active {
  color: var(--color-primary);
  font-weight: 600;
}

.step-arrow {
  color: var(--color-text-light);
  font-size: 16px;
}

.page-msg {
  font-size: 14px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
}

.page-msg.success {
  background: #f0f9eb;
  color: var(--color-success);
}

.page-msg.error {
  background: #fef2f2;
  color: var(--color-danger);
}

.loading-state {
  padding: 40px 0;
  color: var(--color-text-muted);
}

.empty-cart {
  text-align: center;
  padding: 60px 20px;
}

.empty-icon {
  margin-bottom: 16px;
}

.empty-title {
  font-size: 16px;
  color: var(--color-text);
  margin-bottom: 8px;
}

.empty-desc {
  font-size: 14px;
  color: var(--color-text-muted);
  margin-bottom: 24px;
}

.empty-btn {
  display: inline-block;
  text-decoration: none;
}

.cart-content {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.cart-items {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cart-item {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  padding: 16px 20px;
  box-shadow: var(--shadow-sm);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.item-info h3 {
  font-size: 15px;
  color: var(--color-text);
  margin-bottom: 4px;
}

.item-attr {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}

.item-unit-price {
  color: var(--color-text-muted);
  font-size: 13px;
}

.item-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.qty-control {
  display: flex;
  align-items: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.qty-control button {
  width: 30px;
  height: 30px;
  border: none;
  background: var(--color-bg);
  cursor: pointer;
  font-size: 14px;
  color: var(--color-text-body);
}

.qty-control button:disabled {
  color: #ccc;
  cursor: not-allowed;
}

.qty-control span {
  min-width: 28px;
  text-align: center;
  font-size: 14px;
}

.item-subtotal {
  min-width: 70px;
  text-align: right;
  font-size: 15px;
  color: var(--color-primary);
  font-weight: 600;
}

.delete-btn {
  background: none;
  border: none;
  color: var(--color-text-light);
  font-size: 13px;
  cursor: pointer;
}

.delete-btn:hover {
  color: var(--color-danger);
}

.cart-summary {
  width: 300px;
  flex-shrink: 0;
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  padding: 20px;
  box-shadow: var(--shadow-sm);
  position: sticky;
  top: 20px;
}

.summary-count {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: 16px;
  display: block;
}

.summary-amounts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: var(--color-text-body);
}

.amount-row.muted {
  color: var(--color-text-muted);
}

.summary-total {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 20px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
}

.total-label {
  font-size: 14px;
  color: var(--color-text);
  font-weight: 500;
}

.total-amount {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-primary);
}

.checkout-btn {
  width: 100%;
  padding: 12px;
  font-size: 16px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .cart-content {
    flex-direction: column;
  }

  .cart-summary {
    width: 100%;
    position: static;
  }

  .cart-item {
    flex-direction: column;
    align-items: flex-start;
  }

  .item-actions {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
