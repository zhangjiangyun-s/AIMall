import { createRouter, createWebHistory } from "vue-router";
import AdminLayout from "../layout/AdminLayout.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "AdminLogin",
      component: () => import("../views/AdminLoginView.vue"),
    },
    {
      path: "/",
      component: AdminLayout,
      children: [
        {
          path: "",
          name: "Dashboard",
          component: () => import("../views/DashboardView.vue"),
        },
        {
          path: "products",
          name: "Products",
          component: () => import("../views/ProductManageView.vue"),
        },
        {
          path: "knowledge-docs",
          name: "KnowledgeDocs",
          component: () => import("../views/KnowledgeDocView.vue"),
        },
        {
          path: "orders",
          name: "Orders",
          component: () => import("../views/OrderManageView.vue"),
        },
        {
          path: "returns",
          name: "Returns",
          component: () => import("../views/ReturnManageView.vue"),
        },
        {
          path: "payment-reconciliation",
          name: "PaymentReconciliation",
          component: () => import("../views/PaymentReconciliationView.vue"),
        },
      ],
    },
  ],
});

router.beforeEach((to, _from, next) => {
  if (to.path === "/login") {
    next();
    return;
  }
  const token = localStorage.getItem("admin_token");
  if (!token) {
    next("/login");
  } else {
    next();
  }
});

export default router;
