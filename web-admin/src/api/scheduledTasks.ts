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

/** 请求体是驼峰（沿用管理台既有接口风格），响应才是下划线。 */
export interface ScheduledTaskInput {
  channelNo: string
  name?: string
  title?: string
  content: string
  level?: string
  senderName?: string
  images: string[]
  cronExpr: string
  enabled?: number
}
