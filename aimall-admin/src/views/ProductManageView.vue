<template>
  <div class="product-manage">
    <div class="page-header">
      <h2>商品管理</h2>
      <el-button type="primary" @click="openAddDialog">新增商品</el-button>
    </div>

    <el-table :data="products" border stripe style="width: 100%">
      <el-table-column prop="id" label="商品 ID" width="100" />
      <el-table-column prop="name" label="商品名称" min-width="180" />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column prop="price" label="价格" width="100">
        <template #default="{ row }">
          ¥{{ row.price }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === '上架' ? 'success' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑商品' : '新增商品'" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="商品名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" />
        </el-form-item>
        <el-form-item label="价格">
          <el-input-number v-model="form.price" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="上架" value="上架" />
            <el-option label="下架" value="下架" />
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
  getProducts,
  addProduct,
  updateProduct,
  deleteProduct,
  type Product
} from '../api/productAdminApi'

const products = ref<Product[]>([])
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)

const defaultForm = { name: '', category: '', price: 0, status: '上架' }
const form = ref({ ...defaultForm })

async function loadProducts() {
  products.value = await getProducts()
}

function openAddDialog() {
  isEditing.value = false
  editingId.value = null
  form.value = { ...defaultForm }
  dialogVisible.value = true
}

function openEditDialog(product: Product) {
  isEditing.value = true
  editingId.value = product.id
  form.value = {
    name: product.name,
    category: product.category,
    price: product.price,
    status: product.status
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (isEditing.value && editingId.value !== null) {
    const updated = await updateProduct(editingId.value, form.value)
    const index = products.value.findIndex((p) => p.id === editingId.value)
    if (index !== -1) products.value[index] = updated
    ElMessage.success('商品已更新')
  } else {
    const newProduct = await addProduct(form.value)
    products.value.push(newProduct)
    ElMessage.success('商品已新增')
  }
  dialogVisible.value = false
}

async function handleDelete(product: Product) {
  await ElMessageBox.confirm(`确定删除商品「${product.name}」？`, '确认删除', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
  await deleteProduct(product.id)
  const index = products.value.findIndex((p) => p.id === product.id)
  if (index !== -1) products.value.splice(index, 1)
  ElMessage.success('商品已删除')
}

onMounted(loadProducts)
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
</style>
