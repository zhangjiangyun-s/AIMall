import http from "./http";
import type { ApiResponse } from "./productApi";

export interface OrderItem {
  productName: string;
  quantity: number;
  price: number;
}

export interface Order {
  orderId: number;
  orderNo: string;
  status: string;
  statusText: string;
  totalAmount: number;
  items: OrderItem[];
}

export function fetchOrders() {
  return http.get<ApiResponse<Order[]>>("/api/orders");
}

export function fetchOrderById(id: number) {
  return http.get<ApiResponse<Order>>(`/api/orders/${id}`);
}
