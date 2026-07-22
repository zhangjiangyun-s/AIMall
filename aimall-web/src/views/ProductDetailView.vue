<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchProductById, type ProductDetail, type ProductSkuStock } from "../api/productApi";
import { addToCart } from "../api/cartApi";
import { favoriteProduct, fetchProductReviews, recordProductBrowse, submitProductReview, unfavoriteProduct, type ProductReview } from "../api/productInteractionApi";
import SkuSelector from "../components/product/SkuSelector.vue";

const route = useRoute();
const router = useRouter();

const product = ref<ProductDetail | null>(null);
const loading = ref(true);
const selectedSku = ref<ProductSkuStock | null>(null);
const quantity = ref(1);
const msg = ref("");
const msgType = ref<"success" | "error" | "">("");
const reviews = ref<ProductReview[]>([]);
const favorite = ref(false);
const reviewLoading = ref(false);
const reviewOrderItemId = ref("");
const reviewRating = ref(5);
const reviewContent = ref("");

const productId = computed(() => Number(route.params.id));
const isAuthenticated = computed(() => Boolean(localStorage.getItem("token")));
const currentPrice = computed(() => selectedSku.value?.price ?? product.value?.price ?? 0);
const currentStock = computed(() => selectedSku.value?.stock ?? product.value?.stock ?? 0);

async function loadProduct(id: number) {
  loading.value = true;
  product.value = null;
  selectedSku.value = null;
  quantity.value = 1;
  try {
    const res = await fetchProductById(id);
    if (res.data.code === 0) {
      product.value = res.data.data;
      if (localStorage.getItem("token")) {
        void recordProductBrowse(id).catch(() => undefined);
      }
      void fetchProductReviews(id).then((response) => {
        if (response.data.code === 0) reviews.value = response.data.data;
      }).catch(() => undefined);
    }
  } finally {
    loading.value = false;
  }
}

async function toggleFavorite() {
  try {
    if (favorite.value) await unfavoriteProduct(productId.value);
    else await favoriteProduct(productId.value);
    favorite.value = !favorite.value;
  } catch {
    msg.value = "请登录后再收藏商品";
    msgType.value = "error";
  }
}

async function submitReview() {
  const orderItemId = Number(reviewOrderItemId.value);
  if (!orderItemId || !reviewContent.value.trim()) {
    msg.value = "请填写已完成订单的商品项 ID 和评价内容";
    msgType.value = "error";
    return;
  }
  reviewLoading.value = true;
  try {
    const response = await submitProductReview(productId.value, { orderItemId, rating: reviewRating.value, content: reviewContent.value.trim() });
    if (response.data.code === 0) {
      reviews.value.unshift(response.data.data);
      reviewContent.value = "";
      reviewOrderItemId.value = "";
      msg.value = "评价已提交";
      msgType.value = "success";
    }
  } catch {
    msg.value = "评价提交失败，请确认订单商品项属于已完成订单";
    msgType.value = "error";
  } finally {
    reviewLoading.value = false;
  }
}

onMounted(() => loadProduct(productId.value));
watch(productId, (id) => loadProduct(id));

function onSkuSelect(sku: ProductSkuStock | null) {
  selectedSku.value = sku;
  quantity.value = 1;
}

async function handleAddToCart() {
  msg.value = "";
  msgType.value = "";
  if (!product.value) return false;

  try {
    const res = await addToCart({
      productId: productId.value,
      quantity: quantity.value,
      productSkuId: selectedSku.value?.id,
    });

    if (res.data.code === 0) {
      msg.value = "已加入购物车";
      msgType.value = "success";
      setTimeout(() => {
        msg.value = "";
        msgType.value = "";
      }, 3000);
      return true;
    } else {
      msg.value = res.data.message || "加入购物车失败";
      msgType.value = "error";
    }
  } catch {
    msg.value = "购物车服务暂时不可用";
    msgType.value = "error";
  }

  setTimeout(() => {
    msg.value = "";
    msgType.value = "";
  }, 3000);
  return false;
}

async function buyNow() {
  if (await handleAddToCart()) {
    router.push("/cart");
  }
}
</script>

