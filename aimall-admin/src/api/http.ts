import axios from 'axios'

export class ApiError extends Error {
  code: number
  status?: number

  constructor(message: string, code = -1, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

function clearAdminAuth() {
  localStorage.removeItem('admin_token')
  localStorage.removeItem('admin_info')
}

function safeMessage(message: unknown, fallback: string) {
  const text = typeof message === 'string' ? message.trim() : ''
  if (!text || /java\.|sql|exception|stack|mapper/i.test(text)) return fallback
  return text
}

function redirectToLogin() {
  clearAdminAuth()
  if (window.location.pathname !== '/login') window.location.href = '/login'
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000
})

http.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('admin_token')
    if (token) {
      config.headers['token'] = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

http.interceptors.response.use(
  (response) => {
    const payload = response.data
    if (payload && typeof payload.code === 'number' && payload.code !== 0) {
      const message = safeMessage(payload.message, '请求处理失败')
      const unauthorized = message.includes('请先登录') || message.includes('登录已失效')
      const forbidden = message.includes('无管理员权限') || message.includes('无权限')
      if (unauthorized) redirectToLogin()
      return Promise.reject(new ApiError(message, payload.code, unauthorized ? 401 : forbidden ? 403 : response.status))
    }
    return response
  },
  (error) => {
    const status = error.response?.status as number | undefined
    if (status === 401) redirectToLogin()
    const fallback = status === 403 ? '当前账号无权执行此操作' : '网络连接失败，请稍后重试'
    const message = safeMessage(error.response?.data?.message, fallback)
    return Promise.reject(new ApiError(message, -1, status))
  }
)

export default http
