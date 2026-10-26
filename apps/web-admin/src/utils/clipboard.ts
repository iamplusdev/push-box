/**
 * 复制文本。管理台通常跑在 `http://<NAS>:7404` 上，那不是安全上下文，
 * `navigator.clipboard` 在那种页面里根本不存在；所以必须留 `execCommand` 这条老路，
 * 否则"复制"在真实部署里只会静默失败（本机 localhost 调试时反而是通的，最容易看漏）。
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false
  try {
    if (window.isSecureContext && navigator.clipboard) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限被拒或浏览器不给，落到下面的兜底
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(area)
  return ok
}
