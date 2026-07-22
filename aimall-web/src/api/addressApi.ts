import http from "./http";
import type { ApiResponse } from "./productApi";

export interface Address {
  id: number;
  name: string;
  phone: string;
  province: string;
  city: string;
  region: string;
  detailAddress: string;
  fullAddress: string;
  defaultStatus: number;
}

export function fetchAddresses() {
  return http.get<ApiResponse<Address[]>>("/api/user/addresses");
}

export function createAddress(data: Omit<Address, "id" | "fullAddress">) {
  return http.post<ApiResponse<Address>>("/api/user/addresses", data);
}

export function updateAddress(id: number, data: Omit<Address, "id" | "fullAddress">) {
  return http.put<ApiResponse<Address>>(`/api/user/addresses/${id}`, data);
}

export function deleteAddress(id: number) {
  return http.delete<ApiResponse<null>>(`/api/user/addresses/${id}`);
}

export function setDefaultAddress(id: number) {
  return http.put<ApiResponse<Address>>(`/api/user/addresses/${id}/default`);
}
