<template>
  <div v-loading="loading">
    <PageHeader title="概览" description="频道、设备与消息量的运行概况。">
      <template #actions>
        <el-button type="primary" :loading="loading" @click="fetchOverview">刷新</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num">{{ stats.active_channels }}<span class="stat-sub">/ {{ stats.total_channels }}</span></div>
          <div class="stat-label">活跃频道 / 总数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num">{{ stats.active_devices }}<span class="stat-sub">/ {{ stats.total_devices }}</span></div>
          <div class="stat-label">在册设备 / 总数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num">{{ stats.messages_today }}</div>
          <div class="stat-label">今日消息</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num">{{ stats.live_messages }}</div>
          <div class="stat-label">累计消息</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <span>近 14 天消息量</span>
      </template>
      <div ref="chartEl" class="chart"></div>
      <el-empty v-if="!loading && trend.length === 0" description="暂无数据" :image-size="80" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import PageHeader from '@/components/PageHeader.vue'
import { getOverview, type OverviewStats, type TrendPoint } from '@/api/messages'

const loading = ref(false)
const chartEl = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

const stats = ref<OverviewStats>({
  total_channels: 0,
  active_channels: 0,
  total_devices: 0,
  active_devices: 0,
  live_messages: 0,
  messages_today: 0
})
const trend = ref<TrendPoint[]>([])

function renderChart() {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value)
  }
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: trend.value.map(t => t.day),
      axisLabel: { formatter: (v: string) => v.slice(5) }
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      type: 'bar',
      data: trend.value.map(t => t.count),
      itemStyle: { color: '#409eff', borderRadius: [3, 3, 0, 0] },
      barMaxWidth: 32
    }]
  })
}

function handleResize() {
  chart?.resize()
}

async function fetchOverview() {
  loading.value = true
  try {
    const res = await getOverview()
    stats.value = res.stats
    trend.value = res.trend || []
    await nextTick()
    renderChart()
  } catch (e) {
    // 拦截器已提示错误
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchOverview()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
}

/* 统计数字原来 30px——规范只有六档，最大 20。数字仍靠字重与留白压住，不靠再大一号 */
.stat-num {
  font-size: var(--pb-font-h1);
  line-height: var(--pb-line-h1);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.stat-sub {
  font-size: var(--pb-font-small);
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 4px;
}

.stat-label {
  font-size: var(--pb-font-small);
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.chart {
  height: 300px;
  width: 100%;
}
</style>
