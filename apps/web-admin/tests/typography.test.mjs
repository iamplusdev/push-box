/* §9.2 那张字号表，落到管理台这一端的闸。
 *
 * 两端早就各有一份 TypographyTest（Android 15 个槽位、Windows 两本字典 + 不许出现数字 FontSize），
 * 管理台此前只有"构建通过 + 真浏览器手量"，于是表之外的数还能悄悄长出来：
 * 2026-10-26 这一步之前在 `ScheduledTasks.vue` 里就留着 `line-height: 20px` 与 `line-height: 1.9`
 * 两处——都不是六档里的行高，也没有任何一处会红。
 *
 * 所以这里断的不是"某个数字等于某个数字"，而是三件事：
 *   ① 表本身对得上规范：六档字号 + 各自行高 + Element 六档的重指 + 三级文字色；
 *   ② 视图里不许再出现裸字号/裸行高/裸等宽字体族——要写就写 var(--pb-*) 或挂 .pb-mono；
 *   ③ 页头、表格、截断、对话框滚动这几处"曾经各写一遍"的地方，来源只有一个。
 *
 * 跑法：`npm test`（`node --test`，Node ≥ 20，不引任何依赖）。
 * 改 `src/styles/typography.css`、`PageHeader.vue` 或任何视图的 `<style>` 之后都要跑。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = join(root, 'src')

/** §9.2 的字号 → 行高（1.53× 取整）：20/28、18/25、16/22、15/23、13/18、12/16。 */
const SPEC = {
  h1: [20, 28],
  h2: [18, 25],
  h3: [16, 22],
  body: [15, 23],
  small: [13, 18],
  label: [12, 16]
}

/** 六个内页：页头必须走 PageHeader，且各带一句说明。Login 不是内页，不在这张表里。 */
const PAGES = ['Dashboard', 'Channels', 'ChannelDetail', 'Devices', 'MessageSearch', 'ScheduledTasks']

const read = (...parts) => readFileSync(join(...parts), 'utf8')
const rel = path => relative(root, path).replaceAll('\\', '/')

function vueFiles(dir = srcDir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory()
      ? vueFiles(join(dir, entry.name))
      : entry.name.endsWith('.vue')
        ? [join(dir, entry.name)]
        : []
  )
}

/** 注释里的数字不是声明，先剥掉再解析。 */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '')
}

function declarations(body) {
  const out = {}
  for (const part of body.split(';')) {
    const colon = part.indexOf(':')
    if (colon === -1) continue
    out[part.slice(0, colon).trim()] = part.slice(colon + 1).trim()
  }
  return out
}

/** 拆成 {selector, decls}。@media 之类的嵌套展平，选择器前面带上那层条件。 */
function rules(text, context = '') {
  const clean = stripComments(text)
  const out = []
  let i = 0
  while (i < clean.length) {
    const open = clean.indexOf('{', i)
    if (open === -1) break
    const selector = clean.slice(i, open).trim().replace(/\s+/g, ' ')
    let depth = 1
    let j = open + 1
    while (j < clean.length && depth > 0) {
      if (clean[j] === '{') depth += 1
      else if (clean[j] === '}') depth -= 1
      j += 1
    }
    const body = clean.slice(open + 1, j - 1)
    if (selector.startsWith('@')) out.push(...rules(body, `${context}${selector} `))
    else out.push({ selector: `${context}${selector}`, decls: declarations(body) })
    i = j
  }
  return out
}

function styleRules(vueText) {
  return [...vueText.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].flatMap(block => rules(block[1]))
}

const typography = () => rules(read(srcDir, 'styles', 'typography.css'))
const rootDecls = () => {
  const found = typography().find(rule => rule.selector === ':root')
  assert.ok(found, 'typography.css 里没有 :root 块——六档字号没有落脚点')
  return found.decls
}
const pick = (list, selector) => {
  const found = list.find(rule => rule.selector === selector)
  assert.ok(found, `找不到规则 ${selector}`)
  return found
}

