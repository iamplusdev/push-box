<template>
  <el-button link type="primary" size="small" @click.stop="handle">复制</el-button>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { copyText } from '@/utils/clipboard'

const props = withDefaults(defineProps<{ text: string; label?: string }>(), { label: '' })

async function handle() {
  const ok = await copyText(String(props.text ?? ''))
  if (ok) ElMessage.success(props.label ? `已复制${props.label}` : '已复制')
  else ElMessage.error('复制失败，请手动选中后复制')
}
</script>
