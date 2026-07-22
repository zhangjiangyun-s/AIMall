<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { login } from "../api/userApi";
import AuthLayout from "../components/layout/AuthLayout.vue";

const router = useRouter();
const username = ref("");
const password = ref("");
const rememberMe = ref(false);
const errorMsg = ref("");
const loading = ref(false);

onMounted(() => {
  if (localStorage.getItem("token")) {
    router.replace("/");
  }
});

async function handleLogin() {
  errorMsg.value = "";

  if (!username.value.trim()) {
    errorMsg.value = "请输入用户名";
    return;
  }

  if (!password.value.trim()) {
    errorMsg.value = "请输入密码";
    return;
  }

  loading.value = true;
  try {
    const res = await login({ username: username.value.trim(), password: password.value.trim() });
    if (res.data.code === 0) {
      localStorage.removeItem("aimall_login_session_id");
      localStorage.removeItem("aimall_login_session_token");
      localStorage.setItem("token", res.data.data.token);
      localStorage.setItem("userInfo", JSON.stringify(res.data.data.userInfo));
      localStorage.setItem(
        "aimall_login_session_id",
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? `login_${crypto.randomUUID()}`
          : `login_${Date.now()}_${Math.random().toString(16).slice(2)}`
      );
      localStorage.setItem("aimall_login_session_token", res.data.data.token);
      window.dispatchEvent(new Event("storage"));
      router.replace("/");
    } else {
      errorMsg.value = res.data.message || "用户名或密码错误";
    }
  } catch {
    errorMsg.value = "网络连接失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <AuthLayout>
    <div class="login-form">
      <h1 class="form-title">欢迎回来</h1>
      <p class="form-subtitle">登录 AIMall，继续你的智能购物体验</p>

      <form @submit.prevent="handleLogin">
        <div class="field">
          <label>用户名</label>
          <input v-model="username" type="text" placeholder="请输入用户名" :disabled="loading" />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="password" type="password" placeholder="请输入密码" :disabled="loading" />
        </div>

        <div class="field-row">
          <label class="checkbox-label">
            <input v-model="rememberMe" type="checkbox" />
            <span>记住我</span>
          </label>
          <router-link to="/forgot-password" class="forgot-link">忘记密码？</router-link>
        </div>

        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>

        <button type="submit" class="btn-primary submit-btn" :disabled="loading">
          <span v-if="loading" class="btn-loading">登录中...</span>
          <span v-else>登录</span>
        </button>
      </form>

      <p class="switch-link">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </p>
    </div>
  </AuthLayout>
</template>

<style scoped>
.login-form {
  width: 100%;
  max-width: 380px;
}

.form-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: 8px;
}

.form-subtitle {
  font-size: 14px;
  color: var(--color-text-muted);
  margin-bottom: 32px;
}

.field {
  margin-bottom: 20px;
}

.field label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 6px;
}

.field input {
  width: 100%;
  padding: 11px 14px;
}

.field-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-muted);
  cursor: pointer;
}

.checkbox-label input {
  width: 16px;
  height: 16px;
  accent-color: var(--color-primary);
}

.forgot-link {
  font-size: 13px;
  color: var(--color-text-muted);
}

.forgot-link:hover {
  color: var(--color-primary);
}

.error-msg {
  font-size: 13px;
  color: var(--color-danger);
  margin-bottom: 16px;
  padding: 8px 12px;
  background: #fef2f2;
  border-radius: 6px;
  border: 1px solid #fecaca;
}

.submit-btn {
  width: 100%;
  padding: 12px;
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 24px;
}

.btn-loading {
  opacity: 0.7;
}

.switch-link {
  text-align: center;
  font-size: 14px;
  color: var(--color-text-muted);
}

.switch-link a {
  font-weight: 500;
}
</style>
