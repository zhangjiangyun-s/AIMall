<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { addReturnEvidence, cancelReturn, fetchReturnById, submitReturnLogistics, type ReturnApply } from "../api/returnApi";

const route = useRoute();
const loading = ref(true);
const acting = ref(false);
const msg = ref("");
const detail = ref<ReturnApply | null>(null);
const evidenceType = ref<"IMAGE" | "VIDEO">("IMAGE");
const evidenceUrl = ref("");
const carrier = ref("");
const trackingNo = ref("");

const returnId = computed(() => Number(route.params.id));

async function loadDetail(id: number) {
  loading.value = true;
  msg.value = "";
  try {
    const response = await fetchReturnById(id);
    if (response.data.code === 0) {
      detail.value = response.data.data;
    } else {
      msg.value = response.data.message || "售后详情加载失败";
    }
  } catch {
    msg.value = "售后详情加载失败";
  } finally {
    loading.value = false;
  }
}

async function handleCancel() {
  if (!detail.value || detail.value.status !== "APPLIED" || acting.value) return;
  acting.value = true;
  try {
    const response = await cancelReturn(detail.value.id);
    if (response.data.code === 0) {
      await loadDetail(returnId.value);
    } else {
      msg.value = response.data.message || "取消售后失败";
    }
  } catch {
    msg.value = "取消售后失败";
  } finally {
    acting.value = false;
  }
}

async function handleEvidence() {
  if (!detail.value || !evidenceUrl.value.trim()) return;
  acting.value = true;
  try { await addReturnEvidence(detail.value.id, evidenceType.value, evidenceUrl.value.trim()); evidenceUrl.value = ""; await loadDetail(returnId.value); }
  catch { msg.value = "凭证提交失败"; } finally { acting.value = false; }
}

async function handleLogistics() {
  if (!detail.value || !carrier.value.trim() || !trackingNo.value.trim()) return;
  acting.value = true;
  try { await submitReturnLogistics(detail.value.id, carrier.value.trim(), trackingNo.value.trim()); await loadDetail(returnId.value); }
  catch { msg.value = "退货物流提交失败"; } finally { acting.value = false; }
}

onMounted(() => loadDetail(returnId.value));
watch(returnId, (id) => loadDetail(id));
</script>

