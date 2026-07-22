<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { useRouter } from "vue-router";
import { resetPassword, sendEmailCode } from "../api/userApi";
import AuthLayout from "../components/layout/AuthLayout.vue";

const router = useRouter();
const email = ref("");
const code = ref("");
const password = ref("");
const confirmPassword = ref("");
const errorMsg = ref("");
const infoMsg = ref("");
const loading = ref(false);
const countdown = ref(0);
let timer: number | undefined;

function validEmail(value: string) { return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value); }
function validPassword(value: string) {
  return value.length >= 12 && value.length <= 72 && /[A-Z]/.test(value) && /[a-z]/.test(value)
    && /\d/.test(value) && /[^A-Za-z0-9]/.test(value);
}

async function sendCode() {
  errorMsg.value = "";
  if (!validEmail(email.value.trim())) return void (errorMsg.value = "请输入有效邮箱");
  loading.value = true;
  try {
    const response = await sendEmailCode({ email: email.value.trim().toLowerCase(), purpose: "PASSWORD_RESET" });
    if (response.data.code !== 0) throw new Error(response.data.message || "发送失败");
    infoMsg.value = "若该邮箱已注册，验证码邮件已发送";
    countdown.value = response.data.data.cooldownSeconds || 60;
    window.clearInterval(timer);
    timer = window.setInterval(() => { if (--countdown.value <= 0) window.clearInterval(timer); }, 1000);
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : "发送失败";
  } finally { loading.value = false; }
}

async function submit() {
  errorMsg.value = "";
  if (!validEmail(email.value.trim())) return void (errorMsg.value = "请输入有效邮箱");
  if (!/^\d{6}$/.test(code.value)) return void (errorMsg.value = "请输入 6 位验证码");
  if (!validPassword(password.value)) return void (errorMsg.value = "新密码不符合安全规则");
  if (password.value !== confirmPassword.value) return void (errorMsg.value = "两次输入的密码不一致");
  loading.value = true;
  try {
    const response = await resetPassword({
      email: email.value.trim().toLowerCase(), verificationCode: code.value, newPassword: password.value,
    });
    if (response.data.code !== 0) throw new Error(response.data.message || "重置失败");
    router.replace({ path: "/login", query: { reset: "success" } });
  } catch (error) {
    errorMsg.value = error instanceof Error ? error.message : "重置失败";
  } finally { loading.value = false; }
}

onBeforeUnmount(() => window.clearInterval(timer));
</script>

<template>
  <AuthLayout>
    <div class="reset-form">
      <h1>重置密码</h1><p class="subtitle">通过注册邮箱验证身份</p>
      <form @submit.prevent="submit">
        <div class="field"><label>邮箱</label><input v-model="email" type="email" autocomplete="email" /></div>
        <div class="field"><label>邮箱验证码</label><div class="code-row"><input v-model="code" maxlength="6" inputmode="numeric" /><button type="button" class="btn-default" :disabled="loading || countdown > 0" @click="sendCode">{{ countdown ? `${countdown}s` : "发送验证码" }}</button></div></div>
        <div class="field"><label>新密码</label><input v-model="password" type="password" autocomplete="new-password" /></div>
        <div class="field"><label>确认新密码</label><input v-model="confirmPassword" type="password" autocomplete="new-password" /></div>
        <p v-if="infoMsg" class="info-msg">{{ infoMsg }}</p><p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        <button class="btn-primary submit" type="submit" :disabled="loading">{{ loading ? "提交中" : "重置密码" }}</button>
      </form>
      <p class="switch-link"><router-link to="/login">返回登录</router-link></p>
    </div>
  </AuthLayout>
</template>

<style scoped>
.reset-form { width: 100%; max-width: 390px; } h1 { font-size: 26px; color: var(--color-text); }
.subtitle { color: var(--color-text-muted); margin: 6px 0 24px; }.field { margin-bottom: 16px; }.field label { display:block;font-weight:500;margin-bottom:5px }.field input{width:100%;min-width:0}
.code-row{display:grid;grid-template-columns:minmax(0,1fr) 112px;gap:8px}.code-row button{padding:9px 8px;white-space:nowrap}.submit{width:100%;padding:12px}.switch-link{text-align:center;margin-top:18px}
.error-msg,.info-msg{padding:8px 10px;margin-bottom:12px;border:1px solid;border-radius:4px}.error-msg{color:#b42318;background:#fff1f0;border-color:#fecdca}.info-msg{color:#067647;background:#ecfdf3;border-color:#abefc6}
@media(max-width:420px){.code-row{grid-template-columns:1fr}}
</style>
