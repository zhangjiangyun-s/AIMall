import type { ProductListItem } from "./productApi";
export type { ProductListItem };

export type PageType =
  | "HOME"
  | "PRODUCT_LIST"
  | "PRODUCT_DETAIL"
  | "CART"
  | "ORDER_LIST"
  | "ORDER_DETAIL"
  | "GENERAL";

export interface PageContext {
  pageType: PageType;
  productId?: number;
  orderId?: number;
  keyword?: string;
  categoryId?: number;
  cartItemCount?: number;
}

export interface AiRequest {
  message: string;
  sessionId: string;
  traceId?: string;
  pageContext: PageContext;
}

export interface AiSuggestedAction {
  type: string;
  label: string;
  payload?: Record<string, unknown>;
}

export type AiPendingActionStatus = "PENDING" | "EXECUTING" | "SUCCEEDED" | "FAILED" | "REJECTED" | "EXPIRED";

export interface AiPendingAction {
  actionId: string;
  actionType: "ADD_TO_CART" | "CLAIM_COUPON" | "CANCEL_ORDER" | "APPLY_RETURN";
  title: string;
  summary: string;
  status: AiPendingActionStatus;
  arguments?: Record<string, unknown>;
  result?: Record<string, unknown> | null;
  error?: string | null;
  expiresAt: number;
  updatedAt?: number;
  retryable?: boolean;
  replayed?: boolean;
}

export interface AiActionResponse {
  code: number;
  message: string;
  data: Partial<AiPendingAction> & {
    success?: boolean;
    errorCode?: string;
  };
}

export interface AiTimelineEvent {
  type: "agent_step" | "tool_call" | "tool_result" | "reflection";
  title?: string;
  content?: string;
  action?: string;
  toolName?: string;
  arguments?: Record<string, unknown>;
  ok?: boolean;
  summary?: string;
  latencyMs?: number;
  traceId?: string;
  status?: AiReflectionStatus;
  generationAttempts?: number;
  prerequisiteRetried?: boolean;
}

export type AiReflectionStatus =
  | "PASSED"
  | "RETRY_REQUIRED"
  | "CLARIFICATION_REQUIRED"
  | "DEGRADED"
  | "HUMAN_REVIEW_REQUIRED"
  | "REFUSED";

export type AiReflectionAction =
  | "ACCEPT"
  | "RETRY_RETRIEVAL"
  | "RETRY_TOOL_EXECUTION"
  | "RETRY_GENERATION"
  | "REQUEST_CLARIFICATION"
  | "RETURN_EVIDENCE_ONLY"
  | "HANDOFF_HUMAN"
  | "REFUSE";

export interface AiReflectionSummary {
  status?: AiReflectionStatus;
  action?: AiReflectionAction;
  terminal?: boolean;
  generationAttempts?: number;
  prerequisiteRetried?: boolean;
}

export interface AiReflectionEvent extends AiReflectionSummary {
  type: "reflection";
  title?: string;
  content?: string;
  traceId?: string;
}

export interface AiRagCitation {
  id: string;
  title: string;
  source: string;
  sourceType?: string;
  snippet?: string;
  sectionTitle?: string;
  sectionPath?: string;
  retrievalSource?: string;
  docId?: number;
  docVersionId?: number;
  version?: number;
  chunkId?: number;
  pageStart?: number;
  pageEnd?: number;
}

export interface AiBusinessEvidence {
  toolName: string;
  arguments?: Record<string, unknown>;
  result?: unknown;
}

export interface AiGuardrailEvent {
  type: "guardrail";
  stage: "INPUT" | "TOOL" | "RAG" | "OUTPUT";
  action: "ALLOW" | "SANITIZE" | "BLOCK";
  riskLevel?: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  title: string;
  message: string;
  blockedEvidenceCount?: number;
  traceId?: string;
}

export interface AiEvidenceGuardrailSummary {
  status: "CLEAR" | "FILTERED";
  blockedEvidenceCount: number;
}

export interface AiRagCitationEvent {
  type: "rag_citation";
  traceId?: string;
  retrievalMode?: string;
  retrievalStatus?: string;
  refusalReason?: string | null;
  citations: AiRagCitation[];
  evidence?: unknown[];
  businessEvidence?: AiBusinessEvidence[];
  evidenceGuardrail?: AiEvidenceGuardrailSummary;
}

export interface AiDoneEvent {
  type: "done";
  intent?: string;
  agentMode?: string;
  agentSteps?: unknown[];
  timelineEvents?: AiTimelineEvent[];
  relatedProducts?: ProductListItem[];
  suggestedActions?: AiSuggestedAction[];
  toolCalls?: unknown[];
  ragCitations?: AiRagCitation[];
  ragEvidence?: unknown[];
  businessEvidence?: AiBusinessEvidence[];
  ragValidation?: unknown;
  reflection?: AiReflectionSummary;
  retrievalStatus?: string | null;
  refusalReason?: string | null;
  traceId?: string;
  degraded?: boolean;
  memoryTurnCount?: number;
  memoryEntities?: Array<Record<string, unknown>>;
  pendingActions?: AiPendingAction[];
  guardrails?: AiGuardrailEvent[];
}

