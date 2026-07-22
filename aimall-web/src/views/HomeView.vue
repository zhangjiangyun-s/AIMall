<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchHomeContent } from "../api/homeApi";
import type { HomeProduct } from "../api/homeApi";

const router = useRouter();
const recommendProducts = ref<HomeProduct[]>([]);
const newProducts = ref<HomeProduct[]>([]);
const hotProducts = ref<HomeProduct[]>([]);
const loading = ref(true);
const activeSlide = ref(0);
let carouselTimer: number | null = null;

const heroSlides = computed(() => {
  const merged = [...recommendProducts.value, ...newProducts.value, ...hotProducts.value];
  return merged
    .filter((item, index, list) => list.findIndex((x) => x.productId === item.productId) === index)
    .slice(0, 4);
});

const currentHero = computed(() => heroSlides.value[activeSlide.value] ?? null);

onMounted(async () => {
  try {
    const contentRes = await fetchHomeContent();
    if (contentRes.data.code === 0) {
      const data = contentRes.data.data;
      recommendProducts.value = data.recommendProductList || [];
      newProducts.value = data.newProductList || [];
      hotProducts.value = data.hotProductList || [];
    }
  } finally {
    loading.value = false;
  }
});

watch(heroSlides, (slides) => {
  if (!slides.length) {
    activeSlide.value = 0;
    stopCarousel();
    return;
  }
  if (activeSlide.value >= slides.length) {
    activeSlide.value = 0;
  }
  startCarousel();
});

onBeforeUnmount(() => {
  stopCarousel();
});

function stopCarousel() {
  if (carouselTimer !== null) {
    window.clearInterval(carouselTimer);
    carouselTimer = null;
  }
}

function startCarousel() {
  stopCarousel();
  if (heroSlides.value.length <= 1) return;
  carouselTimer = window.setInterval(() => {
    activeSlide.value = (activeSlide.value + 1) % heroSlides.value.length;
  }, 5000);
}

function setActiveSlide(index: number) {
  activeSlide.value = index;
  startCarousel();
}

function prevSlide() {
  if (!heroSlides.value.length) return;
  activeSlide.value = (activeSlide.value - 1 + heroSlides.value.length) % heroSlides.value.length;
  startCarousel();
}

function nextSlide() {
  if (!heroSlides.value.length) return;
  activeSlide.value = (activeSlide.value + 1) % heroSlides.value.length;
  startCarousel();
}

