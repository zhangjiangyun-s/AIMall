import http from './http'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface Product {
  id: number
  name: string
  category: string
  price: number
  status: string
}

const mockProducts: Product[] = [
  { id: 1001, name: '学习平板 A1', category: '平板电脑', price: 2999, status: '上架' },
  { id: 1002, name: '轻薄笔记本 B2', category: '笔记本电脑', price: 3999, status: '上架' },
  { id: 1003, name: '无线蓝牙耳机 C3', category: '耳机', price: 399, status: '上架' }
]

let nextId = 1004

export async function getProducts(): Promise<Product[]> {
  try {
    const response = await http.get<ApiResponse<Product[]>>('/api/admin/products')
    return response.data.data
  } catch {
    return [...mockProducts]
  }
}

export function addProduct(product: Omit<Product, 'id'>): Promise<Product> {
  const newProduct = { ...product, id: nextId++ }
  mockProducts.push(newProduct)
  return Promise.resolve(newProduct)
}

export function updateProduct(id: number, product: Partial<Product>): Promise<Product> {
  const index = mockProducts.findIndex((p) => p.id === id)
  if (index !== -1) {
    mockProducts[index] = { ...mockProducts[index], ...product }
    return Promise.resolve(mockProducts[index])
  }
  return Promise.reject(new Error('商品不存在'))
}

export function deleteProduct(id: number): Promise<void> {
  const index = mockProducts.findIndex((p) => p.id === id)
  if (index !== -1) {
    mockProducts.splice(index, 1)
    return Promise.resolve()
  }
  return Promise.reject(new Error('商品不存在'))
}
