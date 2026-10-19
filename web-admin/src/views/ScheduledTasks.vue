<template>
  <div>
    <div class="page-header">
      <h2>定时推送</h2>
      <div class="header-actions">
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
      <el-table-column label="目标频道" width="180">
        <template #default="{ row }">
          <router-link :to="`/channels/${row.channel_no}`" class="channel-link">
            {{ row.channel_no }}
          </router-link>
          <span class="channel-name">{{ row.channel_name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="cron_expr" label="cron" width="140" show-overflow-tooltip />
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
    </el-table>

    <div class="table-footer" v-if="!loading">
      共 {{ tasks.length }} 个任务<template v-if="keyword">，筛选后 {{ filtered.length }} 个</template>
    </div>
    <el-empty v-if="!loading && tasks.length === 0" description="暂无定时推送任务" />

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑定时任务' : '新建定时任务'"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="90px">
        <el-form-item label="任务名">
          <el-input v-model="form.name" maxlength="64" placeholder="例如：每日晨报" />
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
          <el-input v-model="form.cronExpr" placeholder="0 0 9 * * *" />
          <div class="presets">
            <el-button
              v-for="preset in cronPresets"
              :key="preset.cron"
              size="small"
              @click="form.cronExpr = preset.cron"
            >
              {{ preset.label }}
            </el-button>
          </div>
          <div class="hint">
            需要 6 段（秒 分 时 日 月 周），Spring 不认 5 段 unix cron；触发精度受扫描周期（默认 15 秒）限制
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  getScheduledTasks,
  getScheduledTask,
  createScheduledTask,
  updateScheduledTask,
  setScheduledTaskEnabled,
  deleteScheduledTask
} from '@/api/scheduledTasks'
import type { ScheduledTaskInput } from '@/api/scheduledTasks'
import { getChannels } from '@/api/channels'
import type { Channel, ScheduledTask } from '@/api/types'
import { formatTime, levelTagType } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const tasks = ref<ScheduledTask[]>([])
const channels = ref<Channel[]>([])
const keyword = ref('')
const toggling = ref('')
const dialogVisible = ref(false)
const editingId = ref('')

const emptyForm = (): ScheduledTaskInput => ({
  channelNo: '',
  name: '',
  title: '',
  content: '',
  level: 'info',
  senderName: '',
  images: [],
  cronExpr: '0 0 9 * * *',
  enabled: 1
})
const form = ref<ScheduledTaskInput>(emptyForm())

// 从脚本里绑定而不是写成模板静态属性：vue-tsc 3.3 下，v-for 作用域内的静态属性值一旦含
// "//"（比如这个 https://），该 v-for 的别名就会解析成 "Cannot find name"。
const IMAGE_URL_HINT = 'https://… 的图片地址'

/** 6 段 cron（秒 分 时 日 月 周）——Spring CronExpression 不认 5 段 unix 写法。 */
const cronPresets = [
  { label: '每分钟', cron: '* * * * * *' },
  { label: '每 5 分钟', cron: '0 */5 * * * *' },
  { label: '每小时', cron: '0 0 * * * *' },
  { label: '每天 09:00', cron: '0 0 9 * * *' },
  { label: '工作日 09:00', cron: '0 0 9 * * 1-5' },
  { label: '每周一 09:00', cron: '0 0 9 * * 1' }
]

/** 图片条数上限跟所选频道的 max_images 走，服务端在触发时还会再判一次。 */
const maxImages = computed(() => {
  const channel = channels.value.find(c => c.channel_no === form.value.channelNo)
  return channel?.max_images || 4
})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return tasks.value
  return tasks.value.filter(
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
  dialogVisible.value = true
}

async function openEdit(row: ScheduledTask) {
  try {
    const res: any = await getScheduledTask(row.task_id)
    const task = res.task
    editingId.value = row.task_id
    form.value = {
      channelNo: task.channel_no,
      name: task.name,
      title: task.title,
      content: task.content || '',
      level: task.level || 'info',
      senderName: task.sender_name,
      images: task.images || [],
      cronExpr: task.cron_expr,
      enabled: task.enabled
    }
    dialogVisible.value = true
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

async function handleSave() {
  if (!form.value.channelNo) {
    ElMessage.warning('请选择目标频道')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('正文不能为空')
    return
  }
  if (!form.value.cronExpr.trim()) {
    ElMessage.warning('cron 表达式必填')
    return
  }
  const payload: ScheduledTaskInput = {
    ...form.value,
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
    // 拦截器已提示错误
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
      { type: 'warning' }
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

.image-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.image-row :deep(.el-input) {
  flex: 1;
}

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.table-footer {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