test('六档字号与行高逐值对表', () => {
  const decls = rootDecls()
  for (const [name, [size, line]] of Object.entries(SPEC)) {
    assert.equal(decls[`--pb-font-${name}`], `${size}px`, `${name} 档字号应是 ${size}px`)
    assert.equal(decls[`--pb-line-${name}`], `${line}px`, `${name} 档行高应是 ${line}px`)
  }
})

test('Element 的六档全部重指到 pb 的六档', () => {
  const decls = rootDecls()
  const steps = ['extra-large', 'large', 'medium', 'base', 'small', 'extra-small']
  const targets = steps.map(step => {
    const value = decls[`--el-font-size-${step}`]
    assert.ok(value, `--el-font-size-${step} 没有重指，Element 会退回自己的基线（base 是 14px，不在表里）`)
    const match = /^var\(--pb-font-(\w+)\)$/.exec(value)
    assert.ok(match, `--el-font-size-${step} 应指向 var(--pb-font-*)，实际写的是 ${value}`)
    return match[1]
  })
  assert.deepEqual(
    [...targets].sort(),
    Object.keys(SPEC).sort(),
    'Element 六档与 pb 六档必须一一对应：多一个说明有第七档，少一个说明有一档没接管'
  )
})

test('三级文字色是规范里的三个值', () => {
  const decls = rootDecls()
  // #909399 在白底上只有 2.9:1，12 号副文本几乎糊掉，所以次级色压到 #5f5f5f
  assert.equal(decls['--el-text-color-primary'], '#19191a')
  assert.equal(decls['--el-text-color-regular'], '#3f3f45')
  assert.equal(decls['--el-text-color-secondary'], '#5f5f5f')
})

test('视图里没有裸字号与裸行高', () => {
  const offenders = []
  for (const file of vueFiles()) {
    for (const rule of styleRules(read(file))) {
      for (const prop of ['font-size', 'line-height']) {
        const value = rule.decls[prop]
        if (value && /^[\d.]/.test(value)) offenders.push(`${rel(file)} ${rule.selector} { ${prop}: ${value} }`)
      }
    }
    // 模板里的内联 style 是另一条路，同样不许写数字
    const text = read(file)
    for (const match of text.matchAll(/<[^>]*style="[^"]*(?:font-size|line-height):[^"]*"/g)) {
      if (/:\s*[\d.]/.test(match[0])) offenders.push(`${rel(file)} 内联样式 ${match[0].slice(0, 80)}`)
    }
  }
  assert.deepEqual(offenders, [], '要写字号/行高就用 var(--pb-font-*) / var(--pb-line-*)，第七个数不许出现')
})

test('等宽字体族只在 typography.css 里写一次', () => {
  const list = typography()
  const mono = pick(list, '.pb-mono')
  assert.equal(mono.decls['font-family'], 'var(--pb-font-mono)')
  assert.equal(mono.decls['font-variant-numeric'], 'tabular-nums', '等宽不加 tabular-nums，数字仍然不对齐')

  // Element 的 input/textarea 自带字体声明，不显式压一道，频道号与 JSON 输入框还是比例字体
  const inner = pick(list, '.pb-mono input, .pb-mono textarea')
  assert.equal(inner.decls['font-family'], 'var(--pb-font-mono)')
  assert.equal(inner.decls['font-variant-numeric'], 'tabular-nums')

  for (const file of vueFiles()) {
    assert.doesNotMatch(
      read(file),
      /--pb-font-mono|ui-monospace|Consolas|monospace/,
      `${rel(file)} 自己写了等宽字体族——挂 .pb-mono 就够，散写一遍就改不动第二遍`
    )
  }
})

test('长文列的两行截断是两行', () => {
  const clamp = pick(typography(), '.pb-clamp2')
  assert.equal(clamp.decls['display'], '-webkit-box')
  assert.equal(clamp.decls['-webkit-line-clamp'], '2')
  assert.equal(clamp.decls['-webkit-box-orient'], 'vertical')
  assert.equal(clamp.decls['overflow'], 'hidden')
})

