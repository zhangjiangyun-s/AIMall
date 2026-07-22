<script setup lang="ts">
defineProps<{ title: string }>();
</script>

<template>
  <section class="home-section">
    <h2 class="section-title">{{ title }}</h2>
    <div class="section-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.home-section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 16px;
  padding-left: 12px;
  border-left: 3px solid #e4393c;
  color: #333;
}
</style>
