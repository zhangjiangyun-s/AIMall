import http from './http'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface Product {
  id: number
  categoryId: number
  name: string
  category: string
  productSn: string
  price: number
  stock: number
  pic?: string
  status: string
}

export interface ProductCategory {
  id: number
  name: string
}

export type ProductPayload = Omit<Product, 'id' | 'category'>

export interface ProductPage {
  list: Product[]
  total: number
  page: number
  size: number
}

export interface ProductPriceRule {
  id?: number
  productId: number
  skuId?: number | null
  ruleType: 'MEMBER' | 'ACTIVITY'
  ruleName: string
  memberLevel?: string | null
  price: number
  perMemberLimit?: number | null
  priority: number
  status: number
  startTime: string
  endTime: string
}

export async function getProducts(page = 1, size = 20): Promise<ProductPage> {
  const response = await http.get<ApiResponse<ProductPage>>('/api/admin/products', { params: { page, size } })
  return response.data.data
}

export async function getProductCategories(): Promise<ProductCategory[]> {
  const response = await http.get<ApiResponse<ProductCategory[]>>('/api/admin/products/categories')
  return response.data.data
}

export async function addProduct(product: ProductPayload): Promise<Product> {
  const response = await http.post<ApiResponse<Product>>('/api/admin/products', product)
  return response.data.data
}

export async function updateProduct(id: number, product: Partial<ProductPayload>): Promise<Product> {
  const response = await http.put<ApiResponse<Product>>(`/api/admin/products/${id}`, product)
  return response.data.data
}

export async function changeProductPublishState(id: number, published: boolean): Promise<Product> {
  const response = await http.post<ApiResponse<Product>>(
    `/api/admin/product-operations/products/${id}/publish-state`,
    { published }
  )
  return response.data.data
}

export async function deleteProduct(id: number): Promise<void> {
  await http.delete(`/api/admin/products/${id}`)
}

export async function getProductPriceRules(productId: number): Promise<ProductPriceRule[]> {
  const response = await http.get<ApiResponse<ProductPriceRule[]>>(
    `/api/admin/product-operations/products/${productId}/price-rules`
  )
  return response.data.data
}

export async function saveProductPriceRule(
  productId: number,
  rule: Omit<ProductPriceRule, 'productId'>
): Promise<ProductPriceRule> {
  const response = await http.post<ApiResponse<ProductPriceRule>>(
    `/api/admin/product-operations/products/${productId}/price-rules`,
    rule
  )
  return response.data.data
}

export async function disableProductPriceRule(ruleId: number): Promise<void> {
  await http.post(`/api/admin/product-operations/price-rules/${ruleId}/disable`)
}
