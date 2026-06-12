import { createRouter, createWebHistory } from "vue-router";
import ProductListView from "../views/ProductListView.vue";
import ProductDetailView from "../views/ProductDetailView.vue";
import OrderListView from "../views/OrderListView.vue";
import OrderDetailView from "../views/OrderDetailView.vue";

const routes = [
  { path: "/", redirect: "/products" },
  { path: "/products", name: "ProductList", component: ProductListView },
  { path: "/products/:id", name: "ProductDetail", component: ProductDetailView },
  { path: "/orders", name: "OrderList", component: OrderListView },
  { path: "/orders/:id", name: "OrderDetail", component: OrderDetailView },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

export default router;
