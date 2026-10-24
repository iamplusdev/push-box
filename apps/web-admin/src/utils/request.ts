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
    const data = error.response?.data
    // 服务端的 hint 有的本来就是人话（频道已归档、缺少设备 token），有的只是资源名（"channel"）。
    // 后者直接弹出来就是 "not_found"，所以 404 统一换成一句能照着做的提示。
    const message = data?.error === 'not_found'
      ? '请求的资源不存在，可能已被删除，请刷新后重试'
      : (data?.error === 'invalid_param' && data?.hint
        ? data.hint
        : (data?.error || error.message || '请求失败'))
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
