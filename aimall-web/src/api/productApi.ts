import http from "./http";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export interface ProductListItem {
  productId: number;
  name: string;
  price: number;
  originalPrice?: number;
  category: string;
  categoryId?: number;
  subTitle?: string;
  pic?: string;
  brandName?: string;
  sellingPoints?: string[];
}

export interface ProductSkuStock {
  id: number;
  skuCode?: string;
  price: number;
  stock: number;
  sale?: number;
  spData?: string;
}

export interface ProductDetail {
  productId: number;
  name: string;
  price: number;
  originalPrice?: number;
  pic?: string;
  category: string;
  categoryId?: number;
  brandName?: string;
  stock?: number;
  description?: string;
  detailDesc?: string;
  subTitle?: string;
  keywords?: string;
  sellingPoints?: string[];
  skuStocks: ProductSkuStock[];
}

export interface ProductCategory {
  id: number;
  name: string;
  parentId?: number;
  level?: number;
  keywords?: string;
  description?: string;
}

export function fetchProducts(params?: {
  categoryId?: number;
  keyword?: string;
  sort?: "DEFAULT" | "SALES" | "PRICE_ASC" | "PRICE_DESC" | "NEWEST";
  inStock?: boolean;
  page?: number;
  size?: number;
}) {
  return http.get<ApiResponse<PageResult<ProductListItem>>>("/api/products", { params });
}

export function fetchProductById(id: number) {
  return http.get<ApiResponse<ProductDetail>>(`/api/products/${id}`);
}

export function fetchRecommendProducts(limit = 8) {
  return http.get<ApiResponse<ProductListItem[]>>("/api/products/recommend", { params: { limit } });
}

export function fetchNewProducts(limit = 8) {
  return http.get<ApiResponse<ProductListItem[]>>("/api/products/new", { params: { limit } });
}

export function fetchHotProducts(limit = 8) {
  return http.get<ApiResponse<ProductListItem[]>>("/api/products/hot", { params: { limit } });
}

export function fetchProductCategories(parentId?: number) {
  return http.get<ApiResponse<ProductCategory[]>>("/api/products/categories", { params: { parentId } });
}
