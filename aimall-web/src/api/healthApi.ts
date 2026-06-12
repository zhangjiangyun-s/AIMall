import http from "./http";

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface IntegrationHealthData {
  service: string;
  status: string;
  version: string;
  modules: {
    product: boolean;
    order: boolean;
    admin: boolean;
    aiGateway: boolean;
    database: boolean;
  };
  ports: {
    server: number;
    web: number;
    admin: number;
    aiService: number;
  };
}

export function fetchIntegrationHealth() {
  return http.get<ApiResponse<IntegrationHealthData>>("/api/health/integration");
}
