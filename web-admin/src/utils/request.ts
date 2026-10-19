import axios from 'axios'
import { ElMessage } from 'element-plus'

const service = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器：自动添加 token
service.interceptors.request.use(
  config => {
    const token = localStorage.getItem('admin_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

// 响应拦截器：统一处理错误
service.interceptors.response.use(
  response => {
    return response.data
  },
  error => {
    const message = error.response?.data?.error || error.message || '请求失败'
    ElMessage.error(message)
    
    // 401 未授权，跳转登录
    if (error.response?.status === 401) {
      localStorage.removeItem('admin_token')
      window.location.href = '/login'
    }
    
    return Promise.reject(error)
  }
)

export default service
