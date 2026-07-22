<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  sendAiFeedback,
  sendAiMessageStream,
  submitAiPendingAction,
  getAiPendingActionStatus,
  type AiRagCitation,
  type AiBusinessEvidence,
  type AiTimelineEvent,
  type AiPendingAction,
  type AiGuardrailEvent,
  type AiReflectionSummary,
  type PageContext,
  type PageType,
} from "../../api/aiApi";
import type { AiSuggestedAction, ProductListItem } from "../../api/aiApi";

type ChatMessage = {
  role: "user" | "ai";
  text: string;
  traceId?: string;
  intent?: string;
  agentMode?: string;
  degraded?: boolean;
  timeline?: AiTimelineEvent[];
  ragCitations?: AiRagCitation[];
  businessEvidence?: AiBusinessEvidence[];
  retrievalStatus?: string | null;
  refusalReason?: string | null;
  feedbackType?: "THUMBS_UP" | "THUMBS_DOWN" | "CORRECTION";
  relatedProducts?: ProductListItem[];
  suggestedActions?: AiSuggestedAction[];
  pendingActions?: AiPendingAction[];
  guardrails?: AiGuardrailEvent[];
  reflection?: AiReflectionSummary;
};

type StoredConversation = {
  loginSessionId: string;
  expiresAt: number;
  messages: ChatMessage[];
};

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

const route = useRoute();
const router = useRouter();

const open = ref(false);
const messages = ref<ChatMessage[]>([]);
const expandedTraceKeys = ref<Record<number, boolean>>({});
const expandedCitationKeys = ref<Record<number, boolean>>({});
const traceAutoCollapsedKeys = ref<Record<number, boolean>>({});
const messageListRef = ref<HTMLElement | null>(null);
const input = ref("");
const sending = ref(false);
const actionLoadingKeys = ref<Record<string, boolean>>({});
let streamAbortController: AbortController | null = null;

const recommendedQuestions = [
  "帮我找一台 3000 元内的轻薄本",
  "这款商品适合学生吗？",
  "帮我比较商品 1001 和 1002 哪个更好",
  "有什么热门数码产品推荐？",
];

function getUserId() {
  try {
    const raw = localStorage.getItem("userInfo");
    if (!raw) return "guest";
    const info = JSON.parse(raw);
    return String(info.id || info.username || "guest");
  } catch {
    return "guest";
  }
}

function getLoginSessionId() {
  const storageKey = "aimall_login_session_id";
  const token = localStorage.getItem("token") || "guest";
  const existing = localStorage.getItem(storageKey);
  if (existing) return existing;

  const sessionId =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? `login_${crypto.randomUUID()}`
      : `login_${Date.now()}_${Math.random().toString(16).slice(2)}`;
  localStorage.setItem(storageKey, sessionId);
  localStorage.setItem("aimall_login_session_token", token);
  return sessionId;
}

function getConversationKey() {
  return `aimall_ai_conversation_${getUserId()}_${getLoginSessionId()}`;
}

function getSessionId() {
  return `ai_session_${getLoginSessionId()}`;
}

function loadMessages() {
  try {
    const raw = localStorage.getItem(getConversationKey());
    if (!raw) {
      messages.value = [];
      return;
    }
    const stored = JSON.parse(raw) as StoredConversation;
    if (!stored.expiresAt || stored.expiresAt < Date.now() || stored.loginSessionId !== getLoginSessionId()) {
      localStorage.removeItem(getConversationKey());
      messages.value = [];
      return;
    }
    messages.value = Array.isArray(stored.messages) ? stored.messages : [];
  } catch {
    messages.value = [];
  }
}

async function reconcilePendingActions() {
  const candidates = messages.value.flatMap((message) => message.pendingActions || [])
    .filter((action) => action.status === "PENDING" || action.status === "EXECUTING");
  for (const action of candidates) {
    try {
      const response = await getAiPendingActionStatus(action.actionId, getSessionId());
      if (response.code === 0) {
        Object.assign(action, response.data);
      } else if (response.data.errorCode === "ACTION_EXPIRED") {
        action.status = "EXPIRED";
        action.error = response.message;
      } else if (response.data.errorCode === "ACTION_NOT_FOUND") {
        action.status = "FAILED";
        action.error = "确认操作已失效，请重新向助手发起。";
      }
    } catch {
      action.error = "暂时无法同步操作状态。";
    }
  }
  saveMessages();
}

function saveMessages() {
  const payload: StoredConversation = {
    loginSessionId: getLoginSessionId(),
    expiresAt: Date.now() + ONE_DAY_MS,
    messages: messages.value,
  };
  localStorage.setItem(getConversationKey(), JSON.stringify(payload));
}

