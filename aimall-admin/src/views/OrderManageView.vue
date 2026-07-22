<template>
  <div class="order-manage">
    <div class="page-header">
      <h2>订单管理</h2>
    </div>

    <el-table :data="orders" border stripe style="width: 100%">
      <el-table-column prop="id" label="订单 ID" width="90" />
      <el-table-column prop="orderNo" label="订单编号" min-width="180" />
      <el-table-column prop="userId" label="用户 ID" width="90" />
      <el-table-column prop="memberUsername" label="账号" width="140" />
      <el-table-column prop="receiverName" label="收货人" width="110" />
      <el-table-column prop="receiverPhone" label="联系电话" width="140" />
      <el-table-column prop="totalAmount" label="订单金额" width="110">
        <template #default="{ row }">
          &yen;{{ row.totalAmount }}
        </template>
      </el-table-column>
      <el-table-column prop="payAmount" label="实付金额" width="110">
        <template #default="{ row }">
          &yen;{{ row.payAmount }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="订单状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deliveryCompany" label="物流公司" width="120" />
      <el-table-column prop="deliverySn" label="物流单号" min-width="160" />
      <el-table-column prop="createTime" label="下单时间" width="170" />
      <el-table-column prop="deliveryTime" label="发货时间" width="170" />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <div class="action-group">
            <el-button size="small" @click="openDetailDialog(row)">详情</el-button>
            <el-button v-if="row.status === 1" size="small" type="primary" @click="openShipDialog(row)">发货</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailDialogVisible" title="订单详情" width="960px">
      <template v-if="detailLoading">
        <div class="dialog-loading">
          <el-skeleton :rows="10" animated />
        </div>
      </template>
      <template v-else-if="currentOrderDetail">
        <div class="detail-layout">
          <section class="detail-section">
            <h3>基础信息</h3>
            <div class="detail-grid">
              <div class="detail-item"><span class="label">订单编号</span><span>{{ currentOrderDetail.orderNo }}</span></div>
              <div class="detail-item"><span class="label">订单状态</span><span>{{ statusLabel(currentOrderDetail.status) }}</span></div>
              <div class="detail-item"><span class="label">用户 ID</span><span>{{ currentOrderDetail.userId }}</span></div>
              <div class="detail-item"><span class="label">用户账号</span><span>{{ currentOrderDetail.memberUsername || '-' }}</span></div>
              <div class="detail-item"><span class="label">订单金额</span><span>&yen;{{ currentOrderDetail.totalAmount }}</span></div>
              <div class="detail-item"><span class="label">实付金额</span><span>&yen;{{ currentOrderDetail.payAmount }}</span></div>
              <div class="detail-item"><span class="label">运费</span><span>&yen;{{ currentOrderDetail.freightAmount ?? 0 }}</span></div>
              <div class="detail-item"><span class="label">买家备注</span><span>{{ currentOrderDetail.note || '-' }}</span></div>
            </div>
          </section>

          <section class="detail-section">
            <h3>收货信息</h3>
            <div class="detail-grid">
              <div class="detail-item"><span class="label">收货人</span><span>{{ currentOrderDetail.receiverName || '-' }}</span></div>
              <div class="detail-item"><span class="label">联系电话</span><span>{{ currentOrderDetail.receiverPhone || '-' }}</span></div>
              <div class="detail-item detail-item--full">
                <span class="label">收货地址</span>
                <span>{{ fullAddress(currentOrderDetail) }}</span>
              </div>
            </div>
          </section>

          <section class="detail-section">
            <h3>支付与履约</h3>
            <div class="detail-grid">
              <div class="detail-item"><span class="label">下单时间</span><span>{{ currentOrderDetail.createTime || '-' }}</span></div>
              <div class="detail-item"><span class="label">支付时间</span><span>{{ currentOrderDetail.paymentTime || '-' }}</span></div>
              <div class="detail-item"><span class="label">发货时间</span><span>{{ currentOrderDetail.deliveryTime || '-' }}</span></div>
              <div class="detail-item"><span class="label">收货时间</span><span>{{ currentOrderDetail.receiveTime || '-' }}</span></div>
              <div class="detail-item"><span class="label">物流公司</span><span>{{ currentOrderDetail.deliveryCompany || '-' }}</span></div>
              <div class="detail-item"><span class="label">物流单号</span><span>{{ currentOrderDetail.deliverySn || '-' }}</span></div>
            </div>
          </section>

          <section class="detail-section">
            <h3>商品清单</h3>
            <el-table :data="currentOrderDetail.items" border>
              <el-table-column prop="productName" label="商品名称" min-width="180" />
              <el-table-column prop="productBrand" label="品牌" width="120" />
              <el-table-column prop="skuCode" label="SKU" width="150" />
              <el-table-column prop="productAttr" label="规格" min-width="180" />
              <el-table-column prop="price" label="单价" width="100">
                <template #default="{ row }">
                  &yen;{{ row.price }}
                </template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="80" />
              <el-table-column prop="realAmount" label="实付小计" width="120">
                <template #default="{ row }">
                  &yen;{{ row.realAmount }}
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="shipDialogVisible" title="订单发货" width="460px">
      <el-form label-width="100px">
        <el-form-item label="订单编号">
          <span>{{ currentOrder?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="物流公司">
          <el-input v-model="shipForm.deliveryCompany" placeholder="请输入物流公司" />
        </el-form-item>
        <el-form-item label="物流单号">
          <el-input v-model="shipForm.deliverySn" placeholder="请输入物流单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipLoading" @click="handleShipOrder">确认发货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElSkeleton } from 'element-plus'
import http from '../api/http'
import type { ApiResponse } from '../api/productAdminApi'

type TagType = 'success' | 'info' | 'warning' | 'danger' | 'primary'

interface OrderRow {
  id: number
  orderNo: string
  userId: number
  memberUsername: string
  receiverName: string
  receiverPhone: string
  totalAmount: number
  payAmount: number
  status: number
  deliveryCompany: string
  deliverySn: string
  createTime: string
  paymentTime: string
  deliveryTime: string
  receiveTime: string
}

interface OrderItemRow {
  productId: number
  productName: string
  productBrand: string
  quantity: number
  price: number
  skuCode: string
  productAttr: string
  realAmount: number
}

interface OrderDetail extends OrderRow {
  freightAmount: number
  promotionAmount: number
  couponAmount: number
  discountAmount: number
  note: string
  receiverProvince: string
  receiverCity: string
  receiverRegion: string
  receiverDetailAddress: string
  items: OrderItemRow[]
}

const orders = ref<OrderRow[]>([])
const shipDialogVisible = ref(false)
const detailDialogVisible = ref(false)
const shipLoading = ref(false)
const detailLoading = ref(false)
const currentOrder = ref<OrderRow | null>(null)
const currentOrderDetail = ref<OrderDetail | null>(null)
const shipForm = reactive({
  deliveryCompany: '',
  deliverySn: ''
})

const statusMap: Record<number, { label: string; tag: TagType }> = {
  0: { label: '待付款', tag: 'warning' },
  1: { label: '待发货', tag: 'primary' },
  2: { label: '已发货', tag: 'info' },
  3: { label: '已完成', tag: 'success' },
  4: { label: '已关闭', tag: 'danger' },
  5: { label: '无效订单', tag: 'info' }
}

function statusLabel(status: number): string {
  return statusMap[status]?.label || '未知'
}

function statusTag(status: number): TagType {
  return statusMap[status]?.tag || 'info'
}

function fullAddress(order: OrderDetail): string {
  const parts = [
    order.receiverProvince,
    order.receiverCity,
    order.receiverRegion,
    order.receiverDetailAddress
  ].filter(Boolean)
  return parts.length ? parts.join(' ') : '-'
}

async function loadOrders() {
  const response = await http.get<ApiResponse<OrderRow[]>>('/api/admin/orders')
  orders.value = response.data.data
}

async function openDetailDialog(order: OrderRow) {
  currentOrder.value = order
  currentOrderDetail.value = null
  detailDialogVisible.value = true
  detailLoading.value = true
  try {
    const response = await http.get<ApiResponse<OrderDetail>>(`/api/admin/orders/${order.id}`)
    currentOrderDetail.value = response.data.data
  } catch {
    ElMessage.error('加载订单详情失败')
    detailDialogVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

function openShipDialog(order: OrderRow) {
  currentOrder.value = order
  shipForm.deliveryCompany = order.deliveryCompany || ''
  shipForm.deliverySn = order.deliverySn || ''
  shipDialogVisible.value = true
}

async function handleShipOrder() {
  if (!currentOrder.value) return
  if (!shipForm.deliveryCompany.trim() || !shipForm.deliverySn.trim()) {
    ElMessage.warning('请填写物流公司和物流单号')
    return
  }
  shipLoading.value = true
  try {
    await http.post(`/api/admin/orders/${currentOrder.value.id}/ship`, {
      deliveryCompany: shipForm.deliveryCompany.trim(),
      deliverySn: shipForm.deliverySn.trim()
    })
    ElMessage.success('发货成功')
    shipDialogVisible.value = false
    await loadOrders()
    if (detailDialogVisible.value && currentOrderDetail.value?.id === currentOrder.value.id) {
      await openDetailDialog(currentOrder.value)
    }
  } catch {
    ElMessage.error('发货失败')
  } finally {
    shipLoading.value = false
  }
}

onMounted(() => {
  void loadOrders()
})
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
}

.dialog-loading {
  padding: 8px 0 16px;
}

.detail-layout {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-section h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: #303133;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
}

.detail-item {
  display: flex;
  gap: 12px;
  line-height: 1.6;
}

.detail-item--full {
  grid-column: 1 / -1;
}

.label {
  width: 72px;
  flex: none;
  color: #909399;
}
</style>
