<script setup lang="ts">
import { computed } from 'vue'
import { fmtScore, scoreTone } from '@/api/eval'

const props = withDefaults(defineProps<{ score: number | string | null | undefined; size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })
const n = computed(() => (props.score == null || props.score === '' ? null : Number(props.score)))
</script>

<template>
  <span class="score" :class="[scoreTone(n), size]">{{ fmtScore(n) }}</span>
</template>

<style scoped>
.score { white-space: nowrap; flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center; min-width: 40px; height: 22px; padding: 0 8px; border-radius: 999px; font-weight: 700; font-size: 12.5px; font-variant-numeric: tabular-nums; background: var(--bg-muted); color: var(--text-3); }
.score.sm { min-width: 34px; height: 20px; font-size: 12px; padding: 0 6px; }
.score.lg { min-width: 64px; height: 40px; font-size: 22px; border-radius: 12px; }
.score.good { background: var(--green-soft); color: var(--green-text); }
.score.ok { background: var(--primary-soft); color: var(--primary-text); }
.score.bad { background: var(--red-soft); color: var(--red-text); }
</style>