function buildPageContext(): PageContext {
  const name = route.name as string;
  switch (name) {
    case "Home":
      return { pageType: "HOME" as PageType };
    case "ProductList":
      return {
        pageType: "PRODUCT_LIST" as PageType,
        keyword: (route.query.keyword as string) || undefined,
        categoryId: route.query.categoryId ? Number(route.query.categoryId) : undefined,
      };
    case "ProductDetail":
      return { pageType: "PRODUCT_DETAIL" as PageType, productId: Number(route.params.id) };
    case "Cart":
      return { pageType: "CART" as PageType };
    case "OrderList":
      return { pageType: "ORDER_LIST" as PageType };
    case "OrderDetail":
      return { pageType: "ORDER_DETAIL" as PageType, orderId: Number(route.params.id) };
    default:
      return { pageType: "GENERAL" as PageType };
  }
}

function mergeGuardrails(current: AiGuardrailEvent[] | undefined, incoming: AiGuardrailEvent[] | undefined) {
  const result = [...(current || [])];
  for (const event of incoming || []) {
    const index = result.findIndex(
      (item) => item.stage === event.stage && item.action === event.action && item.title === event.title
    );
    if (index >= 0) result[index] = event;
    else result.push(event);
  }
  return result;
}

async function scrollToBottom() {
  await nextTick();
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight;
  }
}

async function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;

  input.value = "";
  messages.value.push({ role: "user", text });
  const aiMessageIndex = messages.value.push({ role: "ai", text: "", timeline: [] }) - 1;
  expandedTraceKeys.value[aiMessageIndex] = false;
  expandedCitationKeys.value[aiMessageIndex] = false;
  traceAutoCollapsedKeys.value[aiMessageIndex] = false;
  saveMessages();
  sending.value = true;
  streamAbortController?.abort();
  const controller = new AbortController();
  streamAbortController = controller;
  await scrollToBottom();

  try {
    await sendAiMessageStream(
      {
        message: text,
        sessionId: getSessionId(),
        pageContext: buildPageContext(),
      },
      async (chunk) => {
        if (!traceAutoCollapsedKeys.value[aiMessageIndex]) {
          expandedTraceKeys.value[aiMessageIndex] = false;
          traceAutoCollapsedKeys.value[aiMessageIndex] = true;
        }
        messages.value[aiMessageIndex].text += chunk;
        saveMessages();
        await scrollToBottom();
      },
      async (event) => {
        messages.value[aiMessageIndex].traceId = event.traceId;
        messages.value[aiMessageIndex].intent = event.intent;
        messages.value[aiMessageIndex].agentMode = event.agentMode;
        messages.value[aiMessageIndex].degraded = event.degraded;
        messages.value[aiMessageIndex].reflection = event.reflection;
        messages.value[aiMessageIndex].relatedProducts = event.relatedProducts || [];
        messages.value[aiMessageIndex].suggestedActions = event.suggestedActions || [];
        messages.value[aiMessageIndex].pendingActions = event.pendingActions || [];
        messages.value[aiMessageIndex].guardrails = mergeGuardrails(
          messages.value[aiMessageIndex].guardrails,
          event.guardrails
        );
        messages.value[aiMessageIndex].ragCitations = event.ragCitations || messages.value[aiMessageIndex].ragCitations || [];
        messages.value[aiMessageIndex].businessEvidence = event.businessEvidence || messages.value[aiMessageIndex].businessEvidence || [];
        messages.value[aiMessageIndex].retrievalStatus = event.retrievalStatus;
        messages.value[aiMessageIndex].refusalReason = event.refusalReason;
        if ((!messages.value[aiMessageIndex].timeline || !messages.value[aiMessageIndex].timeline?.length) && event.timelineEvents) {
          messages.value[aiMessageIndex].timeline = event.timelineEvents;
        }
        saveMessages();
        await scrollToBottom();
      },
      async (event) => {
        const current = messages.value[aiMessageIndex];
        current.timeline = [...(current.timeline || []), event];
        if (event.type === "reflection") {
          current.reflection = {
            status: event.status,
            action: event.action as AiReflectionSummary["action"],
            generationAttempts: event.generationAttempts,
            prerequisiteRetried: event.prerequisiteRetried,
            terminal: true,
          };
        }
        current.traceId = event.traceId || current.traceId;
        saveMessages();
        await scrollToBottom();
      },
      async (event) => {
        const current = messages.value[aiMessageIndex];
        current.ragCitations = event.citations || [];
        current.businessEvidence = event.businessEvidence || [];
        current.retrievalStatus = event.retrievalStatus;
        current.refusalReason = event.refusalReason;
        current.traceId = event.traceId || current.traceId;
        saveMessages();
        await scrollToBottom();
      },
      async (event) => {
        const current = messages.value[aiMessageIndex];
        current.guardrails = mergeGuardrails(current.guardrails, [event]);
        current.traceId = event.traceId || current.traceId;
        saveMessages();
        await scrollToBottom();
      },
      controller.signal
    );
  } catch {
    if (controller.signal.aborted) return;
    messages.value[aiMessageIndex].text = "AI 服务暂时不可用，请稍后重试。";
    saveMessages();
  } finally {
    if (streamAbortController === controller) streamAbortController = null;
    sending.value = false;
    await scrollToBottom();
  }
}

