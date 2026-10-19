export function formatTime(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

/** 级别上限用颜色标签区分：error 红、warn 橙、info 绿。 */
export function levelTagType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch ((level || '').toLowerCase()) {
    case 'error':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'info':
      return 'success'
    default:
      return 'info'
  }
}

/** 每日额度：0 表示不限。 */
export function formatQuota(n: number | null | undefined): string {
  return !n ? '不限' : String(n)
}

/** 字节数转可读单位；用于 max_body_bytes 和 daily_bytes_limit。 */
export function formatBytes(n: number | null | undefined, unlimitedZero = false): string {
  if (!n) return unlimitedZero ? '不限' : '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/** 0/1 治理位转可读文本。 */
export function formatFlag(n: number | null | undefined, on: string, off: string): string {
  return n ? on : off
}
