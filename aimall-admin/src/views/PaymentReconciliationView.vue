<template>
  <div class="reconciliation-page">
    <header class="page-header">
      <div><h2>支付对账</h2><span>差异处理与双人复核</span></div>
      <el-button type="primary" :loading="running" @click="runReconciliation">执行昨日对账</el-button>
    </header>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="差异处理" name="items">
        <div class="toolbar">
          <el-select v-model="selectedBatchId" placeholder="选择对账批次" @change="loadItems">
            <el-option v-for="batch in batches" :key="batch.id" :label="`${batch.reconcileDate} · ${batch.status} · ${batch.differenceCount}`" :value="batch.id" />
          </el-select>
          <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="loadItems">
            <el-option v-for="status in statuses" :key="status" :label="status" :value="status" />
          </el-select>
          <el-button :loading="loading" @click="loadItems">刷新</el-button>
        </div>
        <el-table :data="items" v-loading="loading" row-key="id">
          <el-table-column prop="id" label="ID" width="72" />
          <el-table-column prop="differenceType" label="差异类型" min-width="190" />
          <el-table-column prop="orderId" label="订单" width="100" />
          <el-table-column prop="localStatus" label="本地" width="130" />
          <el-table-column prop="providerStatus" label="渠道" width="150" />
          <el-table-column label="金额" width="180"><template #default="{ row }">{{ row.localAmount ?? '-' }} / {{ row.providerAmount ?? '-' }}</template></el-table-column>
          <el-table-column prop="autoQueryStatus" label="自动查单" width="110" />
          <el-table-column prop="resolutionStatus" label="处理状态" width="145" />
          <el-table-column label="操作" width="260" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.resolutionStatus === 'OPEN'" link type="primary" @click="claim(row)">认领</el-button>
              <el-button v-if="row.resolutionStatus === 'CLAIMED'" link type="primary" @click="openCorrection(row)">提交修正</el-button>
              <el-button v-if="row.resolutionStatus === 'PENDING_REVIEW'" link type="success" @click="openReview(row)">复核</el-button>
              <el-button v-if="!['CLOSED','ESCALATED'].includes(row.resolutionStatus)" link type="danger" @click="openEscalate(row)">升级事故</el-button>
              <el-button link @click="showCorrections(row)">事件</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="修正事件" name="events">
        <el-table :data="corrections" row-key="id">
          <el-table-column prop="eventNo" label="事件号" min-width="250" />
          <el-table-column prop="correctionType" label="类型" width="150" />
          <el-table-column prop="reason" label="原因" min-width="220" />
          <el-table-column prop="operatorId" label="提交人" width="90" />
          <el-table-column prop="reviewerId" label="复核人" width="90" />
          <el-table-column prop="approvalNo" label="审批号" width="150" />
          <el-table-column prop="status" label="状态" width="135" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="correctionVisible" title="提交修正事件" width="620px">
      <el-form label-position="top">
        <el-form-item label="修正类型"><el-select v-model="correction.correctionType"><el-option v-for="type in correctionTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item label="处理原因"><el-input v-model="correction.reason" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="渠道证据"><el-input v-model="correction.evidence" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="原值 JSON"><el-input v-model="correction.originalValueJson" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="建议值 JSON"><el-input v-model="correction.proposedValueJson" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="correctionVisible=false">取消</el-button><el-button type="primary" @click="submitCorrection">提交复核</el-button></template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="第二人复核" width="520px">
      <el-form label-position="top">
        <el-form-item label="审批号"><el-input v-model="review.approvalNo" /></el-form-item>
        <el-form-item label="复核意见"><el-input v-model="review.note" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button type="danger" plain @click="reviewCorrection(false)">驳回</el-button><el-button type="success" @click="reviewCorrection(true)">通过并关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="escalateVisible" title="升级事故" width="520px">
      <el-form label-position="top">
        <el-form-item label="升级原因"><el-input v-model="escalation.reason" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="证据"><el-input v-model="escalation.evidence" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="escalateVisible=false">取消</el-button><el-button type="danger" @click="escalate">确认升级</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

interface ApiResponse<T> { code: number; data: T; message: string }
interface Batch { id: number; reconcileDate: string; status: string; differenceCount: number }
interface Item { id: number; orderId?: number; differenceType: string; localStatus: string; providerStatus: string; localAmount?: number; providerAmount?: number; autoQueryStatus: string; resolutionStatus: string }
interface Correction { id: number; eventNo: string; correctionType: string; reason: string; operatorId: number; reviewerId?: number; approvalNo?: string; status: string }