type AiStreamEvent =
  | { type: "delta"; content?: string; traceId?: string }
  | { type: "error"; message?: string; traceId?: string }
  | AiRagCitationEvent
  | AiGuardrailEvent
  | AiReflectionEvent
  | AiTimelineEvent
  | AiDoneEvent;

function getAiChatUrl() {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || "";
  return `${baseUrl.replace(/\/$/, "")}/api/ai/chat`;
}

function handleSseEvent(
  rawEvent: string,
  onDelta: (content: string) => void,
  onDone?: (event: AiDoneEvent) => void,
  onTimeline?: (event: AiTimelineEvent) => void,
  onCitation?: (event: AiRagCitationEvent) => void,
  onGuardrail?: (event: AiGuardrailEvent) => void
) {
  const data = rawEvent
    .split("\n")
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("");

  if (!data) return;

  const event = JSON.parse(data) as AiStreamEvent;
  if (event.type === "delta" && event.content) {
    onDelta(event.content);
  }
  if (
    event.type === "agent_step" ||
    event.type === "tool_call" ||
    event.type === "tool_result" ||
    event.type === "reflection"
  ) {
    onTimeline?.(event);
  }
  if (event.type === "rag_citation") {
    onCitation?.(event);
  }
  if (event.type === "guardrail") {
    onGuardrail?.(event);
  }
  if (event.type === "done") {
    onDone?.(event);
  }
  if (event.type === "error") {
    throw new Error(event.message || "AI 服务暂时不可用");
  }
}

function createTraceId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `trace_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

export async function sendAiMessageStream(
  data: AiRequest,
  onDelta: (content: string) => void,
  onDone?: (event: AiDoneEvent) => void,
  onTimeline?: (event: AiTimelineEvent) => void,
  onCitation?: (event: AiRagCitationEvent) => void,
  onGuardrail?: (event: AiGuardrailEvent) => void,
  signal?: AbortSignal
) {
  const token = localStorage.getItem("token");
  const requestData = {
    ...data,
    traceId: data.traceId || createTraceId(),
  };
  const response = await fetch(getAiChatUrl(), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { token } : {}),
    },
    body: JSON.stringify(requestData),
    signal,
  });

  if (!response.ok || !response.body) {
    throw new Error(`AI stream failed: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, "\n");

    let separatorIndex = buffer.indexOf("\n\n");
    while (separatorIndex >= 0) {
      const rawEvent = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);
      handleSseEvent(rawEvent, onDelta, onDone, onTimeline, onCitation, onGuardrail);
      separatorIndex = buffer.indexOf("\n\n");
    }
  }

  buffer += decoder.decode();
  if (buffer.trim()) {
    handleSseEvent(buffer, onDelta, onDone, onTimeline, onCitation, onGuardrail);
  }
}

export async function sendAiFeedback(data: {
  traceId: string;
  feedbackType: "THUMBS_UP" | "THUMBS_DOWN" | "CORRECTION";
  userId?: number;
  sessionId: string;
  userComment?: string;
  correctSnippet?: string;
}) {
  const token = localStorage.getItem("token");
  const response = await fetch(`${getAiChatUrl().replace(/\/chat$/, "")}/feedback`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { token } : {}),
    },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    throw new Error(`AI feedback failed: ${response.status}`);
  }
  return response.json();
}

export async function clearAiSession(sessionId: string) {
  const token = localStorage.getItem("token");
  const response = await fetch(`${getAiChatUrl().replace(/\/chat$/, "")}/session/clear`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { token } : {}),
    },
    body: JSON.stringify({ sessionId }),
  });
  if (!response.ok) {
    throw new Error(`AI session clear failed: ${response.status}`);
  }
}

export async function submitAiPendingAction(
  actionId: string,
  decision: "confirm" | "reject",
  sessionId: string
): Promise<AiActionResponse> {
  const token = localStorage.getItem("token");
  const response = await fetch(
    `${getAiChatUrl().replace(/\/chat$/, "")}/actions/${encodeURIComponent(actionId)}/${decision}`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { token } : {}),
      },
      body: JSON.stringify({ sessionId }),
    }
  );
  if (!response.ok) {
    throw new Error(`AI action failed: ${response.status}`);
  }
  return response.json() as Promise<AiActionResponse>;
}

export async function getAiPendingActionStatus(actionId: string, sessionId: string): Promise<AiActionResponse> {
  const token = localStorage.getItem("token");
  const response = await fetch(
    `${getAiChatUrl().replace(/\/chat$/, "")}/actions/${encodeURIComponent(actionId)}/status`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { token } : {}),
      },
      body: JSON.stringify({ sessionId }),
    }
  );
  if (!response.ok) {
    throw new Error(`AI action status failed: ${response.status}`);
  }
  return response.json() as Promise<AiActionResponse>;
}
