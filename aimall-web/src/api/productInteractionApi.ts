import http from "./http";
import type { ApiResponse } from "./productApi";

export interface ProductReview {
  id: number;
  memberId?: number;
  productId: number;
  orderItemId?: number;
  rating: number;
  content?: string;
  createTime?: string;
}

export interface ProductInteractionItem {
  id?: number;
  productId?: number;
  product?: { id?: number; name?: string; pic?: string; price?: number };
  reason?: string;
  lastViewTime?: string;
  createTime?: string;
}

export function fetchProductReviews(productId: number) {
  return http.get<ApiResponse<ProductReview[]>>(`/api/user/product-interactions/${productId}/reviews`);
}

export function favoriteProduct(productId: number) {
  return http.post<ApiResponse<null>>(`/api/user/product-interactions/${productId}/favorite`);
}

export function unfavoriteProduct(productId: number) {
  return http.delete<ApiResponse<null>>(`/api/user/product-interactions/${productId}/favorite`);
}

export function recordProductBrowse(productId: number) {
  return http.post<ApiResponse<null>>(`/api/user/product-interactions/${productId}/browse`);
}

export function submitProductReview(productId: number, data: { orderItemId: number; rating: number; content: string }) {
  return http.post<ApiResponse<ProductReview>>(`/api/user/product-interactions/${productId}/reviews`, data);
}

export function fetchFavorites() {
  return http.get<ApiResponse<ProductInteractionItem[]>>("/api/user/product-interactions/favorites");
}

export function fetchBrowseHistory() {
  return http.get<ApiResponse<ProductInteractionItem[]>>("/api/user/product-interactions/browse-history");
}

export function fetchRecommendations() {
  return http.get<ApiResponse<ProductInteractionItem[]>>("/api/user/product-interactions/recommendations");
}
