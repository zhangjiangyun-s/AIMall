import { createRouter, createWebHistory } from "vue-router";
import HomeView from "../views/HomeView.vue";
import ProductListView from "../views/ProductListView.vue";
import ProductDetailView from "../views/ProductDetailView.vue";
import CartView from "../views/CartView.vue";
import CheckoutView from "../views/CheckoutView.vue";
import OrderListView from "../views/OrderListView.vue";
import OrderDetailView from "../views/OrderDetailView.vue";
import LoginView from "../views/LoginView.vue";
import RegisterView from "../views/RegisterView.vue";
import AddressView from "../views/AddressView.vue";
import ReturnListView from "../views/ReturnListView.vue";
import ReturnDetailView from "../views/ReturnDetailView.vue";
import CouponCenterView from "../views/CouponCenterView.vue";
import AccountView from "../views/AccountView.vue";
import MyCouponsView from "../views/MyCouponsView.vue";
import ForgotPasswordView from "../views/ForgotPasswordView.vue";

const routes = [
  { path: "/", name: "Home", component: HomeView },
  { path: "/products", name: "ProductList", component: ProductListView },
  { path: "/products/:id", name: "ProductDetail", component: ProductDetailView },
  { path: "/cart", name: "Cart", component: CartView },
  { path: "/checkout", name: "Checkout", component: CheckoutView },
  { path: "/orders", name: "OrderList", component: OrderListView },
  { path: "/orders/:id", name: "OrderDetail", component: OrderDetailView },
  { path: "/returns", name: "ReturnList", component: ReturnListView },
  { path: "/returns/:id", name: "ReturnDetail", component: ReturnDetailView },
  { path: "/account", name: "Account", component: AccountView },
  { path: "/account/address", name: "Address", component: AddressView },
  { path: "/account/coupons", name: "MyCoupons", component: MyCouponsView },
  { path: "/coupons", name: "CouponCenter", component: CouponCenterView },
  { path: "/login", name: "Login", component: LoginView },
  { path: "/register", name: "Register", component: RegisterView },
  { path: "/forgot-password", name: "ForgotPassword", component: ForgotPasswordView },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to, _from, next) => {
  const publicPages = ["/login", "/register", "/forgot-password"];
  const isPublic =
    publicPages.includes(to.path) ||
    to.path.startsWith("/products/") ||
    to.path === "/products" ||
    to.path === "/";
  const token = localStorage.getItem("token");
  if (!isPublic && !token) {
    next("/login");
  } else {
    next();
  }
});

export default router;