<template>
  <div class="detail-page">
    <div v-if="loading" class="loading-state">加载中...</div>

    <div v-else-if="!product" class="not-found">
      <p>商品不存在</p>
      <router-link to="/products">返回商品列表</router-link>
    </div>

    <template v-else>
      <div class="breadcrumb">
        <router-link to="/">首页</router-link>
        <span class="sep">/</span>
        <router-link to="/products">{{ product.category }}</router-link>
        <span class="sep">/</span>
        <span>{{ product.name }}</span>
      </div>

      <div class="detail-layout">
        <div class="detail-gallery">
          <div class="gallery-main">
            <img v-if="product.pic" :src="product.pic" :alt="product.name" class="gallery-image" />
            <span v-else class="img-placeholder">{{ product.name[0] }}</span>
          </div>
        </div>

        <div class="detail-info">
          <h1 class="product-name">{{ product.name }}</h1>
          <p v-if="product.subTitle" class="product-subtitle">{{ product.subTitle }}</p>

          <div class="price-area">
            <span class="current-price">&yen;{{ currentPrice }}</span>
            <span v-if="product.originalPrice && product.originalPrice > currentPrice" class="original-price">
              &yen;{{ product.originalPrice }}
            </span>
          </div>

          <div class="meta-row">
            <span v-if="product.brandName" class="meta-item"><strong>品牌：</strong>{{ product.brandName }}</span>
            <span class="meta-item"><strong>分类：</strong>{{ product.category }}</span>
          </div>

          <div v-if="product.sellingPoints && product.sellingPoints.length > 0" class="tag-list">
            <span v-for="(point, i) in product.sellingPoints" :key="i" class="tag">{{ point }}</span>
          </div>

          <SkuSelector
            v-if="product.skuStocks && product.skuStocks.length > 0"
            :skuStocks="product.skuStocks"
            @select="onSkuSelect"
          />

          <div class="qty-row">
            <span class="qty-label">数量：</span>
            <div class="qty-control">
              <button :disabled="quantity <= 1" @click="quantity > 1 && quantity--">-</button>
              <span>{{ quantity }}</span>
              <button :disabled="quantity >= currentStock" @click="quantity < currentStock && quantity++">+</button>
            </div>
            <span class="stock-info">库存 {{ currentStock }} 件</span>
          </div>

          <p v-if="msg" :class="['msg', msgType]">{{ msg }}</p>

          <div class="action-row">
            <button class="btn-favorite" @click="toggleFavorite">{{ favorite ? "已收藏" : "收藏商品" }}</button>
            <button class="btn-cart" @click="handleAddToCart">加入购物车</button>
            <button class="btn-buy" @click="buyNow">立即购买</button>
          </div>
        </div>
      </div>

      <div v-if="product.description || product.detailDesc" class="detail-desc">
        <h2>商品详情</h2>
        <div class="desc-content">
          <p v-if="product.description">{{ product.description }}</p>
          <p v-if="product.detailDesc" style="margin-top: 12px">{{ product.detailDesc }}</p>
        </div>
      </div>

      <section class="reviews-section">
        <div class="section-heading"><h2>用户评价</h2><span>{{ reviews.length }} 条</span></div>
        <div v-if="reviews.length === 0" class="empty-reviews">暂无公开评价</div>
        <article v-for="review in reviews" :key="review.id" class="review-row">
          <div class="review-top"><strong>{{ "★".repeat(review.rating) }}</strong><time>{{ review.createTime || "" }}</time></div>
          <p>{{ review.content || "" }}</p>
        </article>
        <div v-if="isAuthenticated" class="review-form">
          <h3>提交评价</h3>
          <input v-model="reviewOrderItemId" inputmode="numeric" placeholder="已完成订单商品项 ID" />
          <select v-model.number="reviewRating"><option v-for="score in 5" :key="score" :value="score">{{ score }} 星</option></select>
          <textarea v-model="reviewContent" rows="3" maxlength="500" placeholder="分享你的真实体验" />
          <button :disabled="reviewLoading" @click="submitReview">{{ reviewLoading ? "提交中..." : "提交评价" }}</button>
        </div>
      </section>

      <div class="ai-entry">
        <p>对这件商品有疑问？可以继续咨询 AI 助手</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-page {
  padding-top: 20px;
  color: var(--color-text);
}

.detail-layout {
  display: flex;
  gap: 32px;
  margin-bottom: 32px;
}

.detail-gallery {
  width: 400px;
  flex-shrink: 0;
}

