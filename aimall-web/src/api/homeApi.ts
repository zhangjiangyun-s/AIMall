import http from "./http";
import type { ApiResponse } from "./productApi";

export interface HomeCategory {
  id: number;
  name: string;
  level: number;
  keywords?: string;
  description?: string;
}

export interface HomeProduct {
  productId: number;
  name: string;
  brandName?: string;
  categoryName?: string;
  price: number;
  originalPrice?: number;
  subTitle?: string;
  pic?: string;
}

export interface HomeContent {
  categoryList: HomeCategory[];
  recommendProductList: HomeProduct[];
  newProductList: HomeProduct[];
  hotProductList: HomeProduct[];
}

export function fetchHomeContent() {
  return http.get<ApiResponse<HomeContent>>("/api/home/content");
}

export function fetchHomeCategories() {
  return http.get<ApiResponse<HomeCategory[]>>("/api/home/categories");
}
