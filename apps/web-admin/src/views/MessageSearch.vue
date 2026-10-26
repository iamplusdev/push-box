<template>
  <div>
    <div class="page-header">
      <h2>消息搜索</h2>
    </div>

    <el-form :model="form" inline class="search-form" @submit.prevent="handleSearch">
      <el-form-item label="关键词">
        <el-input
          v-model="form.q"
          placeholder="标题 / 摘要 / 正文"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="频道号">
        <el-input v-model="form.channel_no" placeholder="8 位，可选" clearable style="width: 130px" />
      </el-form-item>
      <el-form-item label="类型">
        <el-input v-model="form.type" placeholder="如 text，可选" clearable style="width: 120px" />
      </el-form-item>
      <el-form-item label="起始时间">
        <el-date-picker
          v-model="form.since"
          type="date"
          placeholder="不限"
          value-format="x"
          style="width: 150px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleSearch">搜索</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="messages" stripe border style="width: 100%">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="msg-detail">
            <div class="detail-line"><span class="k">msg_id</span>{{ row.msg_id }}</div>
            <div class="detail-line"><span class="k">seq</span>{{ row.seq }}</div>
            <div class="detail-line"><span class="k">发送者</span>{{ row.sender_name || '-' }}（{{ row.sender_id }}）</div>
            <div class="detail-line"><span class="k">所属空间</span>{{ row.group_id }}</div>
            <div class="detail-line"><span class="k">媒体状态</span>{{ row.media_status }}</div>
            <div class="detail-body">{{ row.body }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="频道" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <router-link :to="`/channels/${row.channel_no}`" class="channel-link">{{ row.channel_no }}</router-link>
          <span class="chan-name">{{ row.channel_name || '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="sender_name" label="发送者" min-width="120" show-overflow-tooltip />
      <el-table-column prop="msg_type" label="类型" width="90" />
      <el-table-column label="级别" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="levelTagType(row.level || '')" size="small">{{ row.level || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ row.title || row.summary || row.body }}</template>
      </el-table-column>
      <template #empty>
        <el-empty :description="searched ? '没有匹配的消息' : '输入关键词后开始搜索'" />
      </template>
    </el-table>

    <div class="table-footer" v-if="searched && messages.length">
      命中 {{ messages.length }} 条（上限 {{ form.limit }}）
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { searchMessages, type MessageRow } from '@/api/messages'
import { formatTime, levelTagType } from '@/utils/format'

const loading = ref(false)
const searched = ref(false)
const messages = ref<MessageRow[]>([])

const form = reactive({
  q: '',
  channel_no: '',
  type: '',
  since: '' as string | number,
  limit: 50
})

async function handleSearch() {
  if (!form.q.trim()) {
    ElMessage.warning('请输入关键词')
    return
  }
  loading.value = true
  try {
    const since = form.since ? Number(form.since) : 0
    const res = await searchMessages({
      q: form.q.trim(),
      channel_no: form.channel_no.trim() || undefined,
      type: form.type.trim() || undefined,
      since: since || undefined,
      limit: form.limit
    })
    messages.value = res.messages || []
    searched.value = true
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

function handleReset() {
  form.q = ''
  form.channel_no = ''
  form.type = ''
  form.since = ''
  messages.value = []
  searched.value = false
}
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
  font-size: var(--pb-font-h2);
  line-height: var(--pb-line-h2);
}

.search-form {
  margin-bottom: 8px;
}

.channel-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.chan-name {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: var(--pb-font-label);
}

.table-footer {
  margin-top: 12px;
  font-size: var(--pb-font-label);
  line-height: var(--pb-line-label);
  color: var(--el-text-color-secondary);
}

.msg-detail {
  padding: 8px 24px;
  font-size: var(--pb-font-small);
  color: var(--el-text-color-primary);
}

.detail-line {
  margin-bottom: 4px;
}

.detail-line .k {
  display: inline-block;
  width: 80px;
  color: var(--el-text-color-secondary);
}

.detail-body {
  margin-top: 8px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
