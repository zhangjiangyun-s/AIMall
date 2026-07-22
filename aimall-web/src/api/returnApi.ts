import http from "./http";
import type { ApiResponse } from "./productApi";

export interface ReturnItem {
  productId: number;
  productName: string;
  productBrand?: string;
  quantity: number;
  price: number;
  productAttr?: string;
}

export interface ReturnApply {
  id: number;
  orderId: number;
  orderNo: string;
  type: string;
  status: string;
  statusText: string;
  reason: string;
  description?: string;
  returnAmount: number;
  handleNote?: string;
  handleTime?: string;
  createTime?: string;
  updateTime?: string;
  items?: ReturnItem[];
  returnCarrier?: string;
  returnTrackingNo?: string;
  inspectionResult?: string;
  inspectionNote?: string;
  slaDeadline?: string;
  slaOverdue?: boolean;
  evidence?: Array<{ id: number; mediaType: "IMAGE" | "VIDEO"; mediaUrl: string; createTime?: string }>;
  statusEvents?: Array<{ id: number; fromStatus?: number; toStatus?: number; eventType?: string; note?: string; createTime?: string }>;
}

export function fetchReturnList() {
  return http.get<ApiResponse<ReturnApply[]>>("/api/returns");
}

export function fetchReturnById(id: number) {
  return http.get<ApiResponse<ReturnApply>>(`/api/returns/${id}`);
}

export function fetchLatestReturnByOrder(orderId: number) {
  return http.get<ApiResponse<ReturnApply | null>>(`/api/returns/order/${orderId}`);
}

export function applyReturn(data: { orderId: number; reason: string; description?: string }) {
  return http.post<ApiResponse<ReturnApply>>("/api/returns/apply", data);
}

export function cancelReturn(id: number) {
  return http.post<ApiResponse<ReturnApply>>(`/api/returns/${id}/cancel`);
}

export function addReturnEvidence(id: number, mediaType: "IMAGE" | "VIDEO", mediaUrl: string) {
  return http.post<ApiResponse<unknown>>(`/api/returns/${id}/evidence`, { mediaType, mediaUrl });
}

export function submitReturnLogistics(id: number, carrier: string, trackingNo: string) {
  return http.post<ApiResponse<ReturnApply>>(`/api/returns/${id}/logistics`, { carrier, trackingNo });
}
