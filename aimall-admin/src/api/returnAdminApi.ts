import http from "./http";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface ReturnApply {
  id: number;
  orderId: number;
  orderNo: string;
  memberId: number;
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
  refundStatus?: string;
  refundFailureReason?: string;
  refundRetryCount?: number;
  refundManualRetryCount?: number;
  items?: Array<{
    productId: number;
    productName: string;
    productBrand?: string;
    quantity: number;
    price: number;
    productAttr?: string;
    realAmount?: number;
  }>;
  returnCarrier?: string;
  returnTrackingNo?: string;
  inspectionResult?: string;
  inspectionNote?: string;
  slaDeadline?: string;
  slaOverdue?: boolean;
  evidence?: Array<{ id: number; mediaType: "IMAGE" | "VIDEO"; mediaUrl: string; createTime?: string }>;
  statusEvents?: Array<{ id: number; fromStatus?: number; toStatus?: number; eventType?: string; note?: string; createTime?: string }>;
}

export async function fetchReturnList(): Promise<ReturnApply[]> {
  const response = await http.get<ApiResponse<ReturnApply[]>>("/api/admin/returns");
  return response.data.data;
}

export async function fetchReturnById(id: number): Promise<ReturnApply> {
  const response = await http.get<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}`);
  return response.data.data;
}

export async function reviewReturn(id: number, approved: boolean, handleNote: string): Promise<ReturnApply> {
  const response = await http.post<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}/review`, {
    approved,
    handleNote,
  });
  return response.data.data;
}

export async function refundReturn(id: number, requestId: string, handleNote: string): Promise<ReturnApply> {
  const response = await http.post<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}/refund`, {
    requestId,
    handleNote,
  });
  return response.data.data;
}

export async function retryFailedRefund(id: number, handleNote: string): Promise<ReturnApply> {
  const response = await http.post<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}/refund/retry`, { handleNote });
  return response.data.data;
}

export async function closeFailedRefund(id: number, handleNote: string): Promise<ReturnApply> {
  const response = await http.post<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}/refund/close`, { handleNote });
  return response.data.data;
}

export async function inspectReturn(id: number, accepted: boolean, note: string): Promise<ReturnApply> {
  const response = await http.post<ApiResponse<ReturnApply>>(`/api/admin/returns/${id}/inspection`, { accepted, note });
  return response.data.data;
}
