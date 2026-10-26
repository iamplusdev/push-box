<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>频道详情</h2>
      <el-button @click="$router.back()">返回</el-button>
    </div>

    <el-descriptions v-if="channel" :column="2" border>
      <el-descriptions-item label="频道号码">{{ channel.channel_no }}</el-descriptions-item>
      <el-descriptions-item label="名称">{{ channel.name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="所属空间">{{ channel.group_id }}</el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ formatTime(channel.created_at) }}</el-descriptions-item>
      <el-descriptions-item label="级别上限">
        <el-tag :type="levelTagType(channel.max_level)" size="small">{{ channel.max_level }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="图片上限">{{ channel.max_images }}</el-descriptions-item>
      <el-descriptions-item label="限速（条/分）">{{ channel.rate_limit }}</el-descriptions-item>
      <el-descriptions-item label="单条体上限">{{ formatBytes(channel.max_body_bytes) }}</el-descriptions-item>
      <el-descriptions-item label="严格鉴权">{{ formatFlag(channel.strict_auth, '开启', '关闭') }}</el-descriptions-item>
      <el-descriptions-item label="静音">
        <el-tag v-if="channel.muted" type="danger" size="small" effect="plain">已静音</el-tag>
        <el-tag v-else type="success" size="small" effect="plain">正常</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="发送者白名单">{{ formatFlag(channel.sender_whitelist_mode, '仅白名单', '所有人') }}</el-descriptions-item>
      <el-descriptions-item label="每日消息额度">{{ formatQuota(channel.daily_msg_limit) }}</el-descriptions-item>
      <el-descriptions-item label="每日图片额度">{{ formatQuota(channel.daily_image_limit) }}</el-descriptions-item>
      <el-descriptions-item label="每日字节额度">{{ formatBytes(channel.daily_bytes_limit, true) }}</el-descriptions-item>
    </el-descriptions>

    <h3 class="section-title">接入设备</h3>
    <el-table :data="devices" stripe border style="width: 100%">
      <el-table-column prop="device_id" label="设备 ID" min-width="200" show-overflow-tooltip />
      <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="platform" label="平台" width="100" />
      <el-table-column label="最近在线" width="170">
        <template #default="{ row }">{{ formatTime(row.last_seen_at) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无设备接入" />
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getChannelDetail } from '@/api/channels'
import type { Channel, Device } from '@/api/types'
import { formatTime, formatBytes, formatQuota, formatFlag, levelTagType } from '@/utils/format'

const route = useRoute()
const loading = ref(false)
const channel = ref<Channel | null>(null)
const devices = ref<Device[]>([])

async function fetchDetail() {
  loading.value = true
  try {
    const res: any = await getChannelDetail(String(route.params.channelNo))
    channel.value = res.channel
    devices.value = res.devices || []
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

onMounted(fetchDetail)
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

.section-title {
  margin: 24px 0 12px;
  font-size: 15px;
}
</style>
