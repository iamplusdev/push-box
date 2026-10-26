<template>
  <div>
    <div class="page-header">
      <h2>频道管理</h2>
      <div class="header-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索号码 / 名称 / 空间"
          clearable
          style="width: 260px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" :loading="loading" @click="fetchChannels">刷新</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="filtered" stripe border style="width: 100%">
      <el-table-column prop="channel_no" label="频道号码" width="130">
        <template #default="{ row }">
          <router-link :to="`/channels/${row.channel_no}`" class="channel-link">
            {{ row.channel_no }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="group_id" label="所属空间" width="180" show-overflow-tooltip />
      <el-table-column label="级别上限" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="levelTagType(row.max_level)" size="small">{{ row.max_level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="max_images" label="图片上限" width="90" align="center" />
      <el-table-column prop="rate_limit" label="限速/分" width="95" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.muted" type="danger" size="small" effect="plain">已静音</el-tag>
          <el-tag v-else type="success" size="small" effect="plain">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无频道" />
      </template>
    </el-table>

    <div class="table-footer" v-if="!loading && channels.length > 0">
      共 {{ channels.length }} 个频道<template v-if="keyword">，筛选后 {{ filtered.length }} 个</template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getChannels, deleteChannel } from '@/api/channels'
import type { Channel } from '@/api/types'
import { formatTime, levelTagType } from '@/utils/format'

const loading = ref(false)
const channels = ref<Channel[]>([])
const keyword = ref('')

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return channels.value
  return channels.value.filter(
    c =>
      c.channel_no.toLowerCase().includes(k) ||
      (c.name || '').toLowerCase().includes(k) ||
      (c.group_id || '').toLowerCase().includes(k)
  )
})

async function fetchChannels() {
  loading.value = true
  try {
    const res: any = await getChannels()
    channels.value = res.channels || []
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

async function handleDelete(row: Channel) {
  try {
    await ElMessageBox.confirm(
      `确定删除频道 ${row.channel_no}（${row.name}）吗？删除后入站即刻 403，历史消息仍保留。`,
      '删除确认',
      { type: 'warning', confirmButtonType: 'danger', confirmButtonText: '删除' }
    )
    await deleteChannel(row.channel_no)
    ElMessage.success('已删除')
    fetchChannels()
  } catch (e) {
    // 用户取消或请求失败
  }
}

onMounted(fetchChannels)
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

.table-footer {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
