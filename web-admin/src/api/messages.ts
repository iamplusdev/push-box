import request from '@/utils/request'

export interface MessageRow {
  seq: number
  msg_id: string
  channel_no: string
  channel_name: string | null
  group_id: string
  sender_id: string
  sender_name: string | null
  msg_type: string
  title: string | null
  summary: string | null
  body: string
  level: string | null
  media_status: string
  created_at: number
}

export interface MessageSearchParams {
  q: string
  channel_no?: string
  type?: string
  since?: number
  limit?: number
}

export interface OverviewStats {
  total_channels: number
  active_channels: number
  total_devices: number
  active_devices: number
  live_messages: number
  messages_today: number
}

export interface TrendPoint {
  day: string
  count: number
}

export function searchMessages(params: MessageSearchParams): Promise<{ count: number; messages: MessageRow[] }> {
  return request({ url: '/admin/messages', method: 'GET', params })
}

export function getOverview(): Promise<{ stats: OverviewStats; trend: TrendPoint[] }> {
  return request({ url: '/admin/overview', method: 'GET' })
}
