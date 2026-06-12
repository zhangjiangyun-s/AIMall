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
  updatedAt: string
}

interface RebuildKnowledgeResult {
  success: boolean
  message: string
}

const mockDocs: KnowledgeDoc[] = [
  {
    id: 1,
    title: '退货规则',
    sourceType: 'POLICY',
    content: '消费者有权在收货后 7 天内无理由退货',
    status: '已启用',
    version: 1,
    updatedAt: '2026-06-01 10:00:00'
  },
  {
    id: 2,
    title: '发货规则',
    sourceType: 'POLICY',
    content: '商家需在 48 小时内完成发货',
    status: '已启用',
    version: 1,
    updatedAt: '2026-06-01 10:00:00'
  }
]

let nextDocId = 3

export async function getDocs(): Promise<KnowledgeDoc[]> {
  try {
    const response = await http.get<ApiResponse<KnowledgeDoc[]>>('/api/admin/knowledge-docs')
    return response.data.data
  } catch {
    return [...mockDocs]
  }
}

export function addDoc(doc: Omit<KnowledgeDoc, 'id'>): Promise<KnowledgeDoc> {
  const newDoc = { ...doc, id: nextDocId++ }
  mockDocs.push(newDoc)
  return Promise.resolve(newDoc)
}

export function updateDoc(id: number, doc: Partial<KnowledgeDoc>): Promise<KnowledgeDoc> {
  const index = mockDocs.findIndex((d) => d.id === id)
  if (index !== -1) {
    mockDocs[index] = { ...mockDocs[index], ...doc }
    return Promise.resolve(mockDocs[index])
  }
  return Promise.reject(new Error('文档不存在'))
}

export function deleteDoc(id: number): Promise<void> {
  const index = mockDocs.findIndex((d) => d.id === id)
  if (index !== -1) {
    mockDocs.splice(index, 1)
    return Promise.resolve()
  }
  return Promise.reject(new Error('文档不存在'))
}

export async function rebuildKnowledgeBase(): Promise<string> {
  try {
    const response = await http.post<ApiResponse<RebuildKnowledgeResult>>('/api/admin/knowledge-docs/rebuild')
    return response.data.data.message || '已触发知识库重建 mock'
  } catch {
    return '已触发知识库重建 mock'
  }
}
