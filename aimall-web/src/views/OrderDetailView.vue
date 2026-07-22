<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { sendAiMessageStream } from "../api/aiApi";
import { cancelOrder, confirmReceiveOrder, fetchOrderById, type Order } from "../api/orderApi";
import { createAlipayPayment, fetchPayStatus, type PayStatus } from "../api/payApi";
import { applyReturn, fetchLatestReturnByOrder, type ReturnApply } from "../api/returnApi";

const route = useRoute();
const order = ref<Order | null>(null);
const payStatus = ref<PayStatus | null>(null);
const latestReturn = ref<ReturnApply | null>(null);
const loading = ref(true);
const acting = ref(false);
const returnSubmitting = ref(false);
const msg = ref("");
const returnReason = ref("商品与预期不符");
const returnDescription = ref("");

const orderId = computed(() => Number(route.params.id));

const timelineItems = computed(() => {
  if (!order.value) return [];
  return [
    { label: "订单创建", time: order.value.createTime || "", done: !!order.value.createTime },
    { label: "支付完成", time: order.value.paymentTime || "", done: !!order.value.paymentTime },
    { label: "商家发货", time: order.value.deliveryTime || "", done: !!order.value.deliveryTime },
    { label: "确认收货", time: order.value.receiveTime || "", done: !!order.value.receiveTime },
  ];
});

const fullAddress = computed(() => {
  if (!order.value) return "";
  return [
    order.value.receiverProvince,
    order.value.receiverCity,
    order.value.receiverRegion,
    order.value.receiverDetailAddress,
  ]
    .filter(Boolean)
    .join("");
});

const canApplyReturn = computed(() => {
  if (!order.value) return false;
  const allowed = ["WAIT_SHIP", "SHIPPED", "COMPLETED"];
  const blocked = ["PENDING_REVIEW", "APPROVED", "REFUNDING", "REFUNDED"];
  return allowed.includes(order.value.status) && !blocked.includes(latestReturn.value?.status || "");
});

async function loadOrder(id: number) {
  loading.value = true;
  msg.value = "";
  order.value = null;
  payStatus.value = null;
  latestReturn.value = null;

  try {
    const [orderRes, payRes, returnRes] = await Promise.allSettled([
      fetchOrderById(id),
      fetchPayStatus(id),
      fetchLatestReturnByOrder(id),
    ]);

    if (orderRes.status === "fulfilled" && orderRes.value.data.code === 0) {
      order.value = orderRes.value.data.data;
    } else {
      msg.value = "订单加载失败";
    }

    if (payRes.status === "fulfilled" && payRes.value.data.code === 0) {
      payStatus.value = payRes.value.data.data;
    }

    if (returnRes.status === "fulfilled" && returnRes.value.data.code === 0) {
      latestReturn.value = returnRes.value.data.data;
    }
  } catch {
    msg.value = "订单加载失败";
  } finally {
    loading.value = false;
  }
}

async function handleAlipayPay() {
  if (!order.value || acting.value) return;
  acting.value = true;
  msg.value = "";
  try {
    const res = await createAlipayPayment(order.value.orderId);
    if (res.data.code === 0 && res.data.data?.form) {
      const container = document.createElement("div");
      container.innerHTML = res.data.data.form;
      document.body.appendChild(container);
      const form = container.querySelector("form");
      if (!form) throw new Error("支付表单生成失败");
      form.submit();
    } else {
      msg.value = res.data.message || "支付创建失败";
    }
  } catch {
    msg.value = "支付创建失败，请稍后重试";
  } finally {
    acting.value = false;
  }
}

async function handleCancelOrder() {
  if (!order.value || acting.value) return;
  acting.value = true;
  msg.value = "";

  try {
    const res = await cancelOrder(order.value.orderId);
    if (res.data.code === 0) {
      await loadOrder(orderId.value);
    } else {
      msg.value = res.data.message || "取消订单失败";
    }
  } catch {
    msg.value = "取消订单失败";
  } finally {
    acting.value = false;
  }
}

async function handleConfirmReceive() {
  if (!order.value || acting.value) return;
  acting.value = true;
  msg.value = "";

  try {
    const res = await confirmReceiveOrder(order.value.orderId);
    if (res.data.code === 0) {
      await loadOrder(orderId.value);
    } else {
      msg.value = res.data.message || "确认收货失败";
    }
  } catch {
    msg.value = "确认收货失败";
  } finally {
    acting.value = false;
  }
}

async function handleApplyReturn() {
  if (!order.value || !canApplyReturn.value || returnSubmitting.value) return;
  returnSubmitting.value = true;
  msg.value = "";

  try {
    const res = await applyReturn({
      orderId: order.value.orderId,
      reason: returnReason.value,
      description: returnDescription.value.trim(),
    });

    if (res.data.code === 0) {
      latestReturn.value = res.data.data;
      returnDescription.value = "";
      msg.value = "售后申请已提交";
    } else {
      msg.value = res.data.message || "售后申请提交失败";
    }
  } catch {
    msg.value = "售后申请提交失败";
  } finally {
    returnSubmitting.value = false;
  }
}

