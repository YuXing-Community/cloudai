<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ page: number; size: number; total: number; compact?: boolean }>()
const emit = defineEmits<{ (e: 'update:page', v: number): void; (e: 'change', v: number): void }>()
const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(1, props.size))))

function go(p: number) {
  if (p < 1 || p > pages.value || p === props.page) return
  emit('update:page', p)
  emit('change', p)
}
</script>

<template>
  <div class="pager">
    <button class="btn btn-sm" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
    <span class="pager-num">{{ page }} / {{ pages }}</span>
    <button class="btn btn-sm" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
  </div>
</template>

<style scoped>
.pager { display: inline-flex; align-items: center; gap: 8px; }
.pager .btn { box-shadow: none; color: var(--text-2); }
.pager-num { font-size: 12.5px; color: var(--text-2); font-weight: 500; min-width: 44px; text-align: center; }
</style>
