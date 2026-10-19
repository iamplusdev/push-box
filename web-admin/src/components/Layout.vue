<template>
  <el-container class="layout-container">
    <el-aside width="200px" class="layout-aside">
      <div class="logo">
        <h3>PushBox</h3>
        <span class="subtitle">管理台</span>
      </div>
      <el-menu :default-active="activeMenu" router class="side-menu">
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>概览</span>
        </el-menu-item>
        <el-menu-item index="/channels">
          <el-icon><Message /></el-icon>
          <span>频道管理</span>
        </el-menu-item>
        <el-menu-item index="/devices">
          <el-icon><Cellphone /></el-icon>
          <span>设备管理</span>
        </el-menu-item>
        <el-menu-item index="/scheduled-tasks">
          <el-icon><Timer /></el-icon>
          <span>定时推送</span>
        </el-menu-item>
        <el-menu-item index="/messages">
          <el-icon><Search /></el-icon>
          <span>消息搜索</span>
        </el-menu-item>
      </el-menu>
      <div class="aside-footer">
        <el-button text class="logout-btn" @click="handleLogout">
          <el-icon class="logout-icon"><SwitchButton /></el-icon>
          <span>退出登录</span>
        </el-button>
      </div>
    </el-aside>
    <el-main class="layout-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, Message, Cellphone, Search, SwitchButton, Timer } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const activeMenu = computed(() => {
  if (route.path.startsWith('/channels')) return '/channels'
  return route.path
})

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.layout-aside {
  background: #1d1e1f;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.logo {
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo h3 {
  margin: 0;
  font-size: 18px;
  color: #fff;
}

.logo .subtitle {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.side-menu {
  border-right: none;
  background: #1d1e1f;
  flex: 1;
}

:deep(.side-menu .el-menu-item) {
  color: rgba(255, 255, 255, 0.7);
}

:deep(.side-menu .el-menu-item:hover),
:deep(.side-menu .el-menu-item.is-active) {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

.aside-footer {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding: 8px 8px;
}

.logout-btn {
  width: 100%;
  justify-content: flex-start;
  color: rgba(255, 255, 255, 0.7);
  height: 40px;
}

.logout-btn:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

.logout-icon {
  margin-right: 8px;
}

.layout-main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
