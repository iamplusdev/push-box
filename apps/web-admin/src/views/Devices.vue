<template>
  <div>
    <PageHeader title="设备管理" description="设备注册后接收其空间内频道的消息；吊销立即作废并被踢下线。">
      <template #actions>
        <el-input
          v-model="keyword"
          placeholder="搜索设备 ID / 名称 / 空间"
          clearable
          style="width: 280px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :loading="loading" @click="fetchDevices">刷新</el-button>
      </template>
    </PageHeader>

    <el-table v-loading="loading" :data="paged" stripe border style="width: 100%">
      <el-table-column label="设备 ID" min-width="260">
        <template #default="{ row }">
          <div class="id-cell">
            <span class="id-text pb-mono" :title="row.device_id">{{ row.device_id }}</span>
            <CopyButton :text="row.device_id" label="设备 ID" />
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="platform" label="平台" width="100" />
      <el-table-column label="所属空间" width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="pb-mono">{{ row.group_id }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.revoked_at" type="info" size="small" effect="plain">已吊销</el-tag>
          <el-tag v-else-if="isOnline(row)" type="success" size="small" effect="plain">在线</el-tag>
          <el-tag v-else type="warning" size="small" effect="plain">离线</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="170">
        <template #default="{ row }">
          <span class="pb-mono">{{ formatTime(row.created_at) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近在线" width="170">
        <template #default="{ row }">
          <span class="pb-mono">{{ formatTime(row.last_seen_at) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.revoked_at"
            type="danger"
            link
            @click="handleRevoke(row)"
          >吊销</el-button>
          <span v-else class="muted-dash">-</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无设备" />
      </template>
    </el-table>

    <div class="table-footer" v-if="!loading && devices.length > 0">
      <span
        >共 {{ devices.length }} 台设备<template v-if="keyword">，筛选后 {{ filtered.length }} 台</template></span
      >
      <el-pagination
        v-model:current-page="page"
        :page-size="PAGE_SIZE"
        :total="filtered.length"
        layout="prev, pager, next"
        background
        hide-on-single-page
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import CopyButton from '@/components/CopyButton.vue'
import PageHeader from '@/components/PageHeader.vue'
import { getDevices, revokeDevice } from '@/api/devices'
import type { Device } from '@/api/types'
import { formatTime } from '@/utils/format'

const ONLINE_WINDOW_MS = 90 * 1000
const PAGE_SIZE = 20

const loading = ref(false)
const devices = ref<Device[]>([])
const keyword = ref('')
const page = ref(1)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return devices.value
  return devices.value.filter(
    d =>
      d.device_id.toLowerCase().includes(k) ||
      (d.name || '').toLowerCase().includes(k) ||
      (d.group_id || '').toLowerCase().includes(k)
  )
})

// 前端分页只切展示用的那一页；筛选或吊销让结果变短时把页码夹回有效范围
const paged = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return filtered.value.slice(start, start + PAGE_SIZE)
})

watch(keyword, () => {
  page.value = 1
})

watch(
  () => filtered.value.length,
  len => {
    const maxPage = Math.max(1, Math.ceil(len / PAGE_SIZE))
    if (page.value > maxPage) page.value = maxPage
  }
)

function isOnline(row: Device): boolean {
  return !!row.last_seen_at && Date.now() - row.last_seen_at < ONLINE_WINDOW_MS
}

async function fetchDevices() {
  loading.value = true
  try {
    const res: any = await getDevices()
    devices.value = res.devices || []
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

async function handleRevoke(row: Device) {
  try {
    await ElMessageBox.confirm(
      `确定吊销设备 ${row.name || row.device_id} 吗？吊销后该设备立即作废并被踢下线。`,
      '吊销确认',
      { type: 'warning', confirmButtonType: 'danger', confirmButtonText: '吊销' }
    )
    await revokeDevice(row.device_id)
    ElMessage.success('已吊销')
    fetchDevices()
  } catch (e) {
    // 用户取消或请求失败
  }
}

onMounted(fetchDevices)
</script>

<style scoped>
.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  font-size: var(--pb-font-label);
  line-height: var(--pb-line-label);
  color: var(--el-text-color-secondary);
}

.id-cell {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.id-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted-dash {
  color: #c0c4cc;
}
</style>
