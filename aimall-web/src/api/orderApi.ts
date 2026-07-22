import http from "./http";
import type { ApiResponse } from "./productApi";

export interface OrderItem {
  productId: number;
  productName: string;
  productBrand?: string;
  quantity: number;
  price: number;
  skuCode?: string;
  productAttr?: string;
  realAmount?: number;
}

export interface Order {
  orderId: number;
  orderNo: string;
  status: string;
  statusText: string;
  totalAmount: number;
  payAmount?: number;
  freightAmount?: number;
  couponAmount?: number;
  discountAmount?: number;
  receiverName?: string;
  receiverPhone?: string;
  receiverProvince?: string;
  receiverCity?: string;
  receiverRegion?: string;
  receiverDetailAddress?: string;
  createTime?: string;
  paymentTime?: string;
  deliveryTime?: string;
  receiveTime?: string;
  deliveryCompany?: string;
  deliverySn?: string;
  items: OrderItem[];
}

export function fetchOrders() {
  return http.get<ApiResponse<Order[]>>("/api/orders");
}

export function fetchOrderById(id: number) {
  return http.get<ApiResponse<Order>>(`/api/orders/${id}`);
}

export function createOrder(data: {
  requestId: string;
  cartItemIds: number[];
  addressId: number;
  memberCouponId?: number | null;
}) {
  return http.post<ApiResponse<{ orderId: number; orderSn: string }>>("/api/orders/create", data);
}

export function cancelOrder(orderId: number) {
  return http.post<ApiResponse<{ message: string }>>(`/api/orders/${orderId}/cancel`);
}

export function confirmReceiveOrder(orderId: number) {
  return http.post<ApiResponse<{ message: string }>>(`/api/orders/${orderId}/confirm-receive`);
}
