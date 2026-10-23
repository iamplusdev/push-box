export interface Channel {
  channel_no: string
  name: string
  group_id: string
  max_level: string
  max_images: number
  rate_limit: number
  max_body_bytes: number
  strict_auth: number
  muted: number
  daily_msg_limit: number
  daily_image_limit: number
  daily_bytes_limit: number
  sender_whitelist_mode: number
  created_at: number
  archived_at: number | null
}

export interface Device {
  device_id: string
  name: string | null
  platform: string | null
  group_id: string
  created_at: number
  last_seen_at: number | null
  revoked_at?: number | null
}

/** 定时推送任务。列表不含 content（只有 content_head），编辑走 detail 拿完整载荷。 */
export interface ScheduledTask {
  task_id: string
  group_id: string
  channel_no: string
  channel_name?: string
  name: string
  category: string
  title: string
  content?: string
  content_head?: string
  images?: string[]
  level: string
  sender_name: string
  cron_expr: string
  enabled: number
  next_fire_at: number
  last_fire_at: number
  last_result: string | null
  last_error: string | null
  last_msg_id: string | null
  created_at: number
  updated_at: number
}
