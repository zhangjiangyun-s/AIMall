<template>
  <div class="product-manage">
    <div class="page-header">
      <h2>商品管理</h2>
      <el-button type="primary" @click="openAddDialog">新增商品</el-button>
    </div>

    <el-table :data="products" border stripe style="width: 100%">
      <el-table-column prop="id" label="商品 ID" width="100" />
      <el-table-column prop="name" label="商品名称" min-width="180" />
      <el-table-column prop="productSn" label="商品货号" min-width="140" />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column prop="price" label="价格" width="100">
        <template #default="{ row }">
          ¥{{ row.price }}
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="90" />
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
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      class="product-pagination"
      @current-change="loadProducts"
      @size-change="handlePageSizeChange"
    />

    <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑商品' : '新增商品'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="商品货号" prop="productSn">
          <el-input v-model="form.productSn" />
        </el-form-item>
        <el-form-item label="分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="!editingId" label="库存" prop="stock">
          <el-input-number v-model="form.stock" :min="0" :max="999999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="图片地址">
          <el-input v-model="form.pic" placeholder="https://..." />
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
import {
  ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElMessageBox,
  ElOption, ElPagination, ElSelect
} from 'element-plus'
import {
  getProducts,
  getProductCategories,
  addProduct,
  updateProduct,
  changeProductPublishState,
  deleteProduct,
  type Product,
  type ProductCategory,
  type ProductPayload
} from '../api/productAdminApi'

const products = ref<Product[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const categories = ref<ProductCategory[]>([])
const formRef = ref()
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const originalStatus = ref('上架')

const defaultForm: ProductPayload = {
  name: '',
  categoryId: 0,
  productSn: '',
  price: 0,
  stock: 0,
  pic: '',
  status: '上架'
}
const form = ref<ProductPayload>({ ...defaultForm })
const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  productSn: [{ required: true, message: '请输入商品货号', trigger: 'blur' }],
  categoryId: [{ required: true, type: 'number', min: 1, message: '请选择商品分类', trigger: 'change' }],
  price: [{ required: true, type: 'number', min: 0, message: '价格不能小于 0', trigger: 'change' }],
  stock: [{ required: true, type: 'number', min: 0, message: '库存不能小于 0', trigger: 'change' }]
}

async function loadProducts() {
  try {
    const result = await getProducts(currentPage.value, pageSize.value)
    products.value = result.list
    total.value = result.total
  } catch (error) {
    products.value = []
    total.value = 0
    ElMessage.error(error instanceof Error ? error.message : '商品列表加载失败')
  }
}

function handlePageSizeChange() {
  currentPage.value = 1
  loadProducts()
}

async function loadCategories() {
  try {
    categories.value = await getProductCategories()
  } catch (error) {
    categories.value = []
    ElMessage.error(error instanceof Error ? error.message : '商品分类加载失败')
  }
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
  originalStatus.value = product.status
  form.value = {
    name: product.name,
    categoryId: product.categoryId,
    productSn: product.productSn,
    price: product.price,
    stock: product.stock,
    pic: product.pic || '',
    status: product.status
  }
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    if (isEditing.value && editingId.value !== null) {
      const { stock: _stock, status, ...productPatch } = form.value
      await updateProduct(editingId.value, productPatch)
      if (status !== originalStatus.value) {
        await changeProductPublishState(editingId.value, status === '上架')
      }
      ElMessage.success('商品已更新')
    } else {
      await addProduct(form.value)
      ElMessage.success('商品已新增')
    }
    dialogVisible.value = false
    await loadProducts()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商品保存失败')
  }
}

async function handleDelete(product: Product) {
  await ElMessageBox.confirm(`确定删除商品「${product.name}」？`, '确认删除', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
  try {
    await deleteProduct(product.id)
    ElMessage.success('商品已删除')
    await loadProducts()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商品删除失败')
  }
}

onMounted(() => {
  loadProducts()
  loadCategories()
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
.product-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