.gallery-main {
  width: 100%;
  aspect-ratio: 1;
  background: var(--color-bg);
  border-radius: var(--radius-card);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.gallery-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.img-placeholder {
  font-size: 64px;
  color: #ddd;
  font-weight: 700;
}

.detail-info {
  flex: 1;
}

.product-name {
  font-size: 22px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 8px;
}

.product-subtitle {
  font-size: 14px;
  color: var(--color-text-muted);
  margin-bottom: 16px;
}

.price-area {
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 12px 16px;
  margin-bottom: 16px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.current-price {
  color: var(--color-primary);
  font-size: 28px;
  font-weight: 700;
}

.original-price {
  color: var(--color-text-light);
  font-size: 14px;
  text-decoration: line-through;
}

.meta-row {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--color-text-muted);
}

.tag-list {
  display: flex;
  gap: 6px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 3px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  border: 1px solid var(--color-primary-border);
}

.qty-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.qty-label {
  font-size: 13px;
  color: var(--color-text-muted);
}

.qty-control {
  display: flex;
  align-items: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.qty-control button {
  width: 32px;
  height: 32px;
  border: none;
  background: var(--color-bg);
  cursor: pointer;
  font-size: 16px;
  color: var(--color-text-body);
}

.qty-control button:disabled {
  color: #ccc;
  cursor: not-allowed;
}

.qty-control span {
  width: 40px;
  text-align: center;
  font-size: 14px;
}

.stock-info {
  font-size: 12px;
  color: var(--color-text-muted);
}

.msg {
  font-size: 14px;
  margin-bottom: 12px;
}

.msg.success {
  color: var(--color-success);
}

.msg.error {
  color: var(--color-danger);
}

.action-row {
  display: flex;
  gap: 12px;
}

.btn-favorite { padding: 12px 18px; border: 1px solid var(--color-primary); border-radius: var(--radius-sm); background: #fff; color: var(--color-primary); cursor: pointer; }

.btn-cart {
  padding: 12px 32px;
  font-size: 16px;
  border: 1px solid var(--color-primary);
  border-radius: var(--radius-sm);
  background: var(--color-bg-white);
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 500;
}

.btn-cart:hover {
  background: var(--color-primary-light);
}

.btn-buy {
  padding: 12px 32px;
  font-size: 16px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: #fff;
  cursor: pointer;
  font-weight: 500;
}

.btn-buy:hover {
  background: var(--color-primary-hover);
}

.detail-desc {
  background: var(--color-bg-white);
  border-radius: var(--radius-card);
  padding: 24px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-sm);
}

.detail-desc h2 {
  font-size: 18px;
  margin-bottom: 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text);
}

.desc-content {
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-text-body);
}

.ai-entry {
  text-align: center;
  padding: 20px;
  background: var(--color-primary-light);
  border-radius: var(--radius-card);
  margin-bottom: 24px;
  cursor: pointer;
  border: 1px solid var(--color-primary-border);
}

.ai-entry p {
  font-size: 14px;
  color: var(--color-primary);
  font-weight: 500;
}

.reviews-section { background: var(--color-bg-white); border-radius: var(--radius-card); padding: 24px; margin-bottom: 24px; box-shadow: var(--shadow-sm); }
.section-heading, .review-top { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.section-heading h2 { margin: 0; font-size: 18px; }
.section-heading span, .review-top time { color: var(--color-text-muted); font-size: 13px; }
.review-row { padding: 14px 0; border-top: 1px solid var(--color-border-light); }
.review-row strong { color: #eab308; letter-spacing: 2px; }
.review-row p { margin-top: 8px; color: var(--color-text-body); line-height: 1.6; }
.empty-reviews { padding: 20px 0; color: var(--color-text-muted); }
.review-form { display: grid; gap: 10px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-border-light); }
.review-form h3 { margin: 0; font-size: 15px; }
.review-form input, .review-form select, .review-form textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 9px 10px; font: inherit; }
.review-form button { width: fit-content; padding: 9px 16px; border: 0; border-radius: var(--radius-sm); background: var(--color-primary); color: #fff; cursor: pointer; }

.loading-state {
  text-align: center;
  padding: 60px 0;
  color: var(--color-text-muted);
}

@media (max-width: 768px) {
  .detail-layout {
    flex-direction: column;
  }

  .detail-gallery {
    width: 100%;
  }
}
</style>
