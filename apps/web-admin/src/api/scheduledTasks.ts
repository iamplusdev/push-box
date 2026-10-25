import request from '@/utils/request'

export function getScheduledTasks() {
  return request({
    url: '/admin/scheduled-tasks',
    method: 'GET'
  })
}

export function getScheduledTask(taskId: string) {
  return request({
    url: `/admin/scheduled-tasks/${taskId}`,
    method: 'GET'
  })
}

export function createScheduledTask(payload: ScheduledTaskInput) {
  return request({
    url: '/admin/scheduled-tasks',
    method: 'POST',
    data: payload
  })
}

export function updateScheduledTask(taskId: string, payload: ScheduledTaskInput) {
  return request({
    url: `/admin/scheduled-tasks/${taskId}`,
    method: 'PUT',
    data: payload
  })
}

export function setScheduledTaskEnabled(taskId: string, enabled: boolean) {
  return request({
    url: `/admin/scheduled-tasks/${taskId}/enabled`,
    method: 'POST',
    data: { enabled: enabled ? 1 : 0 }
  })
}

export function deleteScheduledTask(taskId: string) {
  return request({
    url: `/admin/scheduled-tasks/${taskId}`,
    method: 'DELETE'
  })
}

/** 表单预览：与建任务同一份请求体，服务端按当前时刻渲染一遍、不落库。 */
export function previewScheduledTask(payload: ScheduledTaskInput) {
  return request({
    url: '/admin/scheduled-tasks/preview',
    method: 'POST',
    data: payload
  })
}

export interface ScheduledTaskPreview {
  /** 规范化后的 IANA 时区名（留空时是服务端的默认时区）。 */
  timezone: string
  /** 渲染那一刻（毫秒），对应正文里的 ${now}。 */
  now: number
  /** 所有 cron 里最近的一次时隙（毫秒），对应正文里的 ${scheduled_at}。 */
  next_fire_at: number
  /** 每条 cron 各自的下次触发，顺序与填写顺序一致。 */
  next_fires: { cron: string, next_fire_at: number }[]
  /** 渲染后的正文。 */
  content: string
  bytes: number
  max_body_bytes: number
  over_limit: boolean
}

/** 请求体是驼峰（沿用管理台既有接口风格），响应才是下划线。 */
export interface ScheduledTaskInput {
  channelNo: string
  name?: string
  title?: string
  content: string
  level?: string
  senderName?: string
  /** 留空服务端按 scheduled 落库；超 64 字截断。 */
  senderId?: string
  /** 只收 http(s)、最多 1024 字符；空串表示不带头像。 */
  senderAvatar?: string
  images: string[]
  cronExpr: string
  enabled?: number
  /** 自由标签；留空即"未分类"。服务端只做 trim 与 32 字收口，不校验取值集合。 */
  category?: string
  /** IANA 时区名，留空服务端按 Asia/Shanghai 落库；非法值 400。 */
  timezone?: string
}