function askAiAboutOrder() {
  if (!order.value) return;

  void sendAiMessageStream(
    {
      message: `帮我解释一下订单 ${order.value.orderNo} 的当前状态`,
      sessionId: `order-detail-ai-${order.value.orderId}`,
      pageContext: {
        pageType: "ORDER_DETAIL",
        orderId: order.value.orderId,
      },
    },
    () => {}
  );
}

onMounted(() => {
  void loadOrder(orderId.value);
});

watch(orderId, (id) => {
  void loadOrder(id);
});
</script>

<template>
  <div v-if="loading" class="order-detail-page">
    <div class="loading-state">加载中...</div>
  </div>

  <div v-else-if="!order" class="order-detail-page">
    <div class="not-found">
      <p>{{ msg || "订单不存在" }}</p>
      <router-link to="/orders">返回订单列表</router-link>
    </div>
  </div>

  <div v-else class="order-detail-page">
    <router-link to="/orders" class="back-link">&larr; 返回订单列表</router-link>

    <div class="order-header-bar">
      <div class="header-left">
        <h1>订单详情</h1>
        <span class="order-no">订单号：{{ order.orderNo }}</span>
      </div>
      <span :class="['order-status', order.status]">{{ order.statusText }}</span>
    </div>

    <p v-if="msg" class="msg">{{ msg }}</p>

    <div class="action-row">
      <button v-if="order.status === 'WAIT_PAY'" class="primary" :disabled="acting" @click="handleAlipayPay">
        {{ acting ? "处理中..." : "支付宝沙箱支付" }}
      </button>
      <button v-if="order.status === 'WAIT_PAY'" class="secondary" :disabled="acting" @click="handleCancelOrder">
        取消订单
      </button>
      <button v-if="order.status === 'SHIPPED'" class="primary" :disabled="acting" @click="handleConfirmReceive">
        确认收货
      </button>
      <router-link v-if="latestReturn" :to="`/returns/${latestReturn.id}`" class="outline-link">
        查看售后
      </router-link>
    </div>

    <div class="info-section">
      <h2>订单进度</h2>
      <div class="timeline">
        <div v-for="item in timelineItems" :key="item.label" class="timeline-item" :class="{ done: item.done }">
          <span class="timeline-dot"></span>
          <div class="timeline-content">
            <strong>{{ item.label }}</strong>
            <span>{{ item.time || "待处理" }}</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="order.createTime" class="info-section">
      <h2>订单信息</h2>
      <p><strong>下单时间：</strong>{{ order.createTime }}</p>
      <p v-if="order.receiverName"><strong>收货人：</strong>{{ order.receiverName }}</p>
      <p v-if="order.receiverPhone"><strong>联系电话：</strong>{{ order.receiverPhone }}</p>
      <p v-if="fullAddress"><strong>收货地址：</strong>{{ fullAddress }}</p>
      <p v-if="order.deliveryCompany"><strong>物流公司：</strong>{{ order.deliveryCompany }}</p>
      <p v-if="order.deliverySn"><strong>物流单号：</strong>{{ order.deliverySn }}</p>
      <p v-if="payStatus"><strong>支付状态：</strong>{{ payStatus.payStatus }}</p>
      <p v-if="payStatus?.transactionNo"><strong>交易流水：</strong>{{ payStatus.transactionNo }}</p>
    </div>

    <div class="info-section">
      <h2>商品明细</h2>
      <table class="item-table">
        <thead>
          <tr>
            <th>商品</th>
            <th>单价</th>
            <th>数量</th>
            <th>实付小计</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(item, index) in order.items" :key="index">
            <td>
              <span class="item-name">{{ item.productName }}</span>
              <span v-if="item.productAttr" class="item-attr">{{ item.productAttr }}</span>
            </td>
            <td>&yen;{{ item.price }}</td>
            <td>{{ item.quantity }}</td>
            <td class="item-total">&yen;{{ item.realAmount ?? item.price * item.quantity }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="info-section">
      <h2>金额明细</h2>
      <div class="amount-rows">
        <div class="amount-row">
          <span>商品金额</span>
          <span>&yen;{{ order.totalAmount }}</span>
        </div>
        <div v-if="order.freightAmount !== undefined" class="amount-row">
          <span>运费</span>
          <span>&yen;{{ order.freightAmount }}</span>
        </div>
        <div v-if="order.couponAmount" class="amount-row discount">
          <span>优惠抵扣</span>
          <span>-&yen;{{ order.couponAmount }}</span>
        </div>
        <div v-if="order.payAmount !== undefined" class="amount-row total">
          <span>实付金额</span>
          <span>&yen;{{ order.payAmount }}</span>
        </div>
        <div v-else class="amount-row total">
          <span>应付金额</span>
          <span>&yen;{{ order.totalAmount }}</span>
        </div>
      </div>
    </div>

    <div class="info-section">
      <h2>售后服务</h2>
      <div v-if="latestReturn" class="return-summary">
        <p><strong>当前状态：</strong>{{ latestReturn.statusText }}</p>
        <p><strong>申请原因：</strong>{{ latestReturn.reason }}</p>
        <p><strong>退款金额：</strong>&yen;{{ latestReturn.returnAmount }}</p>
        <p v-if="latestReturn.handleNote"><strong>处理说明：</strong>{{ latestReturn.handleNote }}</p>
        <router-link :to="`/returns/${latestReturn.id}`" class="detail-link">查看售后详情</router-link>
      </div>
      <div v-else-if="canApplyReturn" class="return-form">
        <label>
          <span>申请原因</span>
          <select v-model="returnReason">
            <option>商品与预期不符</option>
            <option>商品存在质量问题</option>
            <option>商家发错货</option>
            <option>不想要了</option>
          </select>
        </label>
        <label>
          <span>补充说明</span>
          <textarea
            v-model="returnDescription"
            rows="4"
            placeholder="可填写问题描述、退款说明等"
          ></textarea>
        </label>
        <button class="primary" :disabled="returnSubmitting" @click="handleApplyReturn">
          {{ returnSubmitting ? "提交中..." : "提交售后申请" }}
        </button>
      </div>
      <div v-else class="return-tip">当前订单状态暂不支持申请售后。</div>
    </div>

    <div class="ai-entry" @click="askAiAboutOrder">
      <p>对订单有疑问？问问 AI 购物助手</p>
    </div>
  </div>
</template>

<style scoped>
.order-detail-page {
  padding-top: 20px;
}

.back-link {
  display: inline-block;
  margin-bottom: 16px;
  font-size: 14px;
  color: var(--color-text-muted);
}

.loading-state,
.not-found {
  text-align: center;
  padding: 60px 0;
  color: var(--color-text-muted);
}

.order-header-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  gap: 12px;
}