function askQuestion(q: string) {
  input.value = q;
  send();
}

function handleAction(action: AiSuggestedAction) {
  open.value = false;
  switch (action.type) {
    case "OPEN_PRODUCTS":
      router.push("/products");
      break;
    case "OPEN_ORDERS":
      router.push("/orders");
      break;
    case "OPEN_PRODUCT_DETAIL":
      if (action.payload?.productId) router.push(`/products/${action.payload.productId}`);
      break;
  }
}

function goProduct(id: number) {
  open.value = false;
  router.push(`/products/${id}`);
}

async function submitPendingAction(messageIndex: number, action: AiPendingAction, decision: "confirm" | "reject") {
  const key = `${messageIndex}:${action.actionId}`;
  if (actionLoadingKeys.value[key] || action.status !== "PENDING") return;
  actionLoadingKeys.value[key] = true;
  action.status = "EXECUTING";
  action.error = null;
  saveMessages();
  try {
    const response = await submitAiPendingAction(action.actionId, decision, getSessionId());
    Object.assign(action, response.data);
    if (response.code !== 0) {
      const errorCode = response.data.errorCode;
      if (errorCode === "ACTION_EXPIRED") action.status = "EXPIRED";
      else if (errorCode === "ACTION_EXECUTING") action.status = "EXECUTING";
      else if (errorCode === "ACTION_RETRYABLE") {
        action.status = "PENDING";
        action.retryable = true;
      } else if (!response.data.status) action.status = "FAILED";
      action.error = response.message || "操作失败";
    }
  } catch {
    action.status = "PENDING";
    action.retryable = true;
    action.error = "网络连接中断，可使用原操作安全重试。";
  } finally {
    actionLoadingKeys.value[key] = false;
    saveMessages();
    await scrollToBottom();
  }
}

function pendingActionStatusText(action: AiPendingAction) {
  if (action.status === "PENDING" && action.retryable) return "等待重试";
  return {
    PENDING: "等待确认",
    EXECUTING: "执行中",
    SUCCEEDED: "已完成",
    FAILED: "执行失败",
    REJECTED: "已取消",
    EXPIRED: "已过期",
  }[action.status];
}

