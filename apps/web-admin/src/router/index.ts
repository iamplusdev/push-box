import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Layout from '@/components/Layout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue')
    },
    {
      path: '/',
      component: Layout,
      redirect: '/dashboard',
      meta: { requiresAuth: true },
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/Dashboard.vue')
        },
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('@/views/Channels.vue')
        },
        {
          path: 'channels/:channelNo',
          name: 'ChannelDetail',
          component: () => import('@/views/ChannelDetail.vue')
        },
        {
          path: 'devices',
          name: 'Devices',
          component: () => import('@/views/Devices.vue')
        },
        {
          path: 'scheduled-tasks',
          name: 'ScheduledTasks',
          component: () => import('@/views/ScheduledTasks.vue')
        },
        {
          path: 'messages',
          name: 'Messages',
          component: () => import('@/views/MessageSearch.vue')
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    return '/login'
  }
  if (to.path === '/login' && authStore.isLoggedIn) {
    return '/dashboard'
  }
})

export default router
