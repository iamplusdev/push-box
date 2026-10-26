<template>
  <router-view />
</template>

<script setup lang="ts">
// App root component
</script>

<style>
/* 只留清零。字体族、字号、行高与文字色都在 src/styles/typography.css 里，
   两处都写就会分叉 */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
</style>