function pendingActionExpiry(action: AiPendingAction) {
  if (!action.expiresAt) return "";
  return new Date(action.expiresAt * 1000).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

function isPendingActionLoading(messageIndex: number, action: AiPendingAction) {
  return !!actionLoadingKeys.value[`${messageIndex}:${action.actionId}`];
}

async function submitFeedback(index: number, feedbackType: "THUMBS_UP" | "THUMBS_DOWN") {
  const msg = messages.value[index];
  if (!msg.traceId) return;
  msg.feedbackType = feedbackType;
  saveMessages();
  try {
    await sendAiFeedback({
      traceId: msg.traceId,
      feedbackType,
      sessionId: getSessionId(),
    });
  } catch {
    msg.feedbackType = undefined;
    saveMessages();
  }
}

function toggleTrace(index: number) {
  expandedTraceKeys.value[index] = !expandedTraceKeys.value[index];
}

function toggleCitations(index: number) {
  expandedCitationKeys.value[index] = !expandedCitationKeys.value[index];
}

function visibleCitations(msg: ChatMessage, index: number) {
  const citations = msg.ragCitations || [];
  return expandedCitationKeys.value[index] ? citations : [];
}

function citationToggleText(index: number) {
  return expandedCitationKeys.value[index] ? "收起" : "展开";
}

function visibleTimeline(msg: ChatMessage, index: number) {
  const timeline = msg.timeline || [];
  if (expandedTraceKeys.value[index]) return timeline;
  if (!msg.text) return timeline.slice(-6);
  return [];
}

function hiddenTimelineCount(msg: ChatMessage, index: number) {
  const timeline = msg.timeline || [];
  if (expandedTraceKeys.value[index]) return 0;
  if (!msg.text) return Math.max(0, timeline.length - 6);
  return timeline.length;
}

function traceToggleText(msg: ChatMessage, index: number) {
  if (expandedTraceKeys.value[index]) return "收起";
  const hiddenCount = hiddenTimelineCount(msg, index);
  return hiddenCount > 0 ? `查看全部 ${hiddenCount} 条` : "展开";
}

function eventLabel(event: AiTimelineEvent) {
  if (event.type === "reflection") return "回答核对";
  if (event.type === "tool_call") return "工具调用";
  if (event.type === "tool_result") return event.ok ? "工具完成" : "工具失败";
  return "执行步骤";
}

function reflectionStatusText(reflection?: AiReflectionSummary) {
  if (!reflection?.status) return "";
  if (reflection.status === "PASSED") {
    return (reflection.generationAttempts || 0) > 1 ? "已修正" : "已核对";
  }
  if (reflection.status === "DEGRADED") return "已降级";
  if (reflection.status === "HUMAN_REVIEW_REQUIRED") return "需确认";
  if (reflection.status === "CLARIFICATION_REQUIRED") return "待补充";
  if (reflection.status === "REFUSED") return "未通过";
  return "核对中";
}

function guardrailStageText(stage: AiGuardrailEvent["stage"]) {
  return {
    INPUT: "输入安全",
    TOOL: "操作安全",
    RAG: "知识安全",
    OUTPUT: "输出安全",
  }[stage];
}

function guardrailActionText(action: AiGuardrailEvent["action"]) {
  return action === "BLOCK" ? "已阻止" : action === "SANITIZE" ? "已处理" : "已检查";
}

function formatArguments(args?: Record<string, unknown>) {
  if (!args || Object.keys(args).length === 0) return "";
  return Object.entries(args)
    .map(([key, value]) => `${key}: ${String(value)}`)
    .join("，");
}

function citationMeta(citation: AiRagCitation) {
  const page = citation.pageStart
    ? citation.pageEnd && citation.pageEnd !== citation.pageStart
      ? `第 ${citation.pageStart}-${citation.pageEnd} 页`
      : `第 ${citation.pageStart} 页`
    : "";
  const version = citation.version ? `版本 ${citation.version}` : "";
  const chunk = citation.chunkId ? `片段 ${citation.chunkId}` : "";
  const parts = [citation.sourceType, citation.retrievalSource, citation.sectionTitle, page, version, chunk].filter(Boolean);
  return parts.join(" / ");
}

function businessEvidenceTitle(evidence: AiBusinessEvidence) {
  const labels: Record<string, string> = {
    get_my_order_detail: "订单详情",
    list_my_orders: "订单列表",
    get_product_detail: "商品详情",
    get_return_detail: "售后详情",
    list_my_returns: "售后列表",
  };
  return labels[evidence.toolName] || evidence.toolName;
}

function businessEvidenceSummary(evidence: AiBusinessEvidence) {
  const result = evidence.result;
  if (Array.isArray(result)) return `读取到 ${result.length} 条业务记录`;
  if (!result || typeof result !== "object") return "已读取业务系统数据";
  const data = result as Record<string, unknown>;
  const parts = [data.orderSn, data.name, data.statusText, data.status, data.payAmount].filter(
    (value) => value !== undefined && value !== null && value !== ""
  );
  return parts.length ? parts.join(" / ") : "已读取业务系统数据";
}

onMounted(async () => {
  loadMessages();
  await reconcilePendingActions();
});
watch(messages, saveMessages, { deep: true });
watch(open, (value) => {
  document.body.classList.toggle("ai-drawer-open", value);
  if (!value) streamAbortController?.abort();
});
window.addEventListener("storage", loadMessages);
onUnmounted(() => {
  streamAbortController?.abort();
  document.body.classList.remove("ai-drawer-open");
  window.removeEventListener("storage", loadMessages);
});
</script>

<template>
  <button v-if="!open" class="ai-trigger" @click="open = true">
    <span class="trigger-icon">AI</span>
    <span class="trigger-text">导购</span>
  </button>

  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="open" class="drawer-overlay" @click.self="open = false">
        <div class="drawer" @click.stop>
          <div class="drawer-header">
            <span class="header-title">
              <span class="header-ai-badge">AI</span>
              AIMall AI 导购
            </span>
            <button class="close-btn" @click="open = false">&times;</button>
          </div>

          <div class="drawer-body">
            <div ref="messageListRef" class="message-list">
              <div v-for="(msg, i) in messages" :key="i" :class="['msg-item', msg.role]">
                <div v-if="msg.role === 'ai' && msg.guardrails && msg.guardrails.length > 0" class="guardrail-list">
                  <div
                    v-for="(guardrail, guardrailIndex) in msg.guardrails"
                    :key="`${guardrail.stage}-${guardrail.action}-${guardrailIndex}`"
                    :class="['guardrail-notice', `action-${guardrail.action.toLowerCase()}`]"
                  >
                    <div class="guardrail-head">
                      <span class="guardrail-stage">{{ guardrailStageText(guardrail.stage) }}</span>
                      <span class="guardrail-status">{{ guardrailActionText(guardrail.action) }}</span>
                    </div>
                    <p class="guardrail-title">{{ guardrail.title }}</p>
                    <p class="guardrail-message">{{ guardrail.message }}</p>
                  </div>
                </div>

                <div v-if="msg.role === 'ai' && msg.timeline && msg.timeline.length > 0" class="trace-panel">
                  <button class="trace-toggle" @click="toggleTrace(i)">
                    <span>AI 工作过程</span>
                    <span class="trace-meta">{{ reflectionStatusText(msg.reflection) || msg.agentMode || "REACT_MVP" }}</span>
                    <span>{{ traceToggleText(msg, i) }}</span>
                  </button>
                  <div v-if="visibleTimeline(msg, i).length > 0" class="trace-body">
                    <div v-for="(event, eventIndex) in visibleTimeline(msg, i)" :key="eventIndex" class="trace-item">
                      <div
                        class="trace-dot"
                        :class="{
                          fail: event.type === 'tool_result' && event.ok === false,
                          warn: event.type === 'reflection' && event.status !== 'PASSED'
                        }"
                      ></div>
                      <div class="trace-content">
                        <div class="trace-row">
                          <span class="trace-label">{{ eventLabel(event) }}</span>
                          <span v-if="event.latencyMs" class="trace-latency">{{ event.latencyMs }}ms</span>
                        </div>
                        <p class="trace-title">{{ event.title || event.toolName || event.action }}</p>
                        <p v-if="event.content" class="trace-text">{{ event.content }}</p>
                        <p v-if="event.summary" class="trace-text">{{ event.summary }}</p>
                        <p v-if="event.arguments && Object.keys(event.arguments).length" class="trace-args">
                          {{ formatArguments(event.arguments) }}
                        </p>
                      </div>
                    </div>
                    <p v-if="msg.traceId" class="trace-id">Trace ID：{{ msg.traceId }}</p>
                  </div>
                </div>

                <div v-if="msg.role === 'ai' && msg.businessEvidence && msg.businessEvidence.length > 0" class="business-evidence-panel">
                  <p class="citation-title">业务依据</p>
                  <div v-for="(evidence, evidenceIndex) in msg.businessEvidence" :key="`${evidence.toolName}-${evidenceIndex}`" class="business-evidence-row">
                    <span class="business-evidence-name">{{ businessEvidenceTitle(evidence) }}</span>
                    <span class="business-evidence-summary">{{ businessEvidenceSummary(evidence) }}</span>
                  </div>
                </div>

                <div v-if="msg.role === 'ai' && msg.ragCitations && msg.ragCitations.length > 0" class="citation-panel">
                  <div class="citation-header">
                    <div class="citation-title-wrap">
                      <p class="citation-title">引用依据</p>
                      <span class="citation-count">共 {{ msg.ragCitations.length }} 条</span>
                    </div>
                    <button class="citation-toggle" @click="toggleCitations(i)">
                      {{ citationToggleText(i) }}
                    </button>
                  </div>
                  <p v-if="!expandedCitationKeys[i]" class="citation-collapsed-tip">已收起，点击展开查看完整依据。</p>
                  <div v-for="citation in visibleCitations(msg, i)" :key="citation.id" class="citation-card">
                    <div class="citation-head">
                      <span class="citation-index">[{{ citation.id }}]</span>
                      <span class="citation-name">{{ citation.title }}</span>
                    </div>
                    <p v-if="citationMeta(citation)" class="citation-meta">{{ citationMeta(citation) }}</p>
                    <p v-if="citation.snippet" class="citation-snippet">{{ citation.snippet }}</p>
                    <p class="citation-source">{{ citation.source }}</p>
                  </div>
                </div>

                <div v-if="msg.text" class="msg-bubble">
                  <span>{{ msg.text }}</span>
                </div>
                <div v-else-if="msg.role === 'ai' && (!msg.timeline || msg.timeline.length === 0)" class="thinking-line">
                  正在理解你的需求...
                </div>

                <div v-if="msg.role === 'ai' && msg.pendingActions && msg.pendingActions.length > 0" class="pending-action-list">
                  <section
                    v-for="action in msg.pendingActions"
                    :key="action.actionId"
                    :class="['pending-action-card', `status-${action.status.toLowerCase()}`]"
                  >
                    <div class="pending-action-head">
                      <div>
                        <p class="pending-action-title">{{ action.title }}</p>
                        <p class="pending-action-summary">{{ action.summary }}</p>
                      </div>
                      <span class="pending-action-status">{{ pendingActionStatusText(action) }}</span>
                    </div>
                    <p v-if="action.status === 'PENDING' && pendingActionExpiry(action)" class="pending-action-expiry">
                      请在 {{ pendingActionExpiry(action) }} 前确认
                    </p>
                    <p v-if="action.error" class="pending-action-error">{{ action.error }}</p>
                    <p v-if="action.status === 'SUCCEEDED'" class="pending-action-success">
                      操作已由商城服务确认完成{{ action.replayed ? "（幂等回放）" : "" }}
                    </p>
                    <div v-if="action.status === 'PENDING'" class="pending-action-buttons">
                      <button
                        class="pending-action-reject"
                        :disabled="isPendingActionLoading(i, action)"
                        @click="submitPendingAction(i, action, 'reject')"
                      >
                        取消
                      </button>
                      <button
                        class="pending-action-confirm"
                        :disabled="isPendingActionLoading(i, action)"
                        @click="submitPendingAction(i, action, 'confirm')"
                      >
                        {{ action.retryable ? "安全重试" : "确认执行" }}
                      </button>
                    </div>
                  </section>
                </div>

                <p v-if="msg.degraded && msg.traceId" class="trace-tip">服务降级，追踪编号：{{ msg.traceId }}</p>

                <div v-if="msg.role === 'ai' && msg.text && msg.traceId" class="feedback-actions">
                  <button :class="{ active: msg.feedbackType === 'THUMBS_UP' }" @click="submitFeedback(i, 'THUMBS_UP')">有帮助</button>
                  <button :class="{ active: msg.feedbackType === 'THUMBS_DOWN' }" @click="submitFeedback(i, 'THUMBS_DOWN')">不准确</button>
                </div>

                <div v-if="msg.relatedProducts && msg.relatedProducts.length > 0" class="related-products">
                  <p class="related-title">推荐商品</p>
                  <div v-for="p in msg.relatedProducts.slice(0, 3)" :key="p.productId" class="related-card" @click="goProduct(p.productId)">
                    <span class="related-name">{{ p.name }}</span>
                    <span class="related-price">&yen;{{ p.price }}</span>
                  </div>
                </div>

                <div v-if="msg.suggestedActions && msg.suggestedActions.length > 0" class="suggested-actions">
                  <button v-for="(action, j) in msg.suggestedActions" :key="j" class="action-btn" @click="handleAction(action)">
                    {{ action.label }}
                  </button>
                </div>
              </div>

              <div v-if="messages.length === 0 && !sending" class="empty-state">
                <p class="empty-title">你好，我是 AI 购物助手</p>
                <p class="empty-desc">可以帮你推荐商品、解答疑问、比较商品</p>
                <div class="recommend-questions">
                  <button v-for="(q, i) in recommendedQuestions" :key="i" class="q-btn" @click="askQuestion(q)">
                    {{ q }}
                  </button>
                </div>
              </div>
            </div>
          </div>

          <div class="drawer-footer">
            <div class="input-area">
              <input v-model="input" type="text" placeholder="输入消息..." @keydown.enter="send" :disabled="sending" />
              <button class="send-btn" @click="send" :disabled="sending || !input.trim()">{{ sending ? "..." : "发送" }}</button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
