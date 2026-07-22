<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchIntegrationHealth } from "../../api/healthApi";

const healthStatus = ref("");

onMounted(async () => {
  try {
    const res = await fetchIntegrationHealth();
    if (res.data.code === 0) {
      healthStatus.value = res.data.data.status;
    }
  } catch {
    healthStatus.value = "OFFLINE";
  }
});
</script>

<template>
  <footer class="app-footer">
    <div class="footer-promises">
      <div class="promises-inner">
        <div class="promise-item">
          <strong>品质保障</strong>
          <span>正品严选，购物更安心</span>
        </div>
        <div class="promise-item">
          <strong>极速发货</strong>
          <span>覆盖全国，履约稳定</span>
        </div>
        <div class="promise-item">
          <strong>无忧售后</strong>
          <span>7 天退换，流程清晰</span>
        </div>
        <div class="promise-item">
          <strong>AI 导购</strong>
          <span>预留智能推荐与问答能力</span>
        </div>
      </div>
    </div>

    <div class="footer-main">
      <div class="footer-inner">
        <div class="footer-brand">
          <h3>AIMall</h3>
          <p>一个面向智能导购场景的商城产品底座。</p>
        </div>
        <div class="footer-links">
          <div class="link-group">
            <h4>购物指南</h4>
            <a href="#">下单流程</a>
            <a href="#">支付方式</a>
            <a href="#">配送说明</a>
          </div>
          <div class="link-group">
            <h4>服务保障</h4>
            <a href="#">退换政策</a>
            <a href="#">售后说明</a>
            <a href="#">意见反馈</a>
          </div>
          <div class="link-group">
            <h4>关于我们</h4>
            <a href="#">项目介绍</a>
            <a href="#">合作联系</a>
            <a href="#">服务支持</a>
          </div>
        </div>
      </div>
    </div>

    <div class="footer-bottom">
      <div class="footer-inner">
        <p class="copyright">&copy; 2026 AIMall. All rights reserved.</p>
        <p v-if="healthStatus" class="dev-info">联调状态：{{ healthStatus }} | 后端：aimall-server</p>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.app-footer {
  background: var(--color-bg-white);
  margin-top: 48px;
  border-top: 1px solid var(--color-border);
}

.footer-promises {
  border-bottom: 1px solid var(--color-border);
}

.promises-inner {
  max-width: var(--content-width);
  margin: 0 auto;
  display: flex;
  justify-content: space-around;
  padding: 24px 20px;
  gap: 16px;
  flex-wrap: wrap;
}

.promise-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--color-text-muted);
}

.promise-item strong {
  font-size: 14px;
  color: var(--color-text);
}

.footer-main {
  padding: 32px 0;
}

.footer-inner {
  max-width: var(--content-width);
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  gap: 48px;
}

.footer-brand {
  flex-shrink: 0;
}

.footer-brand h3 {
  font-size: 22px;
  color: var(--color-primary);
  margin-bottom: 8px;
}

.footer-brand p {
  font-size: 13px;
  color: var(--color-text-muted);
}

.footer-links {
  display: flex;
  gap: 48px;
}

.link-group h4 {
  font-size: 14px;
  margin-bottom: 12px;
  color: var(--color-text);
}

.link-group a {
  display: block;
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
  text-decoration: none;
}

.link-group a:hover {
  color: var(--color-primary);
}

.footer-bottom {
  border-top: 1px solid var(--color-border);
  padding: 16px 0;
}

.footer-bottom .footer-inner {
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.copyright {
  font-size: 12px;
  color: var(--color-text-light);
}

.dev-info {
  font-size: 11px;
  color: var(--color-text-muted);
}

@media (max-width: 768px) {
  .footer-inner {
    flex-direction: column;
    gap: 24px;
  }

  .footer-links {
    flex-wrap: wrap;
    gap: 24px;
  }
}
</style>
