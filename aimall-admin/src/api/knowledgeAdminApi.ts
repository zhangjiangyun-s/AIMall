import http from './http'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface KnowledgeDoc {
  id: number
  title: string
  sourceType: string
  content: string
  status: string
  version: number
  currentVersionId?: number
  visibilityScope?: string
  tenantId?: string
  roleScope?: string
  categoryIds?: string
  tags?: string
  updatedAt: string
}

export interface KnowledgeTaskEvent {
  id: number
  taskId: string
  eventType: string
  title: string
  detail: string
  progressCurrent?: number
  progressTotal?: number
  ok: boolean
  errorCode: string
  suggestion: string
  createdAt: string
}

export interface KnowledgeIndexTask {
  id: number
  taskId: string
  docId?: number
  docVersionId?: number
  taskType: string
  status: string
  currentStep: string
  progressCurrent: number
  progressTotal: number
  retryCount: number
  maxRetry: number
  errorCode: string
  errorMessage: string
  deadLetterReason: string
  nextRetryAt?: string
  lastHeartbeatAt?: string
  updatedAt: string
}

export interface KnowledgeUploadResult {
  docId: number
  versionId: number
  taskId: string
  status: string
  fileType: string
  sourceHash: string
  versionNo?: number
}

export interface KnowledgeBatchUploadItem extends Partial<KnowledgeUploadResult> {
  fileName: string
  ok: boolean
  error?: string
}

export interface KnowledgeBatchUploadResult {
  total: number
  successCount: number
  failedCount: number
  items: KnowledgeBatchUploadItem[]
}

export interface KnowledgeDocVersion {
  id: number
  versionNo: number
  fileName: string
  fileType: string
  fileSize: number
  status: string
  pageCount: number
  paragraphCount: number
  tableCount: number
  imageCount: number
  piiCount: number
  promptRiskLevel: string
  qualityScore?: number
  createdAt?: string
  updatedAt?: string
}

export interface KnowledgeChunkDetail {
  id: number
  chunkNo: number
  chunkType: string
  sectionTitle: string
  sectionPath: string
  snippet: string
  pageStart?: number
  pageEnd?: number
  status: string
  embeddingSyncStatus: string
}

export interface KnowledgeRetrievalTest {
  id: number
  testQuery: string
  hitDocId?: number
  hitChunkId?: number
  topScore?: number
  passed: boolean
  detail: string
}

export interface KnowledgeQualityReport {
  id: number
  parseScore: number
  chunkScore: number
  piiScore: number
  promptRiskScore: number
  retrievalScore: number
  syncScore: number
  totalScore: number
  grade: string
  detail: string
}

export interface KnowledgeDocDetail {
  document: KnowledgeDoc
  version: KnowledgeDocVersion | Record<string, never>
  versions: KnowledgeDocVersion[]
  activeVersionId?: number
  chunks: KnowledgeChunkDetail[]
  retrievalTests: KnowledgeRetrievalTest[]
  qualityReport: KnowledgeQualityReport | Record<string, never>
  task: {
    taskId?: string
    status?: string
    currentStep?: string
    progressCurrent?: number
    progressTotal?: number
    errorCode?: string
    errorMessage?: string
  }
  events: KnowledgeTaskEvent[]
}

interface RebuildKnowledgeResult {
  success: boolean
  message: string
}

export async function getDocs(): Promise<KnowledgeDoc[]> {
  const response = await http.get<ApiResponse<KnowledgeDoc[]>>('/api/admin/knowledge-docs')
  return response.data.data
}

export async function deleteDoc(id: number): Promise<void> {
  await http.delete(`/api/admin/knowledge-docs/${id}`)
}

export async function rebuildKnowledgeBase(): Promise<string> {
  const response = await http.post<ApiResponse<RebuildKnowledgeResult>>('/api/admin/knowledge-docs/rebuild')
  return response.data.data.message || '已触发知识库重建'
}

export async function uploadKnowledgeDoc(formData: FormData): Promise<KnowledgeUploadResult> {
  const response = await http.post<ApiResponse<KnowledgeUploadResult>>('/api/admin/knowledge/docs/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  })
  return response.data.data
}

export async function uploadKnowledgeDocsBatch(
  files: File[],
  metadata: Record<string, string>
): Promise<KnowledgeBatchUploadResult> {
  const formData = new FormData()
  files.forEach((file) => formData.append('files', file))
  Object.entries(metadata).forEach(([key, value]) => {
    if (value) formData.append(key, value)
  })
  const response = await http.post<ApiResponse<KnowledgeBatchUploadResult>>(
    '/api/admin/knowledge/docs/upload/batch',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 }
  )
  return response.data.data
}

export async function uploadKnowledgeDocVersion(docId: number, file: File): Promise<KnowledgeUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await http.post<ApiResponse<KnowledgeUploadResult>>(
    `/api/admin/knowledge/docs/${docId}/versions/upload`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 }
  )
  return response.data.data
}

export async function getKnowledgeTaskEvents(taskId: string): Promise<KnowledgeTaskEvent[]> {
  const response = await http.get<ApiResponse<KnowledgeTaskEvent[]>>(`/api/admin/knowledge/tasks/${taskId}/events`)
  return response.data.data
}

export async function getKnowledgeDocDetail(docId: number): Promise<KnowledgeDocDetail> {
  const response = await http.get<ApiResponse<KnowledgeDocDetail>>(`/api/admin/knowledge/docs/${docId}`)
  return response.data.data
}

export async function publishKnowledgeDoc(docId: number): Promise<void> {
  await http.post(`/api/admin/knowledge/docs/${docId}/publish`)
}

export async function disableKnowledgeDoc(docId: number): Promise<void> {
  await http.post(`/api/admin/knowledge/docs/${docId}/disable`)
}

export async function publishKnowledgeDocVersion(docId: number, versionId: number): Promise<void> {
  await http.post(`/api/admin/knowledge/docs/${docId}/versions/${versionId}/publish`)
}

export async function rollbackKnowledgeDocVersion(docId: number, versionId: number): Promise<void> {
  await http.post(`/api/admin/knowledge/docs/${docId}/versions/${versionId}/rollback`)
}

export async function getKnowledgeIndexTasks(limit = 50): Promise<KnowledgeIndexTask[]> {
  const response = await http.get<ApiResponse<KnowledgeIndexTask[]>>('/api/admin/knowledge-index-tasks', {
    params: { limit }
  })
  return response.data.data
}

export async function retryKnowledgeIndexTask(id: number): Promise<KnowledgeIndexTask> {
  const response = await http.post<ApiResponse<KnowledgeIndexTask>>(`/api/admin/knowledge-index-tasks/${id}/retry`)
  return response.data.data
}
