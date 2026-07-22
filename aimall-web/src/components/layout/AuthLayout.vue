<template>
  <div class="auth-layout">
    <div class="auth-left">
      <div class="auth-brand">
        <div class="brand-logo">AIMall</div>
        <h2 class="brand-title">AI 智能导购，品质新生活</h2>
        <p class="brand-desc">高效感知您的购物偏好，精准推荐优质商品，让每一笔消费都物超所值。</p>
        <div class="brand-features">
          <div class="feature-item">
            <span class="feature-icon">&#9670;</span>
            <span>AI 精准推荐</span>
          </div>
          <div class="feature-item">
            <span class="feature-icon">&#9670;</span>
            <span>品质好物严选</span>
          </div>
          <div class="feature-item">
            <span class="feature-icon">&#9670;</span>
            <span>极速下单体验</span>
          </div>
          <div class="feature-item">
            <span class="feature-icon">&#9670;</span>
            <span>智能售前咨询</span>
          </div>
        </div>
      </div>
    </div>
    <div class="auth-right">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.auth-layout {
  display: flex;
  min-height: 100vh;
}

.auth-left {
  flex: 1;
  background: linear-gradient(135deg, #ff5a3d 0%, #e84a2e 50%, #c0392b 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  padding: 40px;
}

.auth-brand {
  max-width: 420px;
}

.brand-logo {
  font-size: 36px;
  font-weight: 800;
  margin-bottom: 16px;
  letter-spacing: 2px;
}

.brand-title {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 12px;
  line-height: 1.4;
}

.brand-desc {
  font-size: 15px;
  line-height: 1.7;
  opacity: 0.85;
  margin-bottom: 32px;
}

.brand-features {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  opacity: 0.9;
}

.feature-icon {
  font-size: 10px;
}

.auth-right {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg-white);
  padding: 40px;
}

@media (max-width: 768px) {
  .auth-layout {
    flex-direction: column;
  }
  .auth-left {
    padding: 32px 24px;
  }
  .auth-right {
    padding: 32px 24px;
  }
}
</style>