:global(body.ai-drawer-open) {
  overflow: hidden;
}

.ai-trigger {
  position: fixed;
  bottom: 24px;
  right: 24px;
  height: 52px;
  border-radius: 26px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  cursor: pointer;
  z-index: 1000;
  box-shadow: 0 4px 16px rgba(255, 90, 61, 0.4);
  transition: all 0.2s;
  display: flex;
  align-items: center;
  padding: 0 20px;
  gap: 6px;
}
.ai-trigger:hover { transform: scale(1.03); box-shadow: 0 6px 20px rgba(255, 90, 61, 0.5); }

.trigger-icon {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 1px;
}
.trigger-text { font-size: 14px; font-weight: 500; }
.trigger-close { font-size: 24px; line-height: 1; }

.drawer-overlay {
  position: fixed;
  top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(0,0,0,0.3);
  z-index: 999;
  display: flex;
  justify-content: flex-end;
}

.drawer {
  width: 420px;
  height: 100%;
  background: var(--color-bg-white);
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 20px rgba(0,0,0,0.1);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: var(--color-primary);
  color: #fff;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.header-ai-badge {
  font-size: 11px;
  font-weight: 800;
  background: rgba(255,255,255,0.25);
  padding: 2px 8px;
  border-radius: 4px;
}

.close-btn {
  background: none;
  border: none;
  color: #fff;
  font-size: 24px;
  cursor: pointer;
  padding: 0 4px;
  opacity: 0.8;
}
.close-btn:hover { opacity: 1; }

