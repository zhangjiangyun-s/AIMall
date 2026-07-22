import http from "./http";
import type { ApiResponse } from "./productApi";

export interface MemberCoupon {
  memberCouponId: number;
  couponId: number;
  name: string;
  type: string;
  amount: number;
  minPoint: number;
  note?: string;
  available: boolean;
  unavailableReason?: string;
}

export interface CouponCenterItem {
  couponId: number;
  name: string;
  type: string;
  amount: number;
  minPoint: number;
  platform: string;
  note?: string;
  startTime?: string;
  endTime?: string;
  active: boolean;
  claimed: boolean;
  claimable: boolean;
}

export interface OwnedCoupon {
  memberCouponId: number;
  couponId: number;
  name: string;
  type: string;
  amount: number;
  minPoint: number;
  platform: string;
  note?: string;
  status: number;
  statusText: string;
  active: boolean;
  usedTime?: string;
  orderId?: number;
  orderSn?: string;
  createTime?: string;
}

export interface OrderPreview {
  goodsAmount: number;
  freightAmount: number;
  couponAmount: number;
  discountAmount: number;
  payAmount: number;
  availableCoupons: MemberCoupon[];
}

export function fetchAvailableCoupons(goodsAmount: number) {
  return http.get<ApiResponse<MemberCoupon[]>>("/api/user/coupons/available", {
    params: { goodsAmount },
  });
}

export function fetchMyCoupons() {
  return http.get<ApiResponse<OwnedCoupon[]>>("/api/user/coupons/my");
}

export function fetchCouponCenter() {
  return http.get<ApiResponse<CouponCenterItem[]>>("/api/user/coupons/center");
}

export function claimCoupon(couponId: number) {
  return http.post<ApiResponse<OwnedCoupon>>(`/api/user/coupons/${couponId}/claim`);
}

export function previewOrder(data: { cartItemIds: number[]; memberCouponId?: number | null }) {
  return http.post<ApiResponse<OrderPreview>>("/api/orders/preview", data);
}
