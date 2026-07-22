<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AiAssistantDrawer from "./components/ai/AiAssistantDrawer.vue";
import AppFooter from "./components/layout/AppFooter.vue";
import AppHeader from "./components/layout/AppHeader.vue";
import AppTopbar from "./components/layout/AppTopbar.vue";
import CategoryNav from "./components/layout/CategoryNav.vue";
import { clearAiSession } from "./api/aiApi";
import { logout } from "./api/userApi";

const route = useRoute();
const router = useRouter();
const token = ref("");
const userNickname = ref("");

const isAuthPage = computed(() => route.name === "Login" || route.name === "Register");
const isLoggedIn = computed(() => !!token.value);

function syncAuthState() {
  token.value = localStorage.getItem("token") || "";

  try {
    const raw = localStorage.getItem("userInfo");
    if (raw) {
      const info = JSON.parse(raw);
      userNickname.value = info.nickname || info.username || "";
      return;
    }
  } catch {
    // ignore invalid cached user info
  }

  userNickname.value = "";
}

async function handleLogout() {
  const loginSessionId = localStorage.getItem("aimall_login_session_id");
  const rawUserInfo = localStorage.getItem("userInfo");
  if (loginSessionId && rawUserInfo) {
    try {
      const info = JSON.parse(rawUserInfo);
      const userId = String(info.id || info.username || "guest");
      localStorage.removeItem(`aimall_ai_conversation_${userId}_${loginSessionId}`);
    } catch {
      // ignore invalid cached user info
    }
  }
  if (loginSessionId) {
    try {
      await clearAiSession(`ai_session_${loginSessionId}`);
    } catch {
      // The local login boundary still changes, so stale server memory is no longer addressable.
    }
  }
  try {
    await logout();
  } finally {
    localStorage.removeItem("token");
    localStorage.removeItem("userInfo");
    localStorage.removeItem("aimall_login_session_id");
    localStorage.removeItem("aimall_login_session_token");
    syncAuthState();
    router.push("/login");
  }
}

onMounted(syncAuthState);
watch(() => route.fullPath, syncAuthState);
window.addEventListener("storage", syncAuthState);
</script>

<template>
  <template v-if="isAuthPage">
    <router-view />
  </template>

  <template v-else>
    <div id="app-layout">
      <AppTopbar :isLoggedIn="isLoggedIn" :userNickname="userNickname" @logout="handleLogout" />
      <AppHeader :isLoggedIn="isLoggedIn" :userNickname="userNickname" />
      <CategoryNav />
      <main class="app-main">
        <router-view />
      </main>
      <AppFooter />
      <AiAssistantDrawer />
    </div>
  </template>
</template>

<style scoped>
#app-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-main {
  flex: 1;
}
</style>
