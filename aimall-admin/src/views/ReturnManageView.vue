<template>
  <div class="return-manage">
    <div class="page-header">
      <h2>售后管理</h2>
    </div>

    <el-table :data="returns" border stripe style="width: 100%">
      <el-table-column prop="id" label="售后单号" width="100" />
      <el-table-column prop="orderNo" label="关联订单" min-width="180" />
      <el-table-column prop="memberId" label="用户 ID" width="90" />
      <el-table-column prop="reason" label="申请原因" min-width="180" />
      <el-table-column prop="returnAmount" label="退款金额" width="110">
        <template #default="{ row }">&yen;{{ row.returnAmount }}</template>
      </el-table-column>
      <el-table-column prop="statusText" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="申请时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <div class="action-group">
            <el-button size="small" @click="openDetail(row.id)">详情</el-button>
            <el-button v-if="row.status === 'APPLIED'" size="small" type="primary" @click="openReview(row.id, true)">通过</el-button>
            <el-button v-if="row.status === 'APPLIED'" size="small" type="danger" plain @click="openReview(row.id, false)">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED'" size="small" type="success" @click="openRefund(row.id)">退款</el-button>
            <el-button v-if="row.status === 'RETURNING'" size="small" type="warning" @click="openInspection(row.id, true)">验收</el-button>
            <el-button v-if="row.status === 'RETURNING'" size="small" type="danger" plain @click="openInspection(row.id, false)">验收拒绝</el-button>
            <el-button v-if="row.refundStatus === 'FAILED'" size="small" type="warning" @click="handleRetryRefund(row.id)">重试退款</el-button>
            <el-button v-if="row.refundStatus === 'FAILED'" size="small" type="danger" plain @click="handleCloseRefund(row.id)">关闭退款</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailVisible" title="售后详情" width="860px">
      <template v-if="detailLoading">
        <el-skeleton :rows="8" animated />
      </template>
      <template v-else-if="currentDetail">
        <div class="detail-grid">
          <div><span class="label">售后单号</span>{{ currentDetail.id }}</div>
          <div><span class="label">订单编号</span>{{ currentDetail.orderNo }}</div>
          <div><span class="label">用户 ID</span>{{ currentDetail.memberId }}</div>
          <div><span class="label">状态</span>{{ currentDetail.statusText }}</div>
          <div><span class="label">申请原因</span>{{ currentDetail.reason }}</div>
          <div><span class="label">退款金额</span>&yen;{{ currentDetail.returnAmount }}</div>
          <div><span class="label">申请时间</span>{{ currentDetail.createTime || "-" }}</div>
          <div><span class="label">处理时间</span>{{ currentDetail.handleTime || "-" }}</div>
          <div class="full"><span class="label">补充说明</span>{{ currentDetail.description || "-" }}</div>
          <div class="full"><span class="label">处理说明</span>{{ currentDetail.handleNote || "-" }}</div>
          <div v-if="currentDetail.refundStatus" class="full"><span class="label">退款任务</span>{{ currentDetail.refundStatus }}</div>
          <div v-if="currentDetail.refundFailureReason" class="full"><span class="label">失败原因</span>{{ currentDetail.refundFailureReason }}</div>
          <div><span class="label">退货物流</span>{{ currentDetail.returnCarrier || "-" }} {{ currentDetail.returnTrackingNo || "" }}</div>
          <div><span class="label">验货结果</span>{{ currentDetail.inspectionResult || "-" }}</div>
          <div><span class="label">SLA</span>{{ currentDetail.slaDeadline || "-" }}{{ currentDetail.slaOverdue ? "（已超时）" : "" }}</div>
          <div class="full"><span class="label">凭证</span><span v-for="item in currentDetail.evidence || []" :key="item.id"><a :href="item.mediaUrl" target="_blank" rel="noreferrer">{{ item.mediaType }} {{ item.mediaUrl }}</a> </span></div>
        </div>
        <el-table :data="currentDetail.items || []" border>
          <el-table-column prop="productName" label="商品名称" min-width="180" />
          <el-table-column prop="productBrand" label="品牌" width="120" />
          <el-table-column prop="productAttr" label="规格" min-width="150" />
          <el-table-column prop="price" label="单价" width="100">
            <template #default="{ row }">&yen;{{ row.price }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column prop="realAmount" label="实付小计" width="120">
            <template #default="{ row }">&yen;{{ row.realAmount }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <el-dialog v-model="inspectionVisible" :title="inspectionAccepted ? '通过验货' : '拒绝验货'" width="460px">
      <el-input v-model="inspectionNote" type="textarea" :rows="4" placeholder="请输入验货说明" />
      <template #footer><el-button @click="inspectionVisible = false">取消</el-button><el-button type="primary" :loading="inspectionLoading" @click="handleInspection">确认</el-button></template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" :title="reviewApproved ? '通过售后' : '驳回售后'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="处理说明">
          <el-input v-model="reviewNote" type="textarea" :rows="4" placeholder="请输入处理说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewLoading" @click="handleReview">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="refundVisible" title="确认退款" width="460px">
      <el-form label-width="90px">
        <el-form-item label="退款说明">
          <el-input v-model="refundNote" type="textarea" :rows="4" placeholder="请输入退款说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="primary" :loading="refundLoading" @click="handleRefund">确认退款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElSkeleton
} from "element-plus";
import { closeFailedRefund, fetchReturnById, fetchReturnList, inspectReturn, refundReturn, retryFailedRefund, reviewReturn, type ReturnApply } from "../api/returnAdminApi";

