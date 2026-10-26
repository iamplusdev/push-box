<template>
  <div class="page-header">
    <div class="page-heading">
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<style scoped>
/* 六个内页原来各自复制一份 .page-header（18px h2、16px 间距、没有说明），
   收进这一个组件：标题走 H1 档，下面配一句 13px 说明，与内容留 24px */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.page-heading h1 {
  margin: 0;
  font-size: var(--pb-font-h1);
  line-height: var(--pb-line-h1);
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-desc {
  margin: 4px 0 0;
  font-size: var(--pb-font-small);
  line-height: var(--pb-line-small);
  color: var(--el-text-color-secondary);
}

.page-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-shrink: 0;
}
</style>
