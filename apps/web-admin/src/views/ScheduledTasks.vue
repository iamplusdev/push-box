<template>
  <div>
    <div class="page-header">
      <h2>定时推送</h2>
      <div class="header-actions">
        <el-select v-model="categoryFilter" placeholder="全部分类" clearable style="width: 160px">
          <el-option
            v-for="c in categoryOptions"
            :key="c"
            :label="c === UNSET ? '未分类' : c"
            :value="c"
          />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索任务名 / 频道"
          clearable
          style="width: 240px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :loading="loading" @click="fetchTasks">刷新</el-button>
        <el-button type="primary" plain @click="openCreate">新建任务</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="filtered" stripe border style="width: 100%">
      <el-table-column prop="name" label="任务名" min-width="140" show-overflow-tooltip />
      <el-table-column label="分类" width="120" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.category">{{ row.category }}</span>
          <span v-else class="unset">未分类</span>
        </template>
      </el-table-column>
      <el-table-column label="目标频道" width="180">
        <template #default="{ row }">
          <router-link :to="`/channels/${row.channel_no}`" class="channel-link">
            {{ row.channel_no }}
          </router-link>
          <span class="channel-name">{{ row.channel_name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="cron" width="160">
        <template #default="{ row }">
          <el-tooltip
            :content="row.cron_expr"
            placement="top"
            :disabled="!isMultiCron(row.cron_expr)"
          >
            <span>{{ cronSummary(row.cron_expr) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="timezone" label="时区" width="150" show-overflow-tooltip />
      <el-table-column label="正文预览" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content_head }}</template>
      </el-table-column>
      <el-table-column label="级别" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="levelTagType(row.level || 'info')" size="small">
            {{ row.level || 'info' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled === 1"
            :loading="toggling === row.task_id"
            @change="handleToggle(row, $event as boolean)"
          />
        </template>
      </el-table-column>
      <el-table-column label="下次触发" width="170">
        <template #default="{ row }">
          {{ row.enabled === 1 ? formatTime(row.next_fire_at) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="上次执行" width="120" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="row.last_error" :content="row.last_error" placement="top">
            <el-tag type="danger" size="small" effect="plain">失败</el-tag>
          </el-tooltip>
          <el-tag v-else-if="row.last_result === 'ok'" type="success" size="small" effect="plain">
            成功
          </el-tag>
          <el-tag v-else type="info" size="small" effect="plain">未触发</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上次时间" width="170">
        <template #default="{ row }">{{ formatTime(row.last_fire_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无定时推送任务" />
      </template>
    </el-table>

    <div class="table-footer" v-if="!loading && tasks.length > 0">
      共 {{ tasks.length }} 个任务<template v-if="keyword || categoryFilter">，筛选后 {{ filtered.length }} 个</template>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑定时任务' : '新建定时任务'"
      width="620px"
      :close-on-click-modal="false"
      @closed="resetJsonDraft"
    >
      <div class="paste-json">
        <div class="paste-head">
          <span class="paste-title">粘贴 API JSON</span>
          <span class="paste-sub">只回显到下面的表单，不会提交；凭据字段一律忽略</span>
        </div>
        <el-input
          v-model="jsonDraft"
          type="textarea"
          :rows="4"
          placeholder='{"channel":"32941977","sender_id":"…","title":"…","content":"…"}'
        />
        <div class="paste-actions">
          <el-button size="small" type="primary" plain @click="applyJson">识别并填入</el-button>
          <el-button size="small" @click="jsonDraft = ''">清空</el-button>
          <span class="paste-hint">
            认得的键：channel / sender_id / sender_avatar / sender_name / title / level / content / images；
            secret、type、msg_key、ts、nonce、sign、url 与其它键都忽略，cron 与时区不受影响
          </span>
        </div>
      </div>
      <el-form :model="form" label-width="90px">
        <el-form-item label="任务名">
          <el-input v-model="form.name" maxlength="64" placeholder="例如：每日晨报" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select
            v-model="form.category"
            placeholder="选已有分类，或直接输入新建一个"
            filterable
            allow-create
            default-first-option
            clearable
            style="width: 100%"
          >
            <el-option v-for="c in namedCategories" :key="c" :label="c" :value="c" />
          </el-select>
          <div class="field-hint">
            自由标签，32 字以内；留空的任务在上方筛选里选「未分类」能找到
          </div>
        </el-form-item>
        <el-form-item label="目标频道" required>
          <el-select v-model="form.channelNo" placeholder="选择频道" filterable style="width: 100%">
            <el-option
              v-for="c in channels"
              :key="c.channel_no"
              :label="`${c.channel_no} · ${c.name}`"
              :value="c.channel_no"
            />
          </el-select>
          <div v-if="channels.length === 0" class="field-hint">
            还没有任何频道：管理台暂无建频道入口，先用 <code>POST /api/v1/admin/channels</code> 建一个再回来
          </div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="512" placeholder="可留空" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            placeholder="到点按这条内容下发一条消息，与外部系统调 POST /message 等价"
          />
          <div class="variables">
            <div class="variables-head">
              <span class="variables-title">可用变量</span>
              <span class="variables-sub">点一下插入到正文末尾，悬停看说明</span>
            </div>
            <div v-for="group in variableGroups" :key="group.label" class="variable-group">
              <span class="variable-group-label">{{ group.label }}</span>
              <div class="variable-chips">
                <el-tooltip
                  v-for="v in group.items"
                  :key="v.token"
                  :content="v.desc"
                  placement="top"
                >
                  <el-tag size="small" class="variable-tag" @click="insertVariable(v.token)">
                    {{ v.token }}
                  </el-tag>
                </el-tooltip>
              </div>
            </div>
          </div>
          <div class="field-hint">
            认不出的变量会原样留在正文里；渲染后超过频道 max_body_bytes 的任务存不下来
          </div>
        </el-form-item>
        <el-form-item label="级别">
          <el-radio-group v-model="form.level">
            <el-radio-button value="info">info</el-radio-button>
            <el-radio-button value="warning">warning</el-radio-button>
            <el-radio-button value="error">error</el-radio-button>
          </el-radio-group>
          <span class="hint">最终生效级别仍受频道上限截断</span>
        </el-form-item>
        <el-form-item label="发送者">
          <el-input v-model="form.senderName" maxlength="64" placeholder="显示名，可留空" />
        </el-form-item>
        <el-form-item label="发送者 ID">
          <el-input v-model="form.senderId" maxlength="64" placeholder="留空按 scheduled 发送" />
          <div class="field-hint">
            客户端按它把消息归到同一个发送者会话；与外部系统调 <code>POST /message</code> 的 sender_id 是同一个字段
          </div>
        </el-form-item>
        <el-form-item label="发送者头像">
          <el-input v-model="form.senderAvatar" maxlength="1024" :placeholder="AVATAR_URL_HINT" />
          <div class="field-hint">
            只收 http(s) 图片地址，触发时由服务端转存；<code>data:</code> 内联头像超过列宽 1024，先传到图床再填
          </div>
        </el-form-item>
        <el-form-item label="图片外链">
          <div class="images">
            <div v-for="(url, index) in form.images" :key="index" class="image-row">
              <el-input :model-value="url" :placeholder="IMAGE_URL_HINT"
                @update:model-value="setImage(index, $event)" />
              <el-button @click="removeImage(index)">移除</el-button>
            </div>
            <el-button
              v-if="form.images.length < maxImages"
              link
              type="primary"
              @click="addImage"
            >
              + 添加图片（最多 {{ maxImages }} 条）
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="cron" required>
          <div class="cron-rows">
            <div v-for="(_, index) in cronRows" :key="index" class="cron-row">
              <el-input v-model="cronRows[index]" placeholder="0 0 9 * * *" />
              <span v-if="rowNextFire(cronRows[index])" class="cron-row-next">
                下次 {{ rowNextFire(cronRows[index]) }}
              </span>
              <el-button
                link
                type="danger"
                :disabled="cronRows.length <= 1"
                @click="removeCronRow(index)"
              >
                删除
              </el-button>
            </div>
            <el-button
              v-if="cronRows.length < MAX_CRONS"
              link
              type="primary"
              @click="addCronRow"
            >
              + 添加一条（最多 {{ MAX_CRONS }} 条，触发取最近的一次）
            </el-button>
          </div>
          <div class="presets">
            <span class="presets-label">快捷添加：</span>
            <el-button
              v-for="preset in cronPresets"
              :key="preset.cron"
              size="small"
              @click="appendCron(preset.cron)"
            >
              {{ preset.label }}
            </el-button>
          </div>
          <div class="hint">
            每行一条，需要 6 段（秒 分 时 日 月 周），Spring 不认 5 段 unix cron。
            同时写「工作日 09:00」和「周末 12:00」就是一条任务两种作息；触发精度受扫描周期（默认 15 秒）限制。
            每条的下次触发点一次「预览」就会标在对应行上
          </div>
        </el-form-item>
        <el-form-item label="时区">
          <el-select
            v-model="form.timezone"
            placeholder="Asia/Shanghai"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option
              v-for="z in timezoneOptions"
              :key="z.value"
              :label="z.label"
              :value="z.value"
            />
          </el-select>
          <div class="field-hint">
            cron 的「每天 09:00」和正文里的时间变量都按这个时区算；可以直接输入任意 IANA 时区名。
            正文里写 ${timezone:其他区域} 不会生效——定时任务只认这里选的时区
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="previewLoading" @click="handlePreview">预览</el-button>
          <div>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="消息预览" width="560px" append-to-body>
      <div v-if="preview" class="preview">
        <div class="preview-meta">
          <el-tag size="small" type="info" effect="plain">{{ preview.timezone }}</el-tag>
          <span>最近一次触发 {{ formatInZone(preview.next_fire_at, preview.timezone) }}</span>
          <span :class="['preview-bytes', { 'over-limit': preview.over_limit }]">
            {{ preview.bytes }} / {{ preview.max_body_bytes }} 字节
          </span>
        </div>
        <div v-if="preview.next_fires && preview.next_fires.length > 1" class="preview-next">
          <div v-for="fire in preview.next_fires" :key="fire.cron" class="preview-next-row">
            <code>{{ fire.cron }}</code>
            <span>{{ formatInZone(fire.next_fire_at, preview.timezone) }}</span>
          </div>
        </div>
        <el-alert
          v-if="preview.over_limit"
          type="error"
          :closable="false"
          show-icon
          title="渲染后超过该频道的 max_body_bytes，保存会被拒绝"
        />
        <div class="preview-bubble">
          <div class="preview-sender">
            <el-tag size="small" :type="levelTagType(form.level || 'info')" effect="plain">
              {{ form.level || 'info' }}
            </el-tag>
            <span>{{ form.senderName || '（未填发送者）' }}</span>
            <span class="preview-sender-id">sender_id：{{ form.senderId || 'scheduled' }}</span>
          </div>
          <div v-if="form.title" class="preview-title">{{ form.title }}</div>
          <div class="preview-content">{{ preview.content || '（正文为空）' }}</div>
        </div>
        <div class="field-hint preview-hint">
          ${now} 按预览这一刻渲染，${scheduled_at} 按所有规则里最近的那一次渲染；到点真正发出时这两个值会变。
        </div>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getScheduledTasks,
  getScheduledTask,
  createScheduledTask,
  updateScheduledTask,
  setScheduledTaskEnabled,
  deleteScheduledTask,
  previewScheduledTask
} from '@/api/scheduledTasks'
import type { ScheduledTaskInput, ScheduledTaskPreview } from '@/api/scheduledTasks'
import { getChannels } from '@/api/channels'
import type { Channel, ScheduledTask } from '@/api/types'
import { formatTime, levelTagType } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const tasks = ref<ScheduledTask[]>([])
const channels = ref<Channel[]>([])
const keyword = ref('')
const categoryFilter = ref('')
const toggling = ref('')
const dialogVisible = ref(false)
const editingId = ref('')
const previewVisible = ref(false)
const previewLoading = ref(false)
const preview = ref<ScheduledTaskPreview | null>(null)

/**
 * 筛选下拉里"未分类"用的哨兵值。不能用空串当它的 value：el-select 里空串等于没选，
 * 选中后显示的是 placeholder，看不出自己到底筛的是哪一档。
 */
const UNSET = '__unset__'

const DEFAULT_CRON = '0 0 9 * * *'

const emptyForm = (): ScheduledTaskInput => ({
  channelNo: '',
  name: '',
  title: '',
  content: '',
  level: 'info',
  senderName: '',
  senderId: '',
  senderAvatar: '',
  images: [],
  cronExpr: DEFAULT_CRON,
  enabled: 1,
  category: '',
  timezone: 'Asia/Shanghai'
})
const form = ref<ScheduledTaskInput>(emptyForm())

/**
 * 粘贴进来的 API JSON 只做回显：留在组件内存里，不提交、不落 localStorage、不写日志
 * （那段 JSON 里有 secret）。对话框一关就清掉。
 */
const jsonDraft = ref('')

/** 与服务端 MAX_CRONS 一致：多到这程度已经是在写调度表，不是在配一条消息了。 */
const MAX_CRONS = 8
/** cron 在表单里是一行一条，提交时才拼成换行串；form.cronExpr 只做载荷字段。 */
const cronRows = ref<string[]>([DEFAULT_CRON])
/** 上一次预览算出的每条规则的下次触发，用来贴回对应行；规则或时区一改就作废。 */
const cronNextFires = ref<Record<string, number>>({})

watch([cronRows, () => form.value.timezone], () => {
  cronNextFires.value = {}
}, { deep: true })

/** 列表里多行 cron 只露第一条 + 条数，完整内容走 tooltip。 */
function isMultiCron(expr: string) {
  return (expr || '').split(/\r?\n/).filter(line => line.trim()).length > 1
}

function cronSummary(expr: string) {
  const lines = (expr || '').split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  if (lines.length === 0) return ''
  return lines.length === 1 ? lines[0] : `${lines[0]} 等 ${lines.length} 条`
}

function addCronRow() {
  if (cronRows.value.length < MAX_CRONS) {
    cronRows.value.push('')
  }
}

function removeCronRow(index: number) {
  if (cronRows.value.length > 1) {
    cronRows.value.splice(index, 1)
  }
}

/** 预设是"加一条"而不是"替换"：工作日 + 周末这种组合就是点两次出来的。 */
function appendCron(cron: string) {
  const existing = cronRows.value.map(line => line.trim()).filter(Boolean)
  if (existing.includes(cron)) {
    ElMessage.info('这条规则已经有了')
    return
  }
  if (cronRows.value.length === 1 && !cronRows.value[0].trim()) {
    cronRows.value[0] = cron
    return
  }
  if (cronRows.value.length >= MAX_CRONS) {
    ElMessage.warning(`最多 ${MAX_CRONS} 条 cron`)
    return
  }
  cronRows.value.push(cron)
}

/** 提交/预览前把行收口成换行串；空行不算一条。 */
function collectCrons(): string[] {
  return cronRows.value.map(line => line.trim()).filter(Boolean)
}

function rowNextFire(cron: string) {
  const at = cronNextFires.value[cron.trim()]
  return at ? formatInZone(at, form.value.timezone || 'Asia/Shanghai') : ''
}

/** 常用时区；下拉可过滤也可直接输入，所以这里只是"顺手"的候选，不是白名单。 */
const timezoneOptions = [
  { value: 'Asia/Shanghai', label: 'Asia/Shanghai（北京时间）' },
  { value: 'Asia/Hong_Kong', label: 'Asia/Hong_Kong' },
  { value: 'Asia/Singapore', label: 'Asia/Singapore' },
  { value: 'Asia/Tokyo', label: 'Asia/Tokyo' },
  { value: 'UTC', label: 'UTC' },
  { value: 'Europe/London', label: 'Europe/London' },
  { value: 'Europe/Berlin', label: 'Europe/Berlin' },
  { value: 'America/New_York', label: 'America/New_York' },
  { value: 'America/Los_Angeles', label: 'America/Los_Angeles' },
  { value: 'Australia/Sydney', label: 'Australia/Sydney' }
]

/** 正文里能用的占位符。与 packages/core 的 Templates 一一对应，改动时两边要一起改。 */
const variableGroups = [
  {
    label: '时间',
    items: [
      { token: '${now}', desc: '当前时间，2026-10-24 09:00:00' },
      { token: '${now:yyyy-MM-dd}', desc: '自定义格式，Java DateTimeFormatter 写法' },
      { token: '${date}', desc: '当天日期，2026-10-24' },
      { token: '${time}', desc: '时:分，09:00' },
      { token: '${weekday}', desc: '星期几，如 星期六' },
      { token: '${timestamp}', desc: '毫秒时间戳' },
      { token: '${scheduled_at}', desc: '计划触发时刻，与 ${now} 可能相差几十秒' },
      { token: '${timezone}', desc: '任务时区' }
    ]
  },
  {
    label: '任务/频道',
    items: [
      { token: '${task_name}', desc: '任务名' },
      { token: '${channel_no}', desc: '频道号' },
      { token: '${channel_name}', desc: '频道名' },
      { token: '${sender_name}', desc: '发送者显示名' },
      { token: '${host}', desc: '服务端主机名' },
      { token: '${version}', desc: '服务端版本号' }
    ]
  }
]

/** 插到正文末尾；光标定位到末尾再插入，符合"写到一半想起来补个时间"的用法。 */
function insertVariable(token: string) {
  form.value.content = (form.value.content || '') + token
}

// 从脚本里绑定而不是写成模板静态属性：vue-tsc 3.3 下，v-for 作用域内的静态属性值一旦含
// "//"（比如这个 https://），该 v-for 的别名就会解析成 "Cannot find name"。
const IMAGE_URL_HINT = 'https://… 的图片地址'
const AVATAR_URL_HINT = 'https://… 的头像图片地址'

/** 只是回显用的白名单：JSON 里其余键（含 secret 这类凭据）一概不读。 */
function asText(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function resetJsonDraft() {
  jsonDraft.value = ''
}

/**
 * 把粘贴的 JSON 回填到表单：只覆盖 JSON 里真有的键，cron 与时区永远不动。
 * 频道按 channel_no 精确匹配（JSON 里的 channel 就是外部系统用的那个频道号）。
 */
async function applyJson() {
  const raw = jsonDraft.value.trim()
  if (!raw) {
    ElMessage.warning('先粘贴一段 JSON')
    return
  }
  let data: any
  try {
    data = JSON.parse(raw)
  } catch (e) {
    ElMessage.error('这不是合法的 JSON，检查一下引号与逗号')
    return
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    ElMessage.error('需要粘贴一个 JSON 对象，而不是数组或裸值')
    return
  }
  const filled: string[] = []
  if (data.channel !== undefined && data.channel !== null) {
    if (channels.value.length === 0) {
      await fetchChannels()
    }
    const channelNo = asText(data.channel).trim()
    if (channelNo && channels.value.some(c => c.channel_no === channelNo)) {
      form.value.channelNo = channelNo
      filled.push('channel')
    } else {
      ElMessage.warning(`JSON 里的频道 ${channelNo || '（空）'} 不在当前列表里，目标频道保持原值`)
    }
  }
  if (data.sender_id !== undefined) {
    form.value.senderId = asText(data.sender_id).trim()
    filled.push('sender_id')
  }
  if (data.sender_avatar !== undefined) {
    const avatar = asText(data.sender_avatar).trim()
    if (!avatar) {
      form.value.senderAvatar = ''
      filled.push('sender_avatar')
    } else if (avatar.length <= 1024 && /^https?:\/\/\S+$/i.test(avatar)) {
      form.value.senderAvatar = avatar
      filled.push('sender_avatar')
    } else {
      ElMessage.warning('sender_avatar 只收 1024 字符内的 http(s) 地址，这一项已跳过')
    }
  }
  if (data.sender_name !== undefined) {
    form.value.senderName = asText(data.sender_name).trim()
    filled.push('sender_name')
  }
  if (data.title !== undefined) {
    form.value.title = asText(data.title)
    filled.push('title')
  }
  if (data.level !== undefined) {
    const level = asText(data.level).trim().toLowerCase()
    if (level === 'info' || level === 'warning' || level === 'error') {
      form.value.level = level
      filled.push('level')
    } else {
      ElMessage.warning(`级别 ${asText(data.level) || '（空）'} 不认识（只有 info/warning/error），这一项已跳过`)
    }
  }
  if (data.content !== undefined) {
    form.value.content = asText(data.content)
    filled.push('content')
  }
  if (data.images !== undefined) {
    if (Array.isArray(data.images)) {
      form.value.images = (data.images as unknown[]).map(asText).map((url: string) => url.trim()).filter(Boolean)
      filled.push('images')
    } else {
      ElMessage.warning('images 不是数组，这一项已跳过')
    }
  }
  if (filled.length === 0) {
    ElMessage.warning('没认出任何可填的键；cron 与时区也不会被这段 JSON 改动')
    return
  }
  ElMessage.success(`已填入 ${filled.join('、')}；cron 与时区未动`)
}

/** 6 段 cron（秒 分 时 日 月 周）——Spring CronExpression 不认 5 段 unix 写法。 */
const cronPresets = [
  { label: '每分钟', cron: '* * * * * *' },
  { label: '每 5 分钟', cron: '0 */5 * * * *' },
  { label: '每小时', cron: '0 0 * * * *' },
  { label: '每天 09:00', cron: '0 0 9 * * *' },
  { label: '工作日 09:00', cron: '0 0 9 * * 1-5' },
  { label: '周末 12:00', cron: '0 0 12 * * 0,6' },
  { label: '每周一 09:00', cron: '0 0 9 * * 1' }
]

/** 图片条数上限跟所选频道的 max_images 走，服务端在触发时还会再判一次。 */
const maxImages = computed(() => {
  const channel = channels.value.find(c => c.channel_no === form.value.channelNo)
  return channel?.max_images || 4
})

/** 列表里已经用过的分类，去重排序；没建过字典表，所以候选值只能从现有任务里现取。 */
const namedCategories = computed(() =>
  [...new Set(tasks.value.map(t => (t.category || '').trim()).filter(c => c))]
    .sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
)

/** 筛选下拉的选项：有任务没分类时才多挂一节「未分类」，否则那一档筛出来永远是空的。 */
const categoryOptions = computed(() => {
  const hasUnset = tasks.value.some(t => !(t.category || '').trim())
  return hasUnset ? [...namedCategories.value, UNSET] : namedCategories.value
})

const filtered = computed(() => {
  let out = tasks.value
  if (categoryFilter.value) {
    out = out.filter(t =>
      categoryFilter.value === UNSET
        ? !(t.category || '').trim()
        : (t.category || '').trim() === categoryFilter.value
    )
  }
  const k = keyword.value.trim().toLowerCase()
  if (!k) return out
  return out.filter(
    t =>
      (t.name || '').toLowerCase().includes(k) ||
      t.channel_no.toLowerCase().includes(k) ||
      (t.channel_name || '').toLowerCase().includes(k)
  )
})

async function fetchTasks() {
  loading.value = true
  try {
    const res: any = await getScheduledTasks()
    tasks.value = res.tasks || []
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

async function fetchChannels() {
  try {
    const res: any = await getChannels()
    channels.value = res.channels || []
  } catch (e) {
    // 拦截器已提示错误
  }
}

function openCreate() {
  editingId.value = ''
  form.value = emptyForm()
  cronRows.value = [DEFAULT_CRON]
  resetJsonDraft()
  dialogVisible.value = true
  // 频道可能在别处被删掉；打开表单时重拉一次，免得下拉里留着一条点了就 404 的旧值
  fetchChannels()
}

async function openEdit(row: ScheduledTask) {
  try {
    const res: any = await getScheduledTask(row.task_id)
    const task = res.task
    editingId.value = row.task_id
    form.value = {
      channelNo: task.channel_no,
      name: task.name,
      category: task.category || '',
      title: task.title,
      content: task.content || '',
      level: task.level || 'info',
      senderName: task.sender_name,
      senderId: task.sender_id || '',
      senderAvatar: task.sender_avatar || '',
      images: task.images || [],
      cronExpr: task.cron_expr,
      enabled: task.enabled,
      timezone: task.timezone || 'Asia/Shanghai'
    }
    const lines = String(task.cron_expr || '').split(/\r?\n/).map((line: string) => line.trim()).filter(Boolean)
    cronRows.value = lines.length > 0 ? lines : [DEFAULT_CRON]
    resetJsonDraft()
    dialogVisible.value = true
    fetchChannels()
  } catch (e) {
    // 拦截器已提示错误
  }
}

function setImage(index: number, value: string | number) {
  form.value.images[index] = String(value)
}

function addImage() {
  if (form.value.images.length < maxImages.value) {
    form.value.images.push('')
  }
}

function removeImage(index: number) {
  form.value.images.splice(index, 1)
}

/** 预览里的触发时刻按任务自己的时区显示：标签写着 Asia/Tokyo，旁边再来个本机时间就会互相打脸。 */
function formatInZone(ts: number, zone: string) {
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: zone,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      hour12: false
    }).format(new Date(ts))
  } catch (e) {
    return formatTime(ts)
  }
}

/**
 * 预览与保存都要以频道为参数，而频道可能已在别处删掉——那样服务端只会回一个
 * `404 {error:"not_found", hint:"channel"}`，在表单里看着像是"接口不存在"。
 * 这里先拿当前列表对一遍；下面两个 catch 里还有一道兜底，管"列表加载之后才被删"的情况。
 */
async function ensureChannel(): Promise<boolean> {
  if (!form.value.channelNo) {
    ElMessage.warning('请选择目标频道')
    return false
  }
  if (channels.value.some(c => c.channel_no === form.value.channelNo)) {
    return true
  }
  ElMessage.warning('所选频道已不存在（可能已被删除），已刷新频道列表，请重新选择')
  form.value.channelNo = ''
  await fetchChannels()
  return false
}

/** 服务端说这个频道不能用时（被删回 404、被归档回 invalid_param），清掉选择并刷新列表。 */
async function resetIfChannelGone(e: any): Promise<boolean> {
  const data = e?.response?.data
  const gone =
    (data?.error === 'not_found' && data?.hint === 'channel') ||
    (data?.error === 'invalid_param' && String(data?.hint || '').includes('归档'))
  if (!gone) {
    return false
  }
  form.value.channelNo = ''
  await fetchChannels()
  return true
}

async function handlePreview() {
  if (!(await ensureChannel())) {
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  const crons = collectCrons()
  if (crons.length === 0) {
    ElMessage.warning('cron 表达式必填')
    return
  }
  previewLoading.value = true
  try {
    // 渲染留在服务端：Java 的 DateTimeFormatter 语法前端复刻不出来，两边一定会长歪
    const res: any = await previewScheduledTask({
      ...form.value,
      cronExpr: crons.join('\n'),
      images: form.value.images.filter(url => url.trim())
    })
    preview.value = res.preview
    // 每条规则的下次触发贴回对应行；服务端算的才是准的，前端不另算一套
    const fires: Record<string, number> = {}
    for (const fire of res.preview?.next_fires || []) {
      fires[fire.cron] = fire.next_fire_at
    }
    cronNextFires.value = fires
    previewVisible.value = true
  } catch (e) {
    await resetIfChannelGone(e)
  } finally {
    previewLoading.value = false
  }
}

async function handleSave() {
  if (!(await ensureChannel())) {
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  const crons = collectCrons()
  if (crons.length === 0) {
    ElMessage.warning('cron 表达式必填')
    return
  }
  // 分类是 allow-create 的下拉，没有 maxlength 可挡，超长只能在这儿拦：
  // 服务端会静默截到 32 字，截出来的那个词和用户想写的就不是同一个分类了。
  const category = (form.value.category || '').trim()
  if (category.length > 32) {
    ElMessage.warning('分类不能超过 32 字，现在是 ' + category.length + ' 字')
    return
  }
  const payload: ScheduledTaskInput = {
    ...form.value,
    cronExpr: crons.join('\n'),
    category,
    timezone: (form.value.timezone || 'Asia/Shanghai').trim(),
    images: form.value.images.filter(url => url.trim())
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateScheduledTask(editingId.value, payload)
    } else {
      await createScheduledTask(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    fetchTasks()
  } catch (e) {
    await resetIfChannelGone(e)
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: ScheduledTask, enabled: boolean) {
  toggling.value = row.task_id
  try {
    await setScheduledTaskEnabled(row.task_id, enabled)
    fetchTasks()
  } catch (e) {
    // 拦截器已提示错误，开关以列表结果为准
  } finally {
    toggling.value = ''
  }
}

async function handleDelete(row: ScheduledTask) {
  try {
    await ElMessageBox.confirm(
      `确定删除定时任务「${row.name || row.task_id}」吗？删除后不再触发，已发出的消息不受影响。`,
      '删除确认',
      { type: 'warning', confirmButtonType: 'danger', confirmButtonText: '删除' }
    )
    await deleteScheduledTask(row.task_id)
    ElMessage.success('已删除')
    fetchTasks()
  } catch (e) {
    // 用户取消或请求失败
  }
}

onMounted(() => {
  fetchTasks()
  fetchChannels()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.channel-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.channel-name {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.images {
  width: 100%;
}

.paste-json {
  margin-bottom: 18px;
  padding: 10px 12px 12px;
  background: #fafbfc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.paste-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.paste-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.paste-sub {
  font-size: 12px;
  color: #a8abb2;
}

.paste-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.paste-hint {
  flex: 1;
  min-width: 240px;
  font-size: 12px;
  line-height: 1.6;
  color: #a8abb2;
}

.image-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.image-row :deep(.el-input) {
  flex: 1;
}

.cron-rows {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cron-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cron-row :deep(.el-input) {
  flex: 1;
}

.cron-row-next {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-color-success);
}

.presets {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.presets-label {
  font-size: 12px;
  color: #909399;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.field-hint {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.variables {
  width: 100%;
  margin-top: 8px;
  padding: 10px 12px 12px;
  background: #fafbfc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.variables-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.variables-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
}

.variables-sub {
  font-size: 12px;
  color: #a8abb2;
}

.variable-group {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.variable-group + .variable-group {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.variable-group-label {
  flex: none;
  width: 56px;
  padding-top: 2px;
  font-size: 12px;
  line-height: 20px;
  color: #a8abb2;
}

.variable-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.variable-tag {
  cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--el-color-primary);
  background: #eef4ff;
  border-color: #d6e4ff;
}

.variable-tag:hover {
  background: #e0ebff;
  border-color: var(--el-color-primary);
}

.unset {
  color: #909399;
  font-size: 12px;
}

.table-footer {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}

.dialog-footer {
  display: flex;
  justify-content: space-between;
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 12px;
  color: #606266;
}

.preview-bytes {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

.preview-bytes.over-limit {
  color: var(--el-color-danger);
  font-weight: 600;
}

.preview-next {
  margin-bottom: 12px;
  padding: 8px 10px;
  background: #fafbfc;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 12px;
  color: #606266;
}

.preview-next-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  line-height: 1.9;
}

.preview-next-row code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--el-color-primary);
}

.preview-bubble {
  margin-top: 12px;
  padding: 12px 14px;
  max-height: 320px;
  overflow: auto;
  background: #f5f7fa;
  border-radius: 8px;
}

.preview-sender {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
  color: #909399;
}

.preview-sender-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: #a8abb2;
}

.preview-title {
  margin-bottom: 6px;
  font-weight: 600;
}

.preview-content {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-hint {
  margin: 10px 0 0;
}
</style>