.header-left h1 {
  font-size: 22px;
  margin-bottom: 4px;
  color: var(--color-text);
}

.order-no {
  font-size: 13px;
  color: var(--color-text-muted);
}

.msg {
  margin-bottom: 16px;
  color: #dc2626;
}

.action-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.secondary,
.outline-link {
  background: #fff;
  border: 1px solid #d1d5db;
  color: #374151;
}

.outline-link,
.detail-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  border-radius: var(--radius-sm);
  text-decoration: none;
}

.order-status {
  font-size: 15px;
  font-weight: 600;
  padding: 6px 16px;
  border-radius: var(--radius-sm);
}

.order-status.WAIT_PAY {
  color: var(--color-danger);
  background: #fef2f2;
}

.order-status.WAIT_SHIP {
  color: var(--color-warning);
  background: #fffbeb;
}

.order-status.SHIPPED {
  color: #3b82f6;
  background: #eff6ff;
}

.order-status.COMPLETED {
  color: var(--color-success);
  background: #f0f9eb;
}

.order-status.CLOSED {
  color: #6b7280;
  background: #f3f4f6;
}

.info-section {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  padding: 20px 24px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-sm);
}

.info-section h2 {
  font-size: 16px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text);
}

.info-section p {
  font-size: 14px;
  margin-bottom: 6px;
  color: var(--color-text-body);
}

.timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.timeline-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  opacity: 0.55;
}

.timeline-item.done {
  opacity: 1;
}

.timeline-dot {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  border-radius: 50%;
  background: #d1d5db;
  flex: 0 0 auto;
}

.timeline-item.done .timeline-dot {
  background: #2563eb;
}

.timeline-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.timeline-content strong {
  font-size: 14px;
  color: var(--color-text);
}

.timeline-content span {
  font-size: 13px;
  color: var(--color-text-muted);
}

.item-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.item-table th,
.item-table td {
  text-align: left;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border-light);
}

.item-table th {
  background: var(--color-bg);
  font-weight: 600;
  color: var(--color-text-muted);
  font-size: 13px;
}

.item-name {
  display: block;
  font-weight: 500;
  color: var(--color-text);
}

.item-attr {
  display: block;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 2px;
}

.item-total {
  color: var(--color-primary);
  font-weight: 500;
}

.amount-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: var(--color-text-body);
}

.amount-row.discount {
  color: #16a34a;
}

.amount-row.total {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-primary);
  padding-top: 8px;
  border-top: 1px solid var(--color-border);
}

.return-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.return-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: var(--color-text-body);
}

.return-form select,
.return-form textarea {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  font: inherit;
  background: #fff;
}

.return-summary,
.return-tip {
  color: var(--color-text-body);
}

.ai-entry {
  text-align: center;
  padding: 16px;
  background: var(--color-primary-light);
  border-radius: var(--radius-card);
  margin-bottom: 24px;
  cursor: pointer;
  border: 1px solid var(--color-primary-border);
}

.ai-entry p {
  font-size: 14px;
  color: var(--color-primary);
  font-weight: 500;
}

@media (max-width: 768px) {
  .order-header-bar {
    flex-direction: column;
    align-items: flex-start;
  }

  .item-table {
    display: block;
    overflow-x: auto;
  }
}
</style>