test('表格节奏：13px 正文 + 600 表头 + 11px 上下内边距', () => {
  const list = typography()
  assert.equal(pick(list, '.el-table').decls['font-size'], 'var(--pb-font-small)')
  assert.equal(pick(list, '.el-table th.el-table__cell').decls['font-weight'], '600')
  assert.equal(
    pick(list, '.el-table th.el-table__cell, .el-table td.el-table__cell').decls['padding'],
    '11px 0',
    '这一处是 44px 数据行的来源：内容 19px + 上下各 11px + 1px 边框；改成 12 就 46、改成 8 就 38'
  )
  assert.equal(pick(list, '.el-table .cell').decls['line-height'], 'var(--pb-line-small)')
  // Element 的 .el-button 自己钉了 base 字号，混在 13 号的行里会大一圈
  assert.equal(pick(list, '.el-table .el-button').decls['font-size'], 'var(--pb-font-small)')
})

test('页头只有一个来源，四个数都在 PageHeader 里', () => {
  const list = styleRules(read(srcDir, 'components', 'PageHeader.vue'))
  const header = pick(list, '.page-header')
  assert.equal(header.decls['margin-bottom'], '24px', '页头与第一张表之间是 24px（间距规范的 XL）')
  assert.equal(header.decls['gap'], '16px')

  const title = pick(list, '.page-heading h1')
  assert.equal(title.decls['font-size'], 'var(--pb-font-h1)')
  assert.equal(title.decls['line-height'], 'var(--pb-line-h1)')
  assert.equal(title.decls['font-weight'], '600')
  assert.equal(title.decls['color'], 'var(--el-text-color-primary)')

  const desc = pick(list, '.page-desc')
  assert.equal(desc.decls['font-size'], 'var(--pb-font-small)')
  assert.equal(desc.decls['line-height'], 'var(--pb-line-small)')
  assert.equal(desc.decls['color'], 'var(--el-text-color-secondary)')

  assert.equal(pick(list, '.page-actions').decls['gap'], '12px')
})

test('六个内页都走 PageHeader 且各带一句说明', () => {
  for (const page of PAGES) {
    const path = join(srcDir, 'views', `${page}.vue`)
    const text = read(path)
    assert.match(text, /import PageHeader from '@\/components\/PageHeader\.vue'/, `${page} 没有引 PageHeader`)
    assert.match(text, /<PageHeader[^>]*\btitle="/, `${page} 的页头缺标题`)
    assert.match(text, /<PageHeader[^>]*\bdescription="/, `${page} 的页头缺那句说明`)
    const own = styleRules(text).filter(rule => /\.page-header\b/.test(rule.selector))
    assert.deepEqual(
      own.map(rule => rule.selector),
      [],
      `${page} 自己又写了一份 .page-header——六个页面各抄一遍就是这么来的`
    )
  }
})

test('定时任务对话框只有两处滚动', () => {
  const scrolling = styleRules(read(srcDir, 'views', 'ScheduledTasks.vue')).filter(rule =>
    Object.entries(rule.decls).some(
      ([prop, value]) => prop === 'max-height' || (/^overflow(-y)?$/.test(prop) && /auto|scroll/.test(value))
    )
  )
  assert.deepEqual(
    scrolling.map(rule => rule.selector),
    // 第二处是预览气泡那道 320px：它模拟的就是气泡自己的视口，属于内容不属于容器
    [':deep(.el-dialog__body)', '.preview-bubble'],
    '对话框只允许 body 一层滚动；多一层就会出现"指针停在哪层决定滚谁"'
  )
})

test('typography.css 必须排在 element-plus 之后', () => {
  const main = read(srcDir, 'main.ts')
  const element = main.indexOf('element-plus/dist/index.css')
  const pb = main.indexOf('./styles/typography.css')
  assert.ok(element !== -1, 'main.ts 没有引 element-plus 的样式')
  assert.ok(pb !== -1, 'main.ts 没有引 typography.css')
  assert.ok(element < pb, '两边都在 :root 上写自定义属性，同权重时后写的赢——顺序反了 14px 基线会压回来')
})
