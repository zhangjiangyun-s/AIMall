<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchAddresses, type Address } from "../api/addressApi";
import { fetchMyCoupons, type OwnedCoupon } from "../api/couponApi";
import { fetchOrders, type Order } from "../api/orderApi";
import { fetchReturnList, type ReturnApply } from "../api/returnApi";
import { cancelAccount, changePassword, consentPrivacy, fetchLoginHistory, fetchSecurityDevices, freezeAccount, revokeSecurityDevice, type LoginHistory, type SecurityDevice } from "../api/userApi";
import { fetchBrowseHistory, fetchFavorites, fetchRecommendations, type ProductInteractionItem } from "../api/productInteractionApi";

const loading = ref(true);
const orders = ref<Order[]>([]);
const addresses = ref<Address[]>([]);
const coupons = ref<OwnedCoupon[]>([]);
const returns = ref<ReturnApply[]>([]);
const userNickname = ref("用户");
const loginHistory = ref<LoginHistory[]>([]);
const devices = ref<SecurityDevice[]>([]);
const securityMsg = ref("");
const favorites = ref<ProductInteractionItem[]>([]);
const browseHistory = ref<ProductInteractionItem[]>([]);
const recommendations = ref<ProductInteractionItem[]>([]);

const waitPayCount = computed(() => orders.value.filter((item) => item.status === "WAIT_PAY").length);
const waitShipCount = computed(() => orders.value.filter((item) => item.status === "WAIT_SHIP").length);
const shippedCount = computed(() => orders.value.filter((item) => item.status === "SHIPPED").length);
const usableCouponCount = computed(() => coupons.value.filter((item) => item.status === 0 && item.active).length);
const defaultAddress = computed(() => addresses.value.find((item) => item.defaultStatus === 1) || addresses.value[0]);

function loadUserInfo() {
  try {
    const raw = localStorage.getItem("userInfo");
    if (raw) {
      const info = JSON.parse(raw);
      userNickname.value = info.nickname || info.username || "用户";
    }
  } catch {
    userNickname.value = "用户";
  }
}

async function loadData() {
  loading.value = true;
  loadUserInfo();
  try {
    const [orderRes, addressRes, couponRes, returnRes, historyRes, deviceRes, favoriteRes, browseRes, recommendationRes] = await Promise.allSettled([
      fetchOrders(),
      fetchAddresses(),
      fetchMyCoupons(),
      fetchReturnList(),
      fetchLoginHistory(),
      fetchSecurityDevices(),
      fetchFavorites(),
      fetchBrowseHistory(),
      fetchRecommendations(),
    ]);

    if (orderRes.status === "fulfilled" && orderRes.value.data.code === 0) {
      orders.value = orderRes.value.data.data;
    }
    if (addressRes.status === "fulfilled" && addressRes.value.data.code === 0) {
      addresses.value = addressRes.value.data.data;
    }
    if (couponRes.status === "fulfilled" && couponRes.value.data.code === 0) {
      coupons.value = couponRes.value.data.data;
    }
    if (returnRes.status === "fulfilled" && returnRes.value.data.code === 0) {
      returns.value = returnRes.value.data.data;
    }
    if (historyRes.status === "fulfilled" && historyRes.value.data.code === 0) loginHistory.value = historyRes.value.data.data;
    if (deviceRes.status === "fulfilled" && deviceRes.value.data.code === 0) devices.value = deviceRes.value.data.data;
    if (favoriteRes.status === "fulfilled" && favoriteRes.value.data.code === 0) favorites.value = favoriteRes.value.data.data;
    if (browseRes.status === "fulfilled" && browseRes.value.data.code === 0) browseHistory.value = browseRes.value.data.data;
    if (recommendationRes.status === "fulfilled" && recommendationRes.value.data.code === 0) recommendations.value = recommendationRes.value.data.data;
  } finally {
    loading.value = false;
  }
}

async function handleChangePassword() {
  const oldPassword = window.prompt("请输入当前密码") || "";
  const newPassword = window.prompt("请输入新密码（至少 12 位）") || "";
  if (!oldPassword || !newPassword) return;
  try { await changePassword(oldPassword, newPassword); securityMsg.value = "密码已修改，请重新登录"; localStorage.removeItem("token"); window.location.href = "/login"; }
  catch (error) { securityMsg.value = error instanceof Error ? error.message : "修改密码失败"; }
}

async function handleRevokeDevice(id: number) {
  if (!window.confirm("确定撤销这个登录设备吗？")) return;
  try { await revokeSecurityDevice(id); devices.value = devices.value.map((item) => item.id === id ? { ...item, revoked: 1 } : item); }
  catch (error) { securityMsg.value = error instanceof Error ? error.message : "设备撤销失败"; }
}

async function handleConsent() {
  try { await consentPrivacy("2026-01"); securityMsg.value = "隐私授权已记录"; }
  catch (error) { securityMsg.value = error instanceof Error ? error.message : "授权失败"; }
}