.drawer-body {
  flex: 1;
  overflow: hidden;
}

.message-list {
  height: 100%;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg-item { display: flex; flex-direction: column; }
.msg-item.user { align-items: flex-end; }

.msg-bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.msg-item.user .msg-bubble { background: var(--color-primary); color: #fff; }
.msg-item.ai .msg-bubble { background: var(--color-bg); color: var(--color-text-body); margin-top: 8px; }

.thinking-line {
  max-width: 85%;
  margin-top: 4px;
  font-size: 12px;
  color: #8a8f98;
}

.guardrail-list {
  width: 92%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 2px 0 6px;
}

.guardrail-notice {
  padding: 9px 10px;
  border-left: 3px solid #6b7280;
  border-radius: 6px;
  background: #f5f6f8;
}

.guardrail-notice.action-sanitize {
  border-left-color: #0f766e;
  background: #f2f8f7;
}

.guardrail-notice.action-block {
  border-left-color: #b45309;
  background: #fff8ed;
}

.guardrail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.guardrail-stage,
.guardrail-status {
  font-size: 11px;
  color: #737b88;
}

.guardrail-status {
  font-weight: 600;
}

.guardrail-title {
  margin: 3px 0 0;
  color: #3f4753;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
}

.guardrail-message {
  margin: 2px 0 0;
  color: #737b88;
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.trace-panel {
  max-width: 92%;
  margin-top: 2px;
  color: #8a8f98;
}

.trace-toggle {
  width: 100%;
  border: none;
  background: transparent;
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 8px;
  align-items: center;
  padding: 0 0 6px;
  cursor: pointer;
  color: #8a8f98;
  font-size: 12px;
  text-align: left;
}

.trace-toggle:hover {
  color: #6f7580;
}

.trace-meta {
  color: #a1a6af;
  font-size: 11px;
}

.trace-body {
  padding: 0 0 4px;
  max-height: 220px;
  overflow-y: auto;
}

.trace-item {
  position: relative;
  display: grid;
  grid-template-columns: 12px 1fr;
  gap: 8px;
  padding-bottom: 8px;
}

.trace-item:not(:last-child)::before {
  content: "";
  position: absolute;
  left: 5px;
  top: 14px;
  bottom: 0;
  width: 1px;
  background: #d7dbe2;
}

.trace-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #a1a6af;
  margin-top: 5px;
  z-index: 1;
}
.trace-dot.fail { background: #dc2626; }
.trace-dot.warn { background: #b45309; }

.trace-content {
  min-width: 0;
}

.trace-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 1px;
}

.trace-label,
.trace-latency {
  font-size: 11px;
  color: #a1a6af;
}

.trace-title {
  margin: 0;
  font-size: 12px;
  color: #7b818c;
  font-weight: 500;
  line-height: 1.5;
}

.trace-text,
.trace-args,
.trace-id,
.trace-tip {
  margin: 2px 0 0;
  font-size: 11px;
  color: #8a8f98;
  line-height: 1.5;
  word-break: break-word;
}

.trace-args {
  padding: 4px 0;
  background: transparent;
}

.trace-tip {
  max-width: 85%;
}

.feedback-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
  max-width: 85%;
}

.feedback-actions button {
  border: 1px solid #d7dbe2;
  background: #fff;
  color: #7b818c;
  border-radius: 14px;
  padding: 4px 10px;
  font-size: 11px;
  cursor: pointer;
}

.feedback-actions button.active {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.citation-panel {
  max-width: 92%;
  margin-top: 6px;
  padding: 8px 10px;
  border-left: 2px solid #d7dbe2;
  background: #f7f8fa;
  border-radius: 6px;
  color: #6f7580;
}

.business-evidence-panel {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-left: 3px solid #a8b0bb;
  background: #f5f6f8;
  color: #596273;
}

.business-evidence-row {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.business-evidence-name {
  flex: 0 0 auto;
  font-weight: 600;
}

.business-evidence-summary {
  min-width: 0;
  overflow-wrap: anywhere;
}

.citation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.citation-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.citation-title {
  margin: 0;
  font-size: 12px;
  color: #7b818c;
  font-weight: 600;
}

.citation-count,
.citation-collapsed-tip {
  color: #9aa1ad;
  font-size: 11px;
}

.citation-toggle {
  border: none;
  background: transparent;
  color: #7b818c;
  cursor: pointer;
  font-size: 11px;
  padding: 2px 0;
  flex: 0 0 auto;
}

.citation-toggle:hover {
  color: var(--color-primary);
}

.citation-collapsed-tip {
  margin: 5px 0 0;
}

.citation-card {
  padding: 6px 0;
  border-top: 1px solid #e4e7ec;
}
.citation-card:first-of-type { border-top: none; padding-top: 0; }

.citation-head {
  display: flex;
  gap: 6px;
  align-items: baseline;
  font-size: 12px;
  line-height: 1.5;
}

.citation-index {
  color: var(--color-primary);
  font-weight: 700;
}

.citation-name {
  color: #5f6672;
  font-weight: 600;
}

.citation-meta,
.citation-snippet,
.citation-source {
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.5;
  word-break: break-word;
}

.citation-meta,
.citation-source {
  color: #9aa1ad;
}

.citation-snippet {
  color: #747b87;
}

.empty-state { text-align: center; padding-top: 40px; }
.empty-title { font-size: 16px; color: var(--color-text); margin-bottom: 8px; }
.empty-desc { font-size: 13px; color: var(--color-text-muted); margin-bottom: 20px; }

.recommend-questions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 8px;
}

.q-btn {
  padding: 10px 14px;
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--color-text-body);
  cursor: pointer;
  text-align: left;
  transition: all 0.2s;
}
.q-btn:hover {
  border-color: var(--color-primary);
  background: #ffe8e3;
}

.related-products { margin-top: 8px; max-width: 85%; }
.related-title { font-size: 12px; color: var(--color-text-muted); margin-bottom: 6px; }

.related-card {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--color-primary-light);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  margin-bottom: 6px;
}
.related-card:hover { background: #ffe8e3; }
.related-name { color: var(--color-text); }
.related-price { color: var(--color-primary); font-weight: 600; }

.suggested-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  max-width: 85%;
}

.action-btn {
  padding: 6px 14px;
  border: 1px solid var(--color-primary);
  border-radius: 20px;
  background: var(--color-bg-white);
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.action-btn:hover { background: var(--color-primary); color: #fff; }

.pending-action-list {
  width: 92%;
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pending-action-card {
  padding: 12px;
  border: 1px solid #d8dce3;
  border-left: 3px solid #f59e0b;
  border-radius: 6px;
  background: #fff;
}

.pending-action-card.status-succeeded { border-left-color: #16a34a; background: #f6fbf7; }
.pending-action-card.status-failed,
.pending-action-card.status-expired { border-left-color: #dc2626; background: #fff8f8; }
.pending-action-card.status-rejected { border-left-color: #8a8f98; background: #f7f8fa; }
.pending-action-card.status-executing { border-left-color: #2563eb; background: #f7faff; }

.pending-action-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.pending-action-title {
  margin: 0;
  color: #303744;
  font-size: 13px;
  font-weight: 600;
}

.pending-action-summary,
.pending-action-expiry,
.pending-action-error,
.pending-action-success {
  margin: 4px 0 0;
  color: #697180;
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.pending-action-status {
  flex: 0 0 auto;
  color: #7b818c;
  font-size: 11px;
  font-weight: 600;
}

.pending-action-error { color: #b91c1c; }
.pending-action-success { color: #15803d; }

.pending-action-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.pending-action-buttons button {
  min-height: 32px;
  padding: 6px 12px;
  border-radius: 5px;
  font-size: 12px;
  cursor: pointer;
}

.pending-action-reject {
  border: 1px solid #cfd4dc;
  background: #fff;
  color: #596273;
}

.pending-action-confirm {
  border: 1px solid var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.pending-action-buttons button:disabled {
  cursor: wait;
  opacity: 0.55;
}

.drawer-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--color-border);
}

.input-area { display: flex; gap: 8px; }
.input-area input { flex: 1; padding: 10px 14px; }
.input-area input:focus { border-color: var(--color-primary); }

.send-btn {
  padding: 10px 20px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  cursor: pointer;
}
.send-btn:disabled { background: #d1d5db; cursor: not-allowed; }

.drawer-enter-active, .drawer-leave-active { transition: all 0.3s ease; }
.drawer-enter-from .drawer, .drawer-leave-to .drawer { transform: translateX(100%); }
.drawer-enter-from .drawer-overlay, .drawer-leave-to .drawer-overlay { opacity: 0; }

@media (max-width: 480px) {
  .drawer { width: 100%; }
  .ai-trigger { right: 16px; bottom: 16px; }
}
</style>
