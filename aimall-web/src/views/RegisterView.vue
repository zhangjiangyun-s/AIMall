<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { useRouter } from "vue-router";
import { login, register, sendEmailCode } from "../api/userApi";
import AuthLayout from "../components/layout/AuthLayout.vue";

const router = useRouter();
const username = ref("");
const nickname = ref("");
const email = ref("");
const verificationCode = ref("");
const password = ref("");
const confirmPassword = ref("");
const agreeTerms = ref(false);
const errorMsg = ref("");
const infoMsg = ref("");
const loading = ref(false);
const sendingCode = ref(false);
const countdown = ref(0);
let countdownTimer: number | undefined;

function validEmail(value: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

function validPassword(value: string) {
  return value.length >= 12 && value.length <= 72 && /[A-Z]/.test(value) && /[a-z]/.test(value)
    && /\d/.test(value) && /[^A-Za-z0-9]/.test(value);
}

function startCountdown(seconds: number) {
  countdown.value = seconds;
  window.clearInterval(countdownTimer);
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) window.clearInterval(countdownTimer);
  }, 1000);
}

async function handleSendCode() {
  errorMsg.value = "";
  infoMsg.value = "";
  const target = email.value.trim().toLowerCase();
  if (!validEmail(target)) {
    errorMsg.value = "请输入有效邮箱";
    return;
  }
  sendingCode.value = true;
  try {
    const response = await sendEmailCode({ email: target, purpose: "REGISTER" });
    if (response.data.code !== 0) throw new Error(response.data.message || "验证码发送失败");
    infoMsg.value = "验证码已发送，请在 MailHog 中查看";
    startCountdown(response.data.data.cooldownSeconds || 60);
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : "验证码发送失败";
  } finally {
    sendingCode.value = false;
  }
}

async function handleRegister() {
  errorMsg.value = "";
  infoMsg.value = "";
  if (username.value.trim().length < 3) return void (errorMsg.value = "用户名至少 3 位");
  if (!validEmail(email.value.trim())) return void (errorMsg.value = "请输入有效邮箱");
  if (!/^\d{6}$/.test(verificationCode.value)) return void (errorMsg.value = "请输入 6 位邮箱验证码");
  if (!validPassword(password.value)) {
    return void (errorMsg.value = "密码需为 12-72 位，并包含大小写字母、数字和特殊字符");
  }
  if (password.value !== confirmPassword.value) return void (errorMsg.value = "两次输入的密码不一致");
  if (!agreeTerms.value) return void (errorMsg.value = "请阅读并同意用户协议");

  loading.value = true;
  try {
    const response = await register({
      username: username.value.trim(),
      password: password.value,
      nickname: nickname.value.trim() || undefined,
      email: email.value.trim().toLowerCase(),
      verificationCode: verificationCode.value,
    });
    if (response.data.code !== 0) throw new Error(response.data.message || "注册失败");
    const loginResponse = await login({ username: username.value.trim(), password: password.value });
    if (loginResponse.data.code !== 0) throw new Error(loginResponse.data.message || "自动登录失败，请手动登录");
    localStorage.removeItem("aimall_login_session_id");
    localStorage.removeItem("aimall_login_session_token");
    localStorage.setItem("token", loginResponse.data.data.token);
    localStorage.setItem("userInfo", JSON.stringify(loginResponse.data.data.userInfo));
    localStorage.setItem("aimall_login_session_id", `login_${crypto.randomUUID()}`);
    localStorage.setItem("aimall_login_session_token", loginResponse.data.data.token);
    window.dispatchEvent(new Event("storage"));
    router.replace("/");
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : "注册失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}

onBeforeUnmount(() => window.clearInterval(countdownTimer));
</script>

<template>
  <AuthLayout>
    <div class="register-form">
      <h1>创建 AIMall 账号</h1>
      <p class="subtitle">使用邮箱验证账号并保护密码安全</p>
      <form @submit.prevent="handleRegister">
        <div class="field"><label>用户名</label><input v-model="username" autocomplete="username" :disabled="loading" /></div>
        <div class="field"><label>昵称（可选）</label><input v-model="nickname" :disabled="loading" /></div>
        <div class="field"><label>邮箱</label><input v-model="email" type="email" autocomplete="email" :disabled="loading" /></div>
        <div class="field">
          <label>邮箱验证码</label>
          <div class="code-row">
            <input v-model="verificationCode" inputmode="numeric" maxlength="6" autocomplete="one-time-code" :disabled="loading" />
            <button type="button" class="btn-default code-button" :disabled="sendingCode || countdown > 0" @click="handleSendCode">
              {{ countdown > 0 ? `${countdown}s` : sendingCode ? "发送中" : "发送验证码" }}
            </button>
          </div>
        </div>
        <div class="field"><label>密码</label><input v-model="password" type="password" autocomplete="new-password" :disabled="loading" /></div>
        <p class="password-hint">12-72 位，包含大小写字母、数字和特殊字符</p>
        <div class="field"><label>确认密码</label><input v-model="confirmPassword" type="password" autocomplete="new-password" :disabled="loading" /></div>
        <label class="agreement"><input v-model="agreeTerms" type="checkbox" /><span>我已阅读并同意用户协议</span></label>
        <p v-if="infoMsg" class="info-msg">{{ infoMsg }}</p>
        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        <button class="btn-primary submit" type="submit" :disabled="loading">{{ loading ? "注册中" : "创建账号" }}</button>
      </form>
      <p class="switch-link">已有账号？<router-link to="/login">去登录</router-link></p>
    </div>
  </AuthLayout>
</template>

<style scoped>
.register-form { width: 100%; max-width: 400px; }
h1 { font-size: 26px; color: var(--color-text); margin-bottom: 6px; }
.subtitle { color: var(--color-text-muted); margin-bottom: 22px; }
.field { margin-bottom: 14px; }
.field label { display: block; font-weight: 500; margin-bottom: 5px; }
.field input { width: 100%; min-width: 0; }
.code-row { display: grid; grid-template-columns: minmax(0, 1fr) 112px; gap: 8px; }
.code-button { padding: 9px 10px !important; white-space: nowrap; }
.password-hint { margin: -8px 0 14px; color: var(--color-text-muted); font-size: 12px; }
.agreement { display: flex; align-items: center; gap: 7px; margin: 4px 0 16px; color: var(--color-text-muted); }
.agreement input { width: 16px; height: 16px; }
.error-msg, .info-msg { padding: 8px 10px; margin-bottom: 12px; border: 1px solid; border-radius: 4px; font-size: 13px; }
.error-msg { color: #b42318; background: #fff1f0; border-color: #fecdca; }
.info-msg { color: #067647; background: #ecfdf3; border-color: #abefc6; }
.submit { width: 100%; padding: 12px; }
.switch-link { margin-top: 18px; text-align: center; color: var(--color-text-muted); }
@media (max-width: 420px) { .code-row { grid-template-columns: 1fr; } .code-button { width: 100%; } }
</style>
