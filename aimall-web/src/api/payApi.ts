import http from "./http";
import type { ApiResponse } from "./productApi";

export interface PayStatus {
  orderId: number;
  orderSn: string;
  orderStatus: number;
  payStatus: string;
  payChannel: string;
  amount: number;
  transactionNo: string;
  payTime: string;
}

export function fetchPayStatus(orderId: number) {
  return http.get<ApiResponse<PayStatus>>(`/api/pay/status/${orderId}`);
}

export function simulatePay(orderId: number) {
  return http.post<ApiResponse<{ message: string }>>("/api/pay/simulate", { orderId });
}

export interface AlipayPayment {
  orderId: number;
  orderSn: string;
  amount: number;
  payChannel: string;
  gatewayUrl: string;
  form: string;
}

export function createAlipayPayment(orderId: number) {
  return http.post<ApiResponse<AlipayPayment>>("/api/pay/alipay/create", { orderId });
}