function categoryColor(id: number): string {
  const colors = ["#ff5a3d", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#8b5cf6", "#ec4899", "#06b6d4"];
  return colors[id % colors.length];
}

function goProduct(id: number) {
  router.push(`/products/${id}`);
}
</script>

<template>
  <div class="home-page">
    <div v-if="loading" class="loading-state page-container">加载中...</div>

    <template v-else>
      <section class="hero-strip">
        <div
          class="hero-banner"
          :style="currentHero?.pic ? { backgroundImage: `url(${currentHero.pic})` } : undefined"
          @click="currentHero && goProduct(currentHero.productId)"
        />
        <div v-if="heroSlides.length > 1" class="carousel-controls page-container">
          <button type="button" class="carousel-nav" @click="prevSlide">上一张</button>
          <div class="carousel-dots">
            <button
              v-for="(slide, index) in heroSlides"
              :key="slide.productId"
              type="button"
              class="carousel-dot"
              :class="{ active: index === activeSlide }"
              @click="setActiveSlide(index)"
            />
          </div>
          <button type="button" class="carousel-nav" @click="nextSlide">下一张</button>
        </div>
      </section>

      <section v-if="recommendProducts.length > 0" class="section-block">
        <div class="page-container">
          <div class="section-header">
            <h2>推荐商品</h2>
          </div>
          <div class="product-grid">
            <div v-for="p in recommendProducts.slice(0, 8)" :key="p.productId" class="product-card" @click="goProduct(p.productId)">
              <div class="card-img" :style="{ background: categoryColor(p.categoryName ? p.categoryName.charCodeAt(0) : p.productId) }">
                <img v-if="p.pic" :src="p.pic" :alt="p.name" class="card-image" />
                <span v-else class="card-img-text">{{ p.name[0] }}</span>
              </div>
              <div class="card-info">
                <h4>{{ p.name }}</h4>
                <p v-if="p.subTitle" class="card-sub">{{ p.subTitle }}</p>
                <div class="card-price">
                  <span class="price">&yen;{{ p.price }}</span>
                  <span v-if="p.originalPrice" class="orig-price">&yen;{{ p.originalPrice }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="newProducts.length > 0" class="section-block">
        <div class="page-container">
          <div class="section-header">
            <h2>新品上架</h2>
          </div>
          <div class="product-grid">
            <div v-for="p in newProducts.slice(0, 8)" :key="p.productId" class="product-card" @click="goProduct(p.productId)">
              <div class="card-img" :style="{ background: categoryColor(p.categoryName ? p.categoryName.charCodeAt(0) : p.productId) }">
                <img v-if="p.pic" :src="p.pic" :alt="p.name" class="card-image" />
                <span v-else class="card-img-text">{{ p.name[0] }}</span>
              </div>
              <div class="card-info">
                <h4>{{ p.name }}</h4>
                <p v-if="p.subTitle" class="card-sub">{{ p.subTitle }}</p>
                <div class="card-price">
                  <span class="price">&yen;{{ p.price }}</span>
                  <span v-if="p.originalPrice" class="orig-price">&yen;{{ p.originalPrice }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="hotProducts.length > 0" class="section-block">
        <div class="page-container">
          <div class="section-header">
            <h2>热门精选</h2>
          </div>
          <div class="product-grid">
            <div v-for="p in hotProducts.slice(0, 8)" :key="p.productId" class="product-card" @click="goProduct(p.productId)">
              <div class="card-img" :style="{ background: categoryColor(p.categoryName ? p.categoryName.charCodeAt(0) : p.productId) }">
                <img v-if="p.pic" :src="p.pic" :alt="p.name" class="card-image" />
                <span v-else class="card-img-text">{{ p.name[0] }}</span>
              </div>
              <div class="card-info">
                <h4>{{ p.name }}</h4>
                <p v-if="p.subTitle" class="card-sub">{{ p.subTitle }}</p>
                <div class="card-price">
                  <span class="price">&yen;{{ p.price }}</span>
                  <span v-if="p.originalPrice" class="orig-price">&yen;{{ p.originalPrice }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.home-page {
  padding-bottom: 20px;
}

.loading-state {
  padding: 40px 0;
  color: var(--color-text-muted);
}

.hero-strip {
  margin-bottom: 24px;
}

.hero-banner {
  background: linear-gradient(135deg, #ff5a3d 0%, #e84a2e 50%, #d63031 100%);
  background-size: cover;
  background-position: center;
  width: 100vw;
  height: min(42vw, 520px);
  min-height: 240px;
  margin-left: calc(50% - 50vw);
  margin-right: calc(50% - 50vw);
  cursor: pointer;
}

.carousel-controls {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding-top: 12px;
}

.carousel-nav {
  min-width: 72px;
  height: 32px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-bg-white);
  color: var(--color-text-body);
  font-size: 13px;
}

.carousel-dots {
  display: flex;
  gap: 8px;
}

.carousel-dot {
  width: 26px;
  height: 4px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.16);
}

.carousel-dot.active {
  background: var(--color-primary);
}

.section-block {
  margin-bottom: 36px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-header h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text);
  padding-left: 12px;
  border-left: 3px solid var(--color-primary);
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.product-card {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s;
  box-shadow: var(--shadow-sm);
}

.product-card:hover {
  box-shadow: var(--shadow-card);
}

.card-img {
  width: 100%;
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.card-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.card-img-text {
  font-size: 36px;
  font-weight: 700;
  color: #fff;
  opacity: 0.8;
}

.card-info {
  padding: 12px;
}

.card-info h4 {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text);
  margin-bottom: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.4;
}

.card-sub {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-price {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.price {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-primary);
}

.orig-price {
  font-size: 12px;
  color: var(--color-text-light);
  text-decoration: line-through;
}

@media (max-width: 900px) {
  .hero-banner {
    height: 44vw;
    min-height: 220px;
  }
}

@media (max-width: 640px) {
  .hero-banner {
    height: 56vw;
    min-height: 180px;
  }

  .product-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .section-header h2 {
    font-size: 17px;
  }
}
</style>
