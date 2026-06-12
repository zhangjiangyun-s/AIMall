import http from "./http";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface Product {
  productId: number;
  name: string;
  price: number;
  category: string;
  stock?: number;
  description?: string;
  sellingPoints?: string[];
}

export function fetchProducts() {
  return http.get<ApiResponse<Product[]>>("/api/products");
}

export function fetchProductById(id: number) {
  return http.get<ApiResponse<Product>>(`/api/products/${id}`);
}