const batches = ref<Batch[]>([]), items = ref<Item[]>([]), corrections = ref<Correction[]>([])
const selectedBatchId = ref<number>(), selectedItem = ref<Item>(), selectedEventId = ref<number>()
const loading = ref(false), running = ref(false), activeTab = ref('items'), statusFilter = ref('')
const correctionVisible = ref(false), reviewVisible = ref(false), escalateVisible = ref(false)
const statuses = ['OPEN', 'CLAIMED', 'PENDING_REVIEW', 'CLOSED', 'ESCALATED']
const correctionTypes = ['PAYMENT_STATE', 'PAYMENT_AMOUNT', 'REFUND_STATE', 'REFUND_AMOUNT', 'EVIDENCE_LINK']
const correction = reactive({ correctionType: 'EVIDENCE_LINK', reason: '', evidence: '', originalValueJson: '{}', proposedValueJson: '{}' })
const review = reactive({ approvalNo: '', note: '' })
const escalation = reactive({ reason: '', evidence: '' })

async function loadBatches() {
  const response = await http.get<ApiResponse<Batch[]>>('/api/admin/payment-reconciliation/batches')
  batches.value = response.data.data
  if (!selectedBatchId.value && batches.value.length) selectedBatchId.value = batches.value[0].id
  await loadItems()
}
async function loadItems() {
  if (!selectedBatchId.value) return
  loading.value = true
  try {
    const response = await http.get<ApiResponse<Item[]>>(`/api/admin/payment-reconciliation/batches/${selectedBatchId.value}/items`, { params: { status: statusFilter.value || undefined } })
    items.value = response.data.data
  } finally { loading.value = false }
}
async function runReconciliation() {
  running.value = true
  try { await http.post('/api/admin/payment-reconciliation/run'); ElMessage.success('对账任务已完成'); await loadBatches() }
  finally { running.value = false }
}
async function claim(item: Item) { await http.post(`/api/admin/payment-reconciliation/items/${item.id}/claim`); ElMessage.success('认领成功'); await loadItems() }
function openCorrection(item: Item) { selectedItem.value = item; correctionVisible.value = true }
async function submitCorrection() {
  if (!selectedItem.value) return
  await http.post(`/api/admin/payment-reconciliation/items/${selectedItem.value.id}/corrections`, correction)
  correctionVisible.value = false; ElMessage.success('修正事件已提交复核'); await loadItems()
}
async function showCorrections(item: Item) {
  selectedItem.value = item
  const response = await http.get<ApiResponse<Correction[]>>(`/api/admin/payment-reconciliation/items/${item.id}/corrections`)
  corrections.value = response.data.data; activeTab.value = 'events'
}
async function openReview(item: Item) {
  selectedItem.value = item
  const response = await http.get<ApiResponse<Correction[]>>(`/api/admin/payment-reconciliation/items/${item.id}/corrections`)
  const pending = response.data.data.find(event => event.status === 'PENDING_REVIEW')
  if (!pending) { ElMessage.error('没有待复核修正事件'); return }
  selectedEventId.value = pending.id; reviewVisible.value = true
}
async function reviewCorrection(approved: boolean) {
  if (!selectedItem.value || !selectedEventId.value) return
  await http.post(`/api/admin/payment-reconciliation/items/${selectedItem.value.id}/corrections/${selectedEventId.value}/review`, { approved, ...review })
  reviewVisible.value = false; ElMessage.success(approved ? '复核通过' : '已驳回'); await loadItems()
}
function openEscalate(item: Item) { selectedItem.value = item; escalateVisible.value = true }
async function escalate() {
  if (!selectedItem.value) return
  await http.post(`/api/admin/payment-reconciliation/items/${selectedItem.value.id}/escalate`, escalation)
  escalateVisible.value = false; ElMessage.success('已升级事故'); await loadItems()
}
onMounted(() => loadBatches().catch(() => ElMessage.error('对账数据加载失败')))
</script>

<style scoped>
.reconciliation-page { min-width: 900px; }
.page-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:16px; }
.page-header h2 { margin:0 0 4px; font-size:22px; }
.page-header span { color:#606266; font-size:13px; }
.toolbar { display:flex; gap:10px; margin-bottom:12px; }
.toolbar .el-select:first-child { width:320px; }
.toolbar .el-select { width:180px; }
</style>