async function handleFreeze() {
  if (!window.confirm("冻结后将退出当前账号，确认继续吗？")) return;
  try { await freezeAccount(); localStorage.removeItem("token"); localStorage.removeItem("userInfo"); window.location.href = "/login"; }
  catch (error) { securityMsg.value = error instanceof Error ? error.message : "冻结账号失败"; }
}

async function handleCancelAccount() {
  const password = window.prompt("请输入密码确认注销账号") || "";
  if (!password || !window.confirm("注销后账号将不可恢复，确认继续吗？")) return;
  try { await cancelAccount(password); localStorage.removeItem("token"); localStorage.removeItem("userInfo"); window.location.href = "/login"; }
  catch (error) { securityMsg.value = error instanceof Error ? error.message : "注销账号失败"; }
}

onMounted(loadData);
</script>

<template>
  <div class="account-page page-container">
    <div class="account-hero">
      <div>
        <span class="eyebrow">个人中心</span>
        <h1>{{ userNickname }}</h1>
        <p>查看订单进度、收货地址、优惠券和售后服务。</p>
      </div>
      <router-link to="/products" class="hero-link">继续购物</router-link>
    </div>

    <div v-if="loading" class="placeholder">加载中...</div>

    <template v-else>
      <section class="stats-grid">
        <router-link to="/orders" class="stat-card">
          <span>全部订单</span>
          <strong>{{ orders.length }}</strong>
        </router-link>
        <router-link to="/orders" class="stat-card">
          <span>待支付</span>
          <strong>{{ waitPayCount }}</strong>
        </router-link>
        <router-link to="/orders" class="stat-card">
          <span>待发货</span>
          <strong>{{ waitShipCount }}</strong>
        </router-link>
        <router-link to="/orders" class="stat-card">
          <span>待收货</span>
          <strong>{{ shippedCount }}</strong>
        </router-link>
      </section>

      <section class="quick-grid">
        <router-link to="/account/address" class="quick-card">
          <div>
            <h2>收货地址</h2>
            <p v-if="defaultAddress">{{ defaultAddress.name }}，{{ defaultAddress.fullAddress }}</p>
            <p v-else>还没有收货地址</p>
          </div>
          <span>{{ addresses.length }} 个</span>
        </router-link>

        <router-link to="/account/coupons" class="quick-card">
          <div>
            <h2>我的优惠券</h2>
            <p>结算时可选择符合条件的优惠券。</p>
          </div>
          <span>{{ usableCouponCount }} 张可用</span>
        </router-link>

        <router-link to="/returns" class="quick-card">
          <div>
            <h2>售后服务</h2>
            <p>查看退款、退货和售后处理进度。</p>
          </div>
          <span>{{ returns.length }} 条</span>
        </router-link>
      </section>

      <section class="security-section">
        <div class="section-head"><h2>账号安全</h2><span class="security-message">{{ securityMsg }}</span></div>
        <div class="security-actions">
          <button @click="handleChangePassword">修改密码</button>
          <button @click="handleConsent">确认隐私授权</button>
          <button class="danger" @click="handleFreeze">冻结账号</button>
          <button class="danger" @click="handleCancelAccount">注销账号</button>
        </div>
        <div class="security-columns">
          <div><h3>登录设备</h3><p v-if="devices.length === 0" class="muted">暂无设备记录</p><div v-for="device in devices" :key="device.id" class="security-row"><span>{{ device.deviceName || "未知设备" }} · {{ device.lastIp || "-" }}</span><button v-if="!device.revoked" @click="handleRevokeDevice(device.id)">撤销</button><em v-else>已撤销</em></div></div>
          <div><h3>最近登录</h3><p v-if="loginHistory.length === 0" class="muted">暂无登录记录</p><div v-for="item in loginHistory.slice(0, 5)" :key="item.id" class="security-row"><span>{{ item.clientIp || "-" }} · {{ item.createTime || "-" }}</span><em :class="item.success ? 'ok' : 'bad'">{{ item.success ? "成功" : "失败" }}</em></div></div>
        </div>
      </section>

      <section class="interaction-section">
        <div class="interaction-column"><h2>我的收藏</h2><p v-if="favorites.length === 0" class="muted">暂无收藏商品</p><router-link v-for="item in favorites.slice(0, 6)" :key="item.productId || item.id" :to="`/products/${item.productId}`" class="interaction-row">商品 #{{ item.productId }}<span>查看</span></router-link></div>
        <div class="interaction-column"><h2>浏览历史</h2><p v-if="browseHistory.length === 0" class="muted">暂无浏览记录</p><router-link v-for="item in browseHistory.slice(0, 6)" :key="item.productId || item.id" :to="`/products/${item.productId}`" class="interaction-row">商品 #{{ item.productId }}<span>{{ item.lastViewTime || "查看" }}</span></router-link></div>
        <div class="interaction-column"><h2>为你推荐</h2><p v-if="recommendations.length === 0" class="muted">暂无推荐</p><router-link v-for="item in recommendations.slice(0, 6)" :key="item.product?.id || item.productId || item.id" :to="`/products/${item.product?.id || item.productId}`" class="interaction-row">{{ item.product?.name || `商品 #${item.productId}` }}<span>{{ item.reason || "精选推荐" }}</span></router-link></div>
      </section>

      <section class="recent-section">
        <div class="section-head">
          <h2>最近订单</h2>
          <router-link to="/orders">查看全部</router-link>
        </div>
        <div v-if="orders.length === 0" class="empty">暂无订单</div>
        <div v-else class="recent-list">
          <router-link v-for="order in orders.slice(0, 3)" :key="order.orderId" :to="`/orders/${order.orderId}`" class="order-row">
            <div>
              <strong>{{ order.orderNo }}</strong>
              <p>{{ order.items?.[0]?.productName || "商品订单" }}</p>
            </div>
            <div class="order-side">
              <span>{{ order.statusText }}</span>
              <strong>&yen;{{ order.payAmount ?? order.totalAmount }}</strong>
            </div>
          </router-link>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.account-page {
  padding-top: 20px;
  padding-bottom: 24px;
}

