import http from "./http";

export interface LoginHistory {
  id: number;
  clientIp?: string;
  userAgent?: string;
  success?: number;
  riskFlag?: number;
  failureReason?: string;
  createTime?: string;
}

export interface SecurityDevice {
  id: number;
  deviceName?: string;
  lastIp?: string;
  trusted?: number;
  revoked?: number;
  lastSeenTime?: string;
}

export function changePassword(oldPassword: string, newPassword: string) {
  return http.post<ApiResponse<null>>("/api/user/password/change", { oldPassword, newPassword });
}

export function fetchLoginHistory() {
  return http.get<ApiResponse<LoginHistory[]>>("/api/user/security/login-history");
}

export function fetchSecurityDevices() {
  return http.get<ApiResponse<SecurityDevice[]>>("/api/user/security/devices");
}

export function revokeSecurityDevice(id: number) {
  return http.post<ApiResponse<null>>(`/api/user/security/devices/${id}/revoke`);
}

export function consentPrivacy(version: string) {
  return http.post<ApiResponse<null>>("/api/user/privacy/consent", { version });
}

export function freezeAccount() {
  return http.post<ApiResponse<null>>("/api/user/freeze");
}

export function cancelAccount(password: string) {
  return http.post<ApiResponse<null>>("/api/user/cancel", { password });
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  phone: string;
  email: string;
}

export interface LoginResult {
  token: string;
  userInfo: UserInfo;
}

export function register(data: {
  username: string;
  password: string;
  nickname?: string;
  email: string;
  verificationCode: string;
}) {
  return http.post<ApiResponse<UserInfo>>("/api/user/register", data);
}

export function sendEmailCode(data: { email: string; purpose: "REGISTER" | "PASSWORD_RESET" }) {
  return http.post<ApiResponse<{ accepted: boolean; cooldownSeconds: number }>>("/api/user/email/code", data);
}

export function resetPassword(data: { email: string; verificationCode: string; newPassword: string }) {
  return http.post<ApiResponse<null>>("/api/user/password/reset", data);
}

export function login(data: { username: string; password: string }) {
  return http.post<ApiResponse<LoginResult>>("/api/user/login", data);
}

export function getUserInfo() {
  return http.get<ApiResponse<UserInfo>>("/api/user/info");
}

export function logout() {
  return http.post<ApiResponse<null>>("/api/user/logout");
}
