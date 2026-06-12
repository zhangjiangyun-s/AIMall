import http from "./http";

export type PageType = "PRODUCT_DETAIL" | "ORDER_DETAIL" | "GENERAL";

export interface PageContext {
  pageType: PageType;
  productId?: number;
  orderId?: number;
}

export interface AiRequest {
  message: string;
  sessionId: string;
  pageContext: PageContext;
}

export interface AiResponse {
  answer: string;
  intent: string;
  relatedProducts: unknown[];
  suggestedActions: unknown[];
}

export function sendAiMessage(data: AiRequest) {
  return http.post<AiResponse>("/api/ai/chat", data);
}
