<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <h2>PushBox 管理后台</h2>
      </template>
      
      <el-form :model="form" @submit.prevent="handleLogin">
        <el-form-item label="管理员密钥">
          <el-input
            v-model="form.secret"
            type="password"
            placeholder="请输入 pb.admin.secret"
            show-password
          />
        </el-form-item>
        
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="handleLogin"
            style="width: 100%"
          >
            登录
          </el-button>
        </el-form-item>
        
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          :closable="false"
        />
      </el-form>
      
      <div class="tips">
        <p>提示：密钥配置在 application-dev.yml 的 pb.admin.secret</p>
        <p>生产环境建议留空以禁用管理台</p>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()

const form = reactive({
  secret: ''
})

const loading = ref(false)
const error = ref('')

async function handleLogin() {
  if (!form.secret.trim()) {
    error.value = '请输入管理员密钥'
    return
  }
  
  loading.value = true
  error.value = ''
  
  try {
    const response = await fetch('/api/v1/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ secret: form.secret })
    })
    
    const data = await response.json()

    if (response.ok && data.token) {
      authStore.setToken(data.token)
      ElMessage.success('登录成功')
      router.push('/dashboard')
    } else {
      error.value = data.hint || '密钥错误'
    }
  } catch (e) {
    error.value = '登录失败，请检查网络连接'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
}

/* 登录页只有这一处标题：h2 的浏览器默认是 1.5em（body 15 下就是 22.5px），
   六档里没有这一档，所以显式落到 H1 */
.login-card h2 {
  text-align: center;
  margin: 0;
  font-size: var(--pb-font-h1);
  line-height: var(--pb-line-h1);
}

.tips {
  margin-top: 16px;
  font-size: var(--pb-font-label);
  line-height: var(--pb-line-label);
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
