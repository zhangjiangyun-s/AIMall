<template>
  <div class="knowledge-doc">
    <div class="page-header">
      <div>
        <h2>知识库文档</h2>
        <p>上传商城规则、售后政策、FAQ、活动说明，进入 RAG 入库流水线。</p>
      </div>
      <div class="header-actions">
        <el-button type="warning" @click="handleRebuild">重建知识库</el-button>
      </div>
    </div>

    <div class="workspace-grid">
      <section class="upload-panel">
        <div class="section-title">
          <h3>上传知识文档</h3>
          <el-tag type="info">PDF / DOCX / MD / TXT</el-tag>
        </div>

        <el-form :model="uploadForm" label-width="92px">
          <el-form-item label="文档文件">
            <input
              ref="batchFileInput"
              class="file-input"
              type="file"
              multiple
              accept=".pdf,.docx,.md,.markdown,.txt"
              @change="handleFileChange"
            />
            <div v-if="selectedFiles.length" class="selected-files">
              <div class="file-summary">已选择 {{ selectedFiles.length }} 个文件，共 {{ formatFileSize(selectedFilesSize) }}</div>
              <div v-for="(file, index) in selectedFiles" :key="`${file.name}-${file.lastModified}`" class="selected-file-row">
                <span class="selected-file-name">{{ file.name }}</span>
                <span>{{ formatFileSize(file.size) }}</span>
                <el-button link type="danger" @click="removeSelectedFile(index)">移除</el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="文档标题">
            <el-input v-model="uploadForm.title" :disabled="selectedFiles.length > 1" placeholder="不填则使用文件名" />
            <p v-if="selectedFiles.length > 1" class="form-tip">批量上传时，每份文档自动使用自己的文件名作为标题。</p>
          </el-form-item>
          <el-form-item label="文档类型">
            <el-select v-model="uploadForm.sourceType">
              <el-option label="政策 POLICY" value="POLICY" />
              <el-option label="FAQ" value="FAQ" />
              <el-option label="指南 GUIDE" value="GUIDE" />
              <el-option label="活动 ACTIVITY" value="ACTIVITY" />
            </el-select>
          </el-form-item>
          <el-form-item label="可见范围">
            <el-select v-model="uploadForm.visibilityScope">
              <el-option label="普通用户" value="PUBLIC_USER" />
              <el-option label="客服可见" value="SERVICE_STAFF" />
              <el-option label="管理员可见" value="ADMIN_ONLY" />
            </el-select>
          </el-form-item>
          <el-form-item label="角色范围">
            <el-input v-model="uploadForm.roleScope" placeholder="可选，例如 USER,CS,ADMIN" />
          </el-form-item>
          <el-form-item label="商品类目">
            <el-input v-model="uploadForm.categoryIds" placeholder="可选，例如 3,4,5" />
          </el-form-item>
          <el-form-item label="标签">
            <el-input v-model="uploadForm.tags" placeholder="可选，例如 退款,售后,优惠券" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="uploading" @click="handleUpload">批量上传并创建任务</el-button>
          </el-form-item>
        </el-form>

        <div v-if="batchUploadResult" class="batch-result">
          <div class="batch-result-summary">
            上传完成：成功 {{ batchUploadResult.successCount }} 个，失败 {{ batchUploadResult.failedCount }} 个
          </div>
          <div v-for="item in batchUploadResult.items" :key="item.fileName" class="batch-result-row">
            <el-tag :type="item.ok ? 'success' : 'danger'" size="small">{{ item.ok ? '成功' : '失败' }}</el-tag>
            <span class="selected-file-name">{{ item.fileName }}</span>
            <span v-if="item.ok" class="batch-result-detail">任务 {{ item.taskId }}</span>
            <span v-else class="batch-result-error">{{ item.error }}</span>
          </div>
        </div>
      </section>

      <section class="process-panel">
        <div class="section-title">
          <h3>文档处理过程</h3>
          <el-tag v-if="activeTaskId" type="success">{{ activeTaskId }}</el-tag>
          <el-tag v-else type="info">等待上传</el-tag>
        </div>

        <el-empty v-if="taskEvents.length === 0" description="上传文档后显示处理过程" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="event in taskEvents"
            :key="event.id"
            :type="event.ok ? 'success' : 'danger'"
            :timestamp="event.createdAt"
          >
            <div class="event-card">
              <div class="event-head">
                <strong>{{ event.title }}</strong>
                <span v-if="event.progressTotal" class="event-progress">
                  {{ event.progressCurrent || 0 }} / {{ event.progressTotal }}
                </span>
              </div>
              <p v-if="event.detail">{{ event.detail }}</p>
              <p v-if="!event.ok && event.suggestion" class="event-suggestion">{{ event.suggestion }}</p>
            </div>
          </el-timeline-item>
        </el-timeline>
      </section>
    </div>

    <section class="table-panel">
      <div class="section-title">
        <h3>文档列表</h3>
        <el-button size="small" @click="loadDocs">刷新</el-button>
      </div>
      <el-table :data="docs" border stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column prop="sourceType" label="类型" width="110" />
        <el-table-column prop="status" label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="visibilityScope" label="可见范围" width="130" />
        <el-table-column prop="tags" label="标签" min-width="120" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 'READY_TO_PUBLISH'" size="small" type="success" @click="handlePublish(row)">发布</el-button>
            <el-button v-if="row.status === 'ACTIVE'" size="small" type="warning" @click="handleDisable(row)">禁用</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="table-panel task-panel">
      <div class="section-title">
        <div>
          <h3>处理任务</h3>
          <p class="section-subtitle">查看自动重试、心跳超时和死信任务。</p>
        </div>
        <el-button size="small" @click="loadTasks">刷新</el-button>
      </div>
      <el-table :data="indexTasks" border stripe style="width: 100%" max-height="360">
        <el-table-column prop="taskId" label="任务编号" min-width="175" />
        <el-table-column prop="docId" label="文档" width="75" />
        <el-table-column prop="docVersionId" label="版本 ID" width="85" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="taskStatusTagType(row.status)">{{ taskStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="90">
          <template #default="{ row }">{{ row.progressCurrent || 0 }} / {{ row.progressTotal || 0 }}</template>
        </el-table-column>
        <el-table-column label="重试" width="80">
          <template #default="{ row }">{{ row.retryCount || 0 }} / {{ row.maxRetry || 0 }}</template>
        </el-table-column>
        <el-table-column prop="currentStep" label="当前步骤" width="135" />
        <el-table-column label="异常信息" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deadLetterReason || row.errorMessage || row.errorCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="nextRetryAt" label="下次重试" width="165" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="['FAILED', 'PARTIAL_FAILED', 'DEAD_LETTER'].includes(row.status)"
              size="small"
              type="warning"
              @click="handleRetryTask(row)"
            >重试</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="detailVisible" title="知识库文档详情" width="900px">
      <div v-if="detail" class="detail-content">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="文档标题">{{ detail.document.title }}</el-descriptions-item>
          <el-descriptions-item label="文档状态">{{ detail.document.status }}</el-descriptions-item>
          <el-descriptions-item label="文档类型">{{ detail.document.sourceType }}</el-descriptions-item>
          <el-descriptions-item label="文件名">{{ detailVersion?.fileName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="文件类型">{{ detailVersion?.fileType || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Prompt 风险">{{ detailVersion?.promptRiskLevel || '-' }}</el-descriptions-item>
          <el-descriptions-item label="段落数">{{ detailVersion?.paragraphCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="表格数">{{ detailVersion?.tableCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="敏感信息">{{ detailVersion?.piiCount ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section">
          <div class="section-title">
            <h3>版本管理</h3>
            <div>
              <input
                ref="versionFileInput"
                class="hidden-file-input"
                type="file"
                accept=".pdf,.docx,.md,.markdown,.txt"
                @change="handleVersionFileChange"
              />
              <el-button type="primary" :loading="uploadingVersion" @click="versionFileInput?.click()">上传新版本</el-button>
            </div>
          </div>
          <el-table :data="detail.versions" size="small" border>
            <el-table-column prop="versionNo" label="版本" width="75">
              <template #default="{ row }">V{{ row.versionNo }}</template>
            </el-table-column>
            <el-table-column prop="fileName" label="文件" min-width="180" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="125">
              <template #default="{ row }">
                <el-tag :type="versionStatusTagType(row.status)">{{ row.status }}</el-tag>
                <el-tag v-if="row.id === detail.activeVersionId" class="active-version-tag" type="success">线上</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qualityScore" label="质量分" width="85" />
            <el-table-column prop="createdAt" label="上传时间" width="165" />
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button v-if="row.status === 'READY'" size="small" type="success" @click="handlePublishVersion(row)">发布</el-button>
                <el-button
                  v-if="['SUPERSEDED', 'DISABLED'].includes(row.status) && row.id !== detail.activeVersionId"
                  size="small"
                  type="warning"
                  @click="handleRollbackVersion(row)"
                >回滚</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="detail-section">
          <div class="section-title">
            <h3>质量报告</h3>
            <el-tag v-if="qualityReport" :type="qualityTagType(qualityReport.grade)">
              {{ qualityReport.grade }} / {{ qualityReport.totalScore }} 分
            </el-tag>
          </div>
          <el-empty v-if="!qualityReport" description="尚未生成质量报告" :image-size="60" />
          <div v-else class="quality-grid">
            <div v-for="item in qualityItems" :key="item.label" class="quality-item">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>
        </div>

        <div class="detail-section">
          <h3>检索自测</h3>
          <el-table :data="detail.retrievalTests" size="small" border>
            <el-table-column prop="testQuery" label="测试问题" min-width="220" />
            <el-table-column prop="hitChunkId" label="命中 Chunk" width="110" />
            <el-table-column prop="topScore" label="Top 分数" width="100" />
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '失败' }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="detail-section">
          <h3>文档分块</h3>
          <el-table :data="detail.chunks" size="small" border max-height="300">
            <el-table-column prop="chunkNo" label="#" width="55" />
            <el-table-column prop="chunkType" label="类型" width="80" />
            <el-table-column prop="sectionTitle" label="章节" width="140" />
            <el-table-column prop="snippet" label="内容片段" min-width="280" show-overflow-tooltip />
            <el-table-column prop="embeddingSyncStatus" label="向量状态" width="110" />
          </el-table>
        </div>

        <div class="detail-section">
          <h3>处理过程</h3>
          <el-timeline>
            <el-timeline-item
              v-for="event in detail.events"
              :key="event.id"
              :type="event.ok ? 'success' : 'danger'"
              :timestamp="event.createdAt"
            >
              <strong>{{ event.title }}</strong>
              <p class="detail-event-text">{{ event.detail }}</p>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail?.document.status === 'READY_TO_PUBLISH'" type="success" @click="handlePublish(detail.document)">发布</el-button>
        <el-button v-if="detail?.document.status === 'ACTIVE'" type="warning" @click="handleDisable(detail.document)">禁用</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, onMounted } from 'vue'
import {
  ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElEmpty, ElForm, ElFormItem,
  ElInput, ElMessage, ElMessageBox, ElOption, ElSelect,
  ElTimeline, ElTimelineItem
} from 'element-plus'
import {
  getDocs,
  deleteDoc,
  rebuildKnowledgeBase,
  uploadKnowledgeDocsBatch,
  uploadKnowledgeDocVersion,
  getKnowledgeTaskEvents,
  getKnowledgeDocDetail,
  publishKnowledgeDoc,
  disableKnowledgeDoc,
  publishKnowledgeDocVersion,
  rollbackKnowledgeDocVersion,
  getKnowledgeIndexTasks,
  retryKnowledgeIndexTask,
  type KnowledgeDoc,
  type KnowledgeTaskEvent,
  type KnowledgeDocDetail,
  type KnowledgeDocVersion,
  type KnowledgeQualityReport,
  type KnowledgeIndexTask,
  type KnowledgeBatchUploadResult
} from '../api/knowledgeAdminApi'

const docs = ref<KnowledgeDoc[]>([])
const uploading = ref(false)
const selectedFiles = ref<File[]>([])
const batchFileInput = ref<HTMLInputElement | null>(null)
const batchUploadResult = ref<KnowledgeBatchUploadResult | null>(null)
const activeTaskId = ref('')
const taskEvents = ref<KnowledgeTaskEvent[]>([])
const detailVisible = ref(false)
const detail = ref<KnowledgeDocDetail | null>(null)
const indexTasks = ref<KnowledgeIndexTask[]>([])
const versionFileInput = ref<HTMLInputElement | null>(null)
const uploadingVersion = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let pollStartedAt = 0

const detailVersion = computed(() => {
  if (!detail.value || !('id' in detail.value.version)) return null
  return detail.value.version as KnowledgeDocVersion
})

const qualityReport = computed(() => {
  if (!detail.value || !('id' in detail.value.qualityReport)) return null
  return detail.value.qualityReport as KnowledgeQualityReport
})

const qualityItems = computed(() => {
  const report = qualityReport.value
  if (!report) return []
  return [
    { label: '解析完整度', value: report.parseScore },
    { label: '分块质量', value: report.chunkScore },
    { label: '敏感信息', value: report.piiScore },
    { label: '注入安全', value: report.promptRiskScore },
    { label: '检索命中', value: report.retrievalScore },
    { label: '向量同步', value: report.syncScore }
  ]
})

const uploadForm = ref({
  title: '',
  sourceType: 'POLICY',
  visibilityScope: 'PUBLIC_USER',
  roleScope: '',
  categoryIds: '',
  tags: ''
})

const selectedFilesSize = computed(() => selectedFiles.value.reduce((total, file) => total + file.size, 0))

async function loadDocs() {
  docs.value = await getDocs()
}

async function loadTasks() {
  indexTasks.value = await getKnowledgeIndexTasks(50)
}

async function handleRetryTask(task: KnowledgeIndexTask) {
  await ElMessageBox.confirm(`确定重新执行任务 ${task.taskId}？`, '任务重试确认', {
    confirmButtonText: '重新执行',
    cancelButtonText: '取消',
    type: 'warning'
  })
  const retried = await retryKnowledgeIndexTask(task.id)
  activeTaskId.value = retried.taskId
  ElMessage.success('任务已重新投递')
  startTaskPolling()
  await loadTasks()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFiles.value = Array.from(input.files || [])
  batchUploadResult.value = null
  if (selectedFiles.value.length > 1) uploadForm.value.title = ''
}

function removeSelectedFile(index: number) {
  selectedFiles.value.splice(index, 1)
  if (batchFileInput.value) batchFileInput.value.value = ''
}

async function handleUpload() {
  if (!selectedFiles.value.length) {
    ElMessage.warning('请先选择 PDF、DOCX、Markdown 或 TXT 文档')
    return
  }
  if (selectedFiles.value.length > 20) {
    ElMessage.warning('单次最多上传 20 个文档')
    return
  }
  if (selectedFilesSize.value > 200 * 1024 * 1024) {
    ElMessage.warning('批量文件总大小不能超过 200MB')
    return
  }
  uploading.value = true
  try {
    const result = await uploadKnowledgeDocsBatch(selectedFiles.value, uploadForm.value)
    batchUploadResult.value = result
    const firstSuccess = result.items.find((item) => item.ok && item.taskId)
    if (firstSuccess?.taskId) {
      activeTaskId.value = firstSuccess.taskId
      startTaskPolling()
    }
    if (result.failedCount === 0) ElMessage.success(`成功上传 ${result.successCount} 个文档`)
    else if (result.successCount > 0) ElMessage.warning(`成功 ${result.successCount} 个，失败 ${result.failedCount} 个`)
    else ElMessage.error('本批文件全部上传失败，请查看失败原因')
    await Promise.all([loadDocs(), loadTasks()])
  } finally {
    uploading.value = false
  }
}

async function refreshTaskEvents() {
  if (!activeTaskId.value) return
  taskEvents.value = await getKnowledgeTaskEvents(activeTaskId.value)
}

function startTaskPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollStartedAt = Date.now()
  void pollTaskEvents()
}

async function pollTaskEvents() {
  await refreshTaskEvents()
  const terminalEvents = ['ready_to_publish', 'ready_for_review', 'failed']
  const finished = taskEvents.value.some((event) => terminalEvents.includes(event.eventType))
  if (finished || Date.now() - pollStartedAt > 180000) {
    await loadDocs()
    return
  }
  pollTimer = setTimeout(() => void pollTaskEvents(), 2000)
}

async function openDetail(doc: KnowledgeDoc) {
  detail.value = await getKnowledgeDocDetail(doc.id)
  detailVisible.value = true
}

async function handleVersionFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !detail.value) return
  uploadingVersion.value = true
  try {
    const result = await uploadKnowledgeDocVersion(detail.value.document.id, file)
    activeTaskId.value = result.taskId
    ElMessage.success(`V${result.versionNo || ''} 已上传，线上版本将在发布前保持不变`)
    detail.value = await getKnowledgeDocDetail(detail.value.document.id)
    startTaskPolling()
    await loadDocs()
  } finally {
    uploadingVersion.value = false
    input.value = ''
  }
}

async function handlePublishVersion(version: KnowledgeDocVersion) {
  if (!detail.value) return
  const doc = detail.value.document
  await ElMessageBox.confirm(`确定发布「${doc.title}」V${version.versionNo}？当前线上版本将自动下线。`, '版本发布确认', {
    confirmButtonText: '发布并切换',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await publishKnowledgeDocVersion(doc.id, version.id)
  ElMessage.success(`已发布 V${version.versionNo}`)
  detail.value = await getKnowledgeDocDetail(doc.id)
  await loadDocs()
}

async function handleRollbackVersion(version: KnowledgeDocVersion) {
  if (!detail.value) return
  const doc = detail.value.document
  await ElMessageBox.confirm(`确定回滚到「${doc.title}」V${version.versionNo}？`, '版本回滚确认', {
    confirmButtonText: '确认回滚',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await rollbackKnowledgeDocVersion(doc.id, version.id)
  ElMessage.success(`已回滚到 V${version.versionNo}`)
  detail.value = await getKnowledgeDocDetail(doc.id)
  await loadDocs()
}

async function handlePublish(doc: KnowledgeDoc) {
  await ElMessageBox.confirm(`确定发布文档「${doc.title}」？发布后 AI 可检索该文档。`, '发布确认', {
    confirmButtonText: '发布',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await publishKnowledgeDoc(doc.id)
  ElMessage.success('文档已发布')
  await loadDocs()
  if (detailVisible.value) detail.value = await getKnowledgeDocDetail(doc.id)
}

async function handleDisable(doc: KnowledgeDoc) {
  await ElMessageBox.confirm(`确定禁用文档「${doc.title}」？禁用后 AI 将不再检索该文档。`, '禁用确认', {
    confirmButtonText: '禁用',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await disableKnowledgeDoc(doc.id)
  ElMessage.success('文档已禁用')
  await loadDocs()
  if (detailVisible.value) detail.value = await getKnowledgeDocDetail(doc.id)
}

async function handleDelete(doc: KnowledgeDoc) {
  await ElMessageBox.confirm(`确定删除文档「${doc.title}」？`, '确认删除', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await deleteDoc(doc.id)
  ElMessage.success('文档已删除')
  await loadDocs()
}

async function handleRebuild() {
  const msg = await rebuildKnowledgeBase()
  ElMessage.success(msg)
}

function statusTagType(status: string) {
  if (status === 'ACTIVE' || status === 'ENABLED' || status === 'READY_TO_PUBLISH') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'UPLOADED' || status === 'INDEXING') return 'warning'
  return 'info'
}

function versionStatusTagType(status: string) {
  if (status === 'ACTIVE' || status === 'READY') return 'success'
  if (status === 'REVIEW_REQUIRED') return 'warning'
  if (status === 'SUPERSEDED' || status === 'DISABLED') return 'info'
  return ''
}

function taskStatusTagType(status: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'RUNNING' || status === 'PENDING') return 'primary'
  if (status === 'RETRY_WAIT') return 'warning'
  if (status === 'FAILED' || status === 'PARTIAL_FAILED' || status === 'DEAD_LETTER') return 'danger'
  return 'info'
}

function taskStatusText(status: string) {
  const labels: Record<string, string> = {
    PENDING: '等待执行',
    RUNNING: '处理中',
    RETRY_WAIT: '等待重试',
    SUCCESS: '成功',
    FAILED: '失败',
    PARTIAL_FAILED: '部分失败',
    DEAD_LETTER: '死信'
  }
  return labels[status] || status
}

function qualityTagType(grade: string) {
  if (grade === 'A') return 'success'
  if (grade === 'B') return 'primary'
  if (grade === 'C') return 'warning'
  return 'danger'
}

function formatFileSize(size: number) {
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`
  return `${(size / 1024 / 1024).toFixed(1)}MB`
}

onMounted(() => {
  void loadDocs()
  void loadTasks()
})
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<style scoped>
.knowledge-doc {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-header,
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.page-header h2,
.section-title h3 {
  margin: 0;
}

.page-header p {
  margin: 6px 0 0;
  color: #7a8494;
  font-size: 13px;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(360px, 460px) minmax(0, 1fr);
  gap: 16px;
}

.upload-panel,
.process-panel,
.table-panel {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
  background: #fff;
}

.file-input {
  width: 100%;
}

.hidden-file-input {
  display: none;
}

.active-version-tag {
  margin-left: 6px;
}

.file-meta {
  margin: 6px 0 0;
  color: #7a8494;
  font-size: 12px;
}

.selected-files,
.batch-result {
  width: 100%;
  margin-top: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}

.file-summary,
.batch-result-summary {
  padding: 8px 10px;
  background: #f8fafc;
  color: #4b5563;
  font-size: 12px;
}

.selected-file-row,
.batch-result-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 7px 10px;
  border-top: 1px solid #eef0f3;
  color: #6b7280;
  font-size: 12px;
}

.selected-file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #374151;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-tip {
  margin: 5px 0 0;
  color: #7a8494;
  font-size: 12px;
}

.batch-result-detail {
  color: #6b7280;
}

.batch-result-error {
  max-width: 45%;
  color: #b91c1c;
}

.event-card {
  color: #4b5563;
}

.event-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.event-progress,
.event-card p,
.event-suggestion {
  font-size: 12px;
}

.event-card p {
  margin: 4px 0 0;
  color: #7a8494;
}

.event-suggestion {
  color: #c2410c;
}

.detail-content,
.detail-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-panel {
  margin-top: 20px;
}

.section-subtitle {
  margin: 4px 0 0;
  color: #7a8494;
  font-size: 12px;
}

.detail-section {
  margin-top: 20px;
}

.detail-section h3 {
  margin: 0;
  font-size: 15px;
}

.quality-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(90px, 1fr));
  border: 1px solid #e5e7eb;
}

.quality-item {
  padding: 10px;
  border-right: 1px solid #e5e7eb;
  text-align: center;
}

.quality-item:last-child {
  border-right: none;
}

.quality-item span,
.quality-item strong {
  display: block;
}

.quality-item span,
.detail-event-text {
  color: #7a8494;
  font-size: 12px;
}

.quality-item strong {
  margin-top: 4px;
  font-size: 16px;
}

.detail-event-text {
  margin: 4px 0 0;
}

@media (max-width: 980px) {
  .workspace-grid {
    grid-template-columns: 1fr;
  }

  .quality-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .quality-item {
    border-bottom: 1px solid #e5e7eb;
  }
}
</style>
