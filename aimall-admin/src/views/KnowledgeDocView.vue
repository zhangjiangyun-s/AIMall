<template>
  <div class="knowledge-doc">
    <div class="page-header">
      <h2>知识库文档</h2>
      <div class="header-actions">
        <el-button type="warning" @click="handleRebuild">重建知识库</el-button>
        <el-button type="primary" @click="openAddDialog">新增文档</el-button>
      </div>
    </div>

    <el-table :data="docs" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="title" label="标题" min-width="150" />
      <el-table-column prop="sourceType" label="来源类型" width="120" />
      <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '已启用' ? 'success' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="60" />
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑文档' : '新增文档'" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="标题">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="来源类型">
          <el-select v-model="form.sourceType">
            <el-option label="POLICY" value="POLICY" />
            <el-option label="RULE" value="RULE" />
            <el-option label="FAQ" value="FAQ" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="已启用" value="已启用" />
            <el-option label="已禁用" value="已禁用" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDocs,
  addDoc,
  updateDoc,
  deleteDoc,
  rebuildKnowledgeBase,
  type KnowledgeDoc
} from '../api/knowledgeAdminApi'

const docs = ref<KnowledgeDoc[]>([])
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)

const defaultForm = { title: '', sourceType: 'POLICY', content: '', status: '已启用' }
const form = ref({ ...defaultForm })

async function loadDocs() {
  docs.value = await getDocs()
}

function openAddDialog() {
  isEditing.value = false
  editingId.value = null
  form.value = { ...defaultForm }
  dialogVisible.value = true
}

function openEditDialog(doc: KnowledgeDoc) {
  isEditing.value = true
  editingId.value = doc.id
  form.value = {
    title: doc.title,
    sourceType: doc.sourceType,
    content: doc.content,
    status: doc.status
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (isEditing.value && editingId.value !== null) {
    await updateDoc(editingId.value, form.value)
    ElMessage.success('文档已更新')
  } else {
    await addDoc({
      ...form.value,
      version: 1,
      updatedAt: new Date().toISOString().slice(0, 19).replace('T', ' ')
    })
    ElMessage.success('文档已新增')
  }
  dialogVisible.value = false
  await loadDocs()
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

onMounted(loadDocs)
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
.header-actions {
  display: flex;
  gap: 10px;
}
</style>
