import http from "./http";
import type { ApiResponse } from "./productApi";

export interface CartItem {
  id: number;
  productId: number;
  productName: string;
  productPrice: number;
  quantity: number;
  productPic?: string;
  productSkuId?: number;
  productSubTitle?: string;
  productSkuCode?: string;
  productAttr?: string;
  createDate?: string;
}

export function addToCart(data: { productId: number; quantity: number; productSkuId?: number }) {
  return http.post<ApiResponse<null>>("/api/cart/add", data);
}

export function fetchCartList() {
  return http.get<ApiResponse<CartItem[]>>("/api/cart/list");
}

export function updateCartItem(data: { cartItemId: number; quantity: number }) {
  return http.post<ApiResponse<null>>("/api/cart/update", data);
}

export function deleteCartItem(data: { cartItemId: number }) {
  return http.post<ApiResponse<null>>("/api/cart/delete", data);
}