type TagType = "success" | "info" | "warning" | "danger" | "primary";

const returns = ref<ReturnApply[]>([]);
const detailVisible = ref(false);
const detailLoading = ref(false);
const currentDetail = ref<ReturnApply | null>(null);
const reviewVisible = ref(false);
const reviewLoading = ref(false);
const reviewId = ref<number | null>(null);
const reviewApproved = ref(true);
const reviewNote = ref("");
const refundVisible = ref(false);
const refundLoading = ref(false);
const refundId = ref<number | null>(null);
const refundNote = ref("");
const inspectionVisible = ref(false);
const inspectionLoading = ref(false);
const inspectionId = ref<number | null>(null);
const inspectionAccepted = ref(true);
const inspectionNote = ref("");

function statusTag(status: string): TagType {
  switch (status) {
    case "APPLIED":
      return "warning";
    case "APPROVED":
      return "primary";
    case "REJECTED":
    case "CANCELLED":
      return "danger";
    case "REFUNDED":
      return "success";
    default:
      return "info";
  }
}

async function loadReturns() {
  returns.value = await fetchReturnList();
}

async function openDetail(id: number) {
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    currentDetail.value = await fetchReturnById(id);
  } catch {
    ElMessage.error("加载售后详情失败");
    detailVisible.value = false;
  } finally {
    detailLoading.value = false;
  }
}

function openReview(id: number, approved: boolean) {
  reviewId.value = id;
  reviewApproved.value = approved;
  reviewNote.value = "";
  reviewVisible.value = true;
}

function openRefund(id: number) {
  refundId.value = id;
  refundNote.value = "";
  refundVisible.value = true;
}

function openInspection(id: number, accepted: boolean) {
  inspectionId.value = id;
  inspectionAccepted.value = accepted;
  inspectionNote.value = "";
  inspectionVisible.value = true;
}

async function handleInspection() {
  if (!inspectionId.value || !inspectionNote.value.trim()) { ElMessage.warning("请填写验货说明"); return; }
  inspectionLoading.value = true;
  try { await inspectReturn(inspectionId.value, inspectionAccepted.value, inspectionNote.value.trim()); inspectionVisible.value = false; ElMessage.success("验货结果已保存"); await loadReturns(); }
  catch { ElMessage.error("验货处理失败"); } finally { inspectionLoading.value = false; }
}

async function handleReview() {
  if (!reviewId.value || !reviewNote.value.trim()) {
    ElMessage.warning("请填写处理说明");
    return;
  }
  reviewLoading.value = true;
  try {
    await reviewReturn(reviewId.value, reviewApproved.value, reviewNote.value.trim());
    ElMessage.success(reviewApproved.value ? "售后申请已通过" : "售后申请已驳回");
    reviewVisible.value = false;
    await loadReturns();
  } catch {
    ElMessage.error("处理失败");
  } finally {
    reviewLoading.value = false;
  }
}

async function handleRefund() {
  if (!refundId.value || !refundNote.value.trim()) {
    ElMessage.warning("请填写退款说明");
    return;
  }
  refundLoading.value = true;
  try {
    await refundReturn(refundId.value, crypto.randomUUID(), refundNote.value.trim());
    ElMessage.success("退款状态已更新");
    refundVisible.value = false;
    await loadReturns();
  } catch {
    ElMessage.error("退款处理失败");
  } finally {
    refundLoading.value = false;
  }
}

async function handleRetryRefund(id: number) {
  try {
    const { value } = await ElMessageBox.prompt("请输入人工重试原因", "重试退款", {
      inputValidator: (text) => Boolean(text?.trim()) || "处理说明不能为空",
      confirmButtonText: "确认重试",
      cancelButtonText: "取消",
    });
    await retryFailedRefund(id, value.trim());
    ElMessage.success("退款任务已重新进入处理队列");
    await loadReturns();
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error("退款重试失败");
  }
}

async function handleCloseRefund(id: number) {
  try {
    const { value } = await ElMessageBox.prompt("关闭后将释放售后商品数量，请填写原因", "关闭失败退款", {
      inputValidator: (text) => Boolean(text?.trim()) || "处理说明不能为空",
      confirmButtonText: "确认关闭",
      cancelButtonText: "取消",
      type: "warning",
    });
    await closeFailedRefund(id, value.trim());
    ElMessage.success("失败退款已关闭，售后占用已释放");
    await loadReturns();
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error("关闭失败退款失败");
  }
}

onMounted(() => {
  void loadReturns();
});
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}

.action-group {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  margin-bottom: 20px;
}

.detail-grid .full {
  grid-column: 1 / -1;
}

.label {
  display: inline-block;
  width: 72px;
  color: #909399;
  margin-right: 10px;
}
</style>