<template>
  <div v-if="loading" class="return-detail-page">
    <div class="placeholder">加载中...</div>
  </div>

  <div v-else-if="!detail" class="return-detail-page">
    <div class="placeholder">{{ msg || "售后申请不存在" }}</div>
  </div>

  <div v-else class="return-detail-page">
    <router-link to="/returns" class="back-link">&larr; 返回售后列表</router-link>
    <div class="detail-card">
      <div class="detail-header">
        <div>
          <h1>售后详情</h1>
          <p>售后单号：{{ detail.id }}</p>
          <p>关联订单：{{ detail.orderNo }}</p>
        </div>
        <span :class="['status-badge', detail.status]">{{ detail.statusText }}</span>
      </div>

      <p v-if="msg" class="error">{{ msg }}</p>

      <div class="detail-grid">
        <div><strong>申请原因：</strong>{{ detail.reason }}</div>
        <div><strong>退款金额：</strong>&yen;{{ detail.returnAmount }}</div>
        <div><strong>提交时间：</strong>{{ detail.createTime || "-" }}</div>
        <div><strong>处理时间：</strong>{{ detail.handleTime || "-" }}</div>
        <div class="full"><strong>补充说明：</strong>{{ detail.description || "-" }}</div>
          <div class="full"><strong>处理说明：</strong>{{ detail.handleNote || "-" }}</div>
          <div><strong>退货物流：</strong>{{ detail.returnCarrier || "-" }} {{ detail.returnTrackingNo || "" }}</div>
          <div><strong>验货结果：</strong>{{ detail.inspectionResult || "-" }}</div>
          <div><strong>SLA：</strong>{{ detail.slaDeadline || "-" }}{{ detail.slaOverdue ? "（已超时）" : "" }}</div>
      </div>

      <div v-if="detail.items?.length" class="items-section">
        <h2>关联商品</h2>
        <div v-for="item in detail.items" :key="`${item.productId}-${item.productName}`" class="item-row">
          <div>
            <strong>{{ item.productName }}</strong>
            <p v-if="item.productAttr">{{ item.productAttr }}</p>
          </div>
          <span>x{{ item.quantity }}</span>
        </div>
      </div>

      <div class="actions">
        <button v-if="detail.status === 'APPLIED'" class="secondary" :disabled="acting" @click="handleCancel">
          {{ acting ? "处理中..." : "取消申请" }}
        </button>
        <router-link :to="`/orders/${detail.orderId}`" class="primary-link">查看原订单</router-link>
      </div>
    </div>
      <section class="detail-card supplemental">
        <h2>售后凭证与退货物流</h2>
        <div v-if="detail.evidence?.length" class="evidence-list"><a v-for="item in detail.evidence" :key="item.id" :href="item.mediaUrl" target="_blank" rel="noreferrer">{{ item.mediaType }}：{{ item.mediaUrl }}</a></div>
        <p v-else class="muted">暂无凭证</p>
        <div class="form-row"><select v-model="evidenceType"><option value="IMAGE">图片</option><option value="VIDEO">视频</option></select><input v-model="evidenceUrl" placeholder="凭证 URL" /><button :disabled="acting" @click="handleEvidence">提交凭证</button></div>
        <div v-if="detail.status === 'APPROVED' && detail.type !== 'ONLY_REFUND'" class="form-row"><input v-model="carrier" placeholder="物流公司" /><input v-model="trackingNo" placeholder="运单号" /><button :disabled="acting" @click="handleLogistics">提交退货物流</button></div>
        <div v-if="detail.statusEvents?.length" class="events"><h3>状态审计</h3><div v-for="event in detail.statusEvents" :key="event.id">{{ event.eventType || "状态变更" }} · {{ event.note || "" }} · {{ event.createTime || "" }}</div></div>
      </section>
  </div>
</template>

<style scoped>
.return-detail-page {
  padding-top: 20px;
}

.placeholder {
  text-align: center;
  padding: 60px 0;
  color: var(--color-text-muted);
}

.back-link {
  display: inline-flex;
  margin-bottom: 16px;
  color: var(--color-text-muted);
  text-decoration: none;
}

.detail-card {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-sm);
  padding: 24px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.detail-header h1 {
  margin: 0 0 8px;
}

.detail-header p {
  margin: 0 0 4px;
  color: var(--color-text-body);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  margin-bottom: 20px;
  color: var(--color-text-body);
}

.detail-grid .full {
  grid-column: 1 / -1;
}

.items-section h2 {
  margin: 0 0 12px;
  font-size: 16px;
}

.item-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px solid var(--color-border-light);
}

.item-row p {
  margin: 4px 0 0;
  color: var(--color-text-muted);
  font-size: 13px;
}

.status-badge {
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  height: fit-content;
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

.actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.supplemental { margin-top: 16px; }
.supplemental h2 { font-size: 17px; margin-bottom: 14px; }
.evidence-list { display: grid; gap: 6px; }
.evidence-list a { color: var(--color-primary); word-break: break-all; }
.muted { color: var(--color-text-muted); }
.form-row { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.form-row input, .form-row select { flex: 1; min-width: 150px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 9px; }
.form-row button { border: 0; border-radius: var(--radius-sm); padding: 9px 14px; background: var(--color-primary); color: #fff; }
.events { margin-top: 18px; border-top: 1px solid var(--color-border-light); padding-top: 14px; line-height: 1.8; color: var(--color-text-body); }
.events h3 { font-size: 15px; }

.error {
  color: #dc2626;
}

.primary-link,
.secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  border-radius: var(--radius-sm);
  text-decoration: none;
}

.primary-link {
  background: var(--color-primary);
  color: #fff;
}

.secondary {
  background: #fff;
  border: 1px solid #d1d5db;
  color: #374151;
}
</style>
