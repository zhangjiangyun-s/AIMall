import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layout/AdminLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: AdminLayout,
      children: [
        {
          path: '',
          name: 'Dashboard',
          component: () => import('../views/DashboardView.vue')
        },
        {
          path: 'products',
          name: 'Products',
          component: () => import('../views/ProductManageView.vue')
        },
        {
          path: 'knowledge-docs',
          name: 'KnowledgeDocs',
          component: () => import('../views/KnowledgeDocView.vue')
        }
      ]
    }
  ]
})

export default router
