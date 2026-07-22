import http from './http'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface AdminInfo {
  id: number
  username: string
  nickName: string
}

export function adminLogin(data: { username: string; password: string }) {
  return http.post<ApiResponse<{ token: string; adminInfo: AdminInfo }>>('/api/admin/login', data)
}

export function adminLogout() {
  return http.post<ApiResponse<null>>('/api/admin/logout')
}
