<script setup lang="ts">
import { ref, computed } from "vue";
import { useRoute } from "vue-router";
import { sendAiMessage, type PageType } from "../api/aiApi";

const route = useRoute();
const open = ref(false);
const messages = ref<{ role: "user" | "ai"; text: string }[]>([]);
const input = ref("");
const sending = ref(false);

function toggle() {
  open.value = !open.value;
}

function buildPageContext() {
  const name = route.name as string;
  if (name === "ProductDetail") {
    return { pageType: "PRODUCT_DETAIL" as PageType, productId: Number(route.params.id) };
  }
  if (name === "OrderDetail") {
    return { pageType: "ORDER_DETAIL" as PageType, orderId: Number(route.params.id) };
  }
  return { pageType: "GENERAL" as PageType };
}

async function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;

  input.value = "";
  messages.value.push({ role: "user", text });
  sending.value = true;

  try {
    const res = await sendAiMessage({
      message: text,
      sessionId: "ai_session_mock",
      pageContext: buildPageContext(),
    });
    messages.value.push({ role: "ai", text: res.data.answer });
  } catch {
    // mock fallback when backend is unavailable
    messages.value.push({
      role: "ai",
      text: `收到消息："${text}"。当前页面：${buildPageContext().pageType}。AI 服务暂未接入。`,
    });
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <div class="ai-assistant">
    <button class="ai-trigger" @click="toggle" v-if="!open">AI</button>

    <div class="ai-panel" v-if="open">
      <div class="ai-panel-header">
        <span>AI 助手</span>
        <button class="ai-close" @click="toggle">&times;</button>
      </div>

      <div class="ai-messages">
        <div
          v-for="(msg, i) in messages"
          :key="i"
          :class="['ai-msg', msg.role]"
        >
          <div class="ai-bubble">{{ msg.text }}</div>
        </div>
        <div v-if="messages.length === 0" class="ai-empty">
          你好！有什么可以帮助你的？
        </div>
      </div>

      <div class="ai-input-area">
        <input
          v-model="input"
          type="text"
          placeholder="输入消息..."
          @keydown.enter="send"
          :disabled="sending"
        />
        <button class="primary" @click="send" :disabled="sending">
          {{ sending ? "..." : "发送" }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-assistant {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 999;
}

.ai-trigger {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.4);
}

.ai-trigger:hover {
  background: #66b1ff;
}

.ai-panel {
  width: 360px;
  height: 480px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #409eff;
  color: #fff;
  font-weight: 600;
}

.ai-close {
  background: none;
  color: #fff;
  font-size: 20px;
  padding: 0 4px;
  line-height: 1;
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
}

.ai-msg {
  margin-bottom: 12px;
}

.ai-msg.user {
  text-align: right;
}

.ai-bubble {
  display: inline-block;
  max-width: 80%;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.ai-msg.user .ai-bubble {
  background: #409eff;
  color: #fff;
  text-align: left;
}

.ai-msg.ai .ai-bubble {
  background: #f0f2f5;
  color: #333;
  text-align: left;
}

.ai-empty {
  text-align: center;
  color: #999;
  margin-top: 60px;
}

.ai-input-area {
  display: flex;
  padding: 8px 12px;
  border-top: 1px solid #e4e7ed;
  gap: 8px;
}

.ai-input-area input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 14px;
  outline: none;
}

.ai-input-area input:focus {
  border-color: #409eff;
}
</style>