.account-hero {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
  background: #fff;
  border-radius: var(--radius-card);
  padding: 24px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 20px;
}

.eyebrow {
  display: block;
  color: var(--color-primary);
  font-size: 13px;
  margin-bottom: 6px;
}

.account-hero h1 {
  font-size: 26px;
  margin-bottom: 6px;
}

.account-hero p {
  color: var(--color-text-muted);
}

.hero-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 16px;
  color: #fff;
  background: var(--color-primary);
  border-radius: var(--radius-sm);
  text-decoration: none;
  flex-shrink: 0;
}

.placeholder,
.empty {
  padding: 42px 0;
  text-align: center;
  color: var(--color-text-muted);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.stat-card,
.quick-card,
.recent-section {
  background: #fff;
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-sm);
}

.security-section { background: #fff; border-radius: var(--radius-card); box-shadow: var(--shadow-sm); padding: 20px; margin-bottom: 20px; }
.interaction-section { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 20px; }
.interaction-column { background: #fff; border-radius: var(--radius-card); box-shadow: var(--shadow-sm); padding: 18px; min-width: 0; }
.interaction-column h2 { font-size: 16px; margin-bottom: 10px; }
.interaction-row { display: flex; justify-content: space-between; gap: 10px; padding: 9px 0; border-top: 1px solid var(--color-border-light); color: var(--color-text); text-decoration: none; font-size: 13px; }
.interaction-row span { color: var(--color-primary); text-align: right; }
.security-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 18px; }
.security-actions button, .security-row button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: #fff; padding: 8px 12px; cursor: pointer; color: var(--color-text); }
.security-actions button.danger { color: var(--color-danger); border-color: #fecaca; }
.security-message { color: var(--color-primary); font-size: 13px; }
.security-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.security-columns h3 { font-size: 15px; margin-bottom: 8px; }
.security-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 9px 0; border-top: 1px solid var(--color-border-light); font-size: 13px; }
.security-row em { font-style: normal; color: var(--color-text-muted); white-space: nowrap; }
.security-row .ok { color: var(--color-success); }.security-row .bad { color: var(--color-danger); }.muted { color: var(--color-text-muted); }

.stat-card {
  padding: 18px;
  text-decoration: none;
  color: var(--color-text);
}

.stat-card span {
  display: block;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}

.stat-card strong {
  font-size: 28px;
  color: var(--color-primary);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.quick-card {
  padding: 18px;
  display: flex;
  justify-content: space-between;
  gap: 14px;
  text-decoration: none;
  color: var(--color-text);
}

.quick-card h2 {
  font-size: 16px;
  margin-bottom: 8px;
}

.quick-card p {
  color: var(--color-text-muted);
  line-height: 1.6;
}

.quick-card > span {
  color: var(--color-primary);
  font-weight: 600;
  white-space: nowrap;
}

.recent-section {
  padding: 20px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-head h2 {
  font-size: 18px;
}

.section-head a {
  color: var(--color-primary);
  text-decoration: none;
  font-size: 14px;
}

.recent-list {
  display: grid;
  gap: 10px;
}

.order-row {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 0;
  border-top: 1px solid var(--color-border-light);
  text-decoration: none;
  color: var(--color-text);
}

.order-row p {
  margin-top: 4px;
  color: var(--color-text-muted);
}

.order-side {
  text-align: right;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.order-side span {
  color: var(--color-primary);
  font-size: 13px;
}

@media (max-width: 860px) {
  .account-hero {
    flex-direction: column;
    align-items: flex-start;
  }

  .stats-grid,
  .quick-grid,
  .security-columns,
  .interaction-section {
    grid-template-columns: 1fr;
  }
}
</style>
