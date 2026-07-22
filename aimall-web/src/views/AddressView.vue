<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  createAddress,
  deleteAddress,
  fetchAddresses,
  setDefaultAddress,
  updateAddress,
  type Address,
} from "../api/addressApi";

type AddressForm = Omit<Address, "id" | "fullAddress">;

const addresses = ref<Address[]>([]);
const loading = ref(false);
const saving = ref(false);
const editingId = ref<number | null>(null);
const msg = ref("");
const form = reactive<AddressForm>({
  name: "",
  phone: "",
  province: "",
  city: "",
  region: "",
  detailAddress: "",
  defaultStatus: 0,
});

const submitLabel = computed(() => (editingId.value ? "保存修改" : "新增地址"));

function resetForm() {
  editingId.value = null;
  form.name = "";
  form.phone = "";
  form.province = "";
  form.city = "";
  form.region = "";
  form.detailAddress = "";
  form.defaultStatus = addresses.value.length === 0 ? 1 : 0;
}

async function loadAddresses() {
  loading.value = true;
  msg.value = "";
  try {
    const res = await fetchAddresses();
    if (res.data.code === 0) {
      addresses.value = res.data.data;
      if (!editingId.value) {
        resetForm();
      }
    } else {
      msg.value = res.data.message || "地址加载失败";
    }
  } catch {
    msg.value = "地址加载失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}

function editAddress(address: Address) {
  editingId.value = address.id;
  form.name = address.name;
  form.phone = address.phone;
  form.province = address.province;
  form.city = address.city;
  form.region = address.region;
  form.detailAddress = address.detailAddress;
  form.defaultStatus = address.defaultStatus;
}

async function submitAddress() {
  if (
    !form.name.trim() ||
    !form.phone.trim() ||
    !form.province.trim() ||
    !form.city.trim() ||
    !form.region.trim() ||
    !form.detailAddress.trim()
  ) {
    msg.value = "请完整填写收货地址";
    return;
  }

  saving.value = true;
  msg.value = "";
  try {
    const payload = {
      name: form.name.trim(),
      phone: form.phone.trim(),
      province: form.province.trim(),
      city: form.city.trim(),
      region: form.region.trim(),
      detailAddress: form.detailAddress.trim(),
      defaultStatus: form.defaultStatus,
    };

    if (editingId.value) {
      await updateAddress(editingId.value, payload);
    } else {
      await createAddress(payload);
    }
    resetForm();
    await loadAddresses();
  } catch {
    msg.value = editingId.value ? "地址修改失败" : "地址新增失败";
  } finally {
    saving.value = false;
  }
}

async function removeAddress(addressId: number) {
  try {
    await deleteAddress(addressId);
    if (editingId.value === addressId) {
      resetForm();
    }
    await loadAddresses();
  } catch {
    msg.value = "地址删除失败";
  }
}

async function makeDefault(addressId: number) {
  try {
    await setDefaultAddress(addressId);
    await loadAddresses();
  } catch {
    msg.value = "默认地址设置失败";
  }
}

onMounted(loadAddresses);
</script>

<template>
  <div class="address-page">
    <div class="page-header">
      <div>
        <h1>收货地址</h1>
        <p>管理你的常用收货信息，结算时会优先使用默认地址。</p>
      </div>
    </div>

    <p v-if="msg" class="msg">{{ msg }}</p>

    <div class="address-layout">
      <section class="address-form">
        <h2>{{ editingId ? "编辑地址" : "新增地址" }}</h2>
        <div class="form-grid">
          <label>
            <span>收货人</span>
            <input v-model="form.name" type="text" placeholder="请输入收货人姓名" />
          </label>
          <label>
            <span>手机号</span>
            <input v-model="form.phone" type="text" placeholder="请输入手机号" />
          </label>
          <label>
            <span>省份</span>
            <input v-model="form.province" type="text" placeholder="例如：浙江省" />
          </label>
          <label>
            <span>城市</span>
            <input v-model="form.city" type="text" placeholder="例如：杭州市" />
          </label>
          <label>
            <span>区县</span>
            <input v-model="form.region" type="text" placeholder="例如：西湖区" />
          </label>
          <label class="checkbox-row">
            <input v-model="form.defaultStatus" :true-value="1" :false-value="0" type="checkbox" />
            <span>设为默认地址</span>
          </label>
          <label class="full-width">
            <span>详细地址</span>
            <textarea
              v-model="form.detailAddress"
              rows="4"
              placeholder="请输入街道、门牌号等详细信息"
            ></textarea>
          </label>
        </div>
        <div class="form-actions">
          <button class="primary" :disabled="saving" @click="submitAddress">
            {{ saving ? "提交中..." : submitLabel }}
          </button>
          <button v-if="editingId" class="secondary" :disabled="saving" @click="resetForm">取消编辑</button>
        </div>
      </section>

      <section class="address-list">
        <h2>地址列表</h2>
        <div v-if="loading" class="empty">加载中...</div>
        <div v-else-if="addresses.length === 0" class="empty">你还没有收货地址</div>
        <div v-else class="address-cards">
          <article v-for="address in addresses" :key="address.id" class="address-card">
            <div class="card-head">
              <div class="name-line">
                <strong>{{ address.name }}</strong>
                <span>{{ address.phone }}</span>
              </div>
              <span v-if="address.defaultStatus === 1" class="default-badge">默认</span>
            </div>
            <p class="full-address">{{ address.fullAddress }}</p>
            <div class="card-actions">
              <button class="link-btn" @click="editAddress(address)">编辑</button>
              <button v-if="address.defaultStatus !== 1" class="link-btn" @click="makeDefault(address.id)">
                设为默认
              </button>
              <button class="link-btn danger" @click="removeAddress(address.id)">删除</button>
            </div>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.address-page {
  max-width: 1100px;
  margin: 0 auto;
}

.page-header h1 {
  margin-bottom: 8px;
  font-size: 28px;
}

.page-header p {
  color: #6b7280;
  margin: 0 0 20px;
}

.msg {
  margin-bottom: 16px;
  color: #dc2626;
}

.address-layout {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 20px;
}

.address-form,
.address-list {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.address-form h2,
.address-list h2 {
  margin: 0 0 16px;
  font-size: 18px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  color: #374151;
}

.full-width {
  grid-column: 1 / -1;
}

.checkbox-row {
  flex-direction: row !important;
  align-items: center;
  gap: 8px !important;
  margin-top: 26px;
}

input,
textarea {
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 14px;
  font-family: inherit;
}

textarea {
  resize: vertical;
}

.form-actions {
  margin-top: 18px;
  display: flex;
  gap: 10px;
}

.secondary {
  background: #fff;
  border: 1px solid #d1d5db;
  color: #374151;
}

.address-cards {
  display: grid;
  gap: 14px;
}

.address-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.name-line {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.default-badge {
  background: #fff1f0;
  color: #e11d48;
  border: 1px solid #fecdd3;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
}

.full-address {
  margin: 12px 0;
  color: #4b5563;
  line-height: 1.6;
}

.card-actions {
  display: flex;
  gap: 14px;
}

.link-btn {
  background: transparent;
  color: #2563eb;
  padding: 0;
}

.link-btn.danger {
  color: #dc2626;
}

.empty {
  color: #9ca3af;
  padding: 24px 0;
}

@media (max-width: 900px) {
  .address-layout {
    grid-template-columns: 1fr;
  }
}
</style>
