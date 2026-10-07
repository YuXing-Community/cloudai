<script setup lang="ts">
import { computed } from 'vue'
import { colorOf, initials } from '@/utils/format'

const props = withDefaults(defineProps<{ name?: string; avatar?: string | null; size?: number; showName?: boolean; sub?: string }>(), {
  size: 22, showName: false,
})
const isImg = computed(() => !!props.avatar && /^(https?:|data:|\/)/.test(props.avatar))
</script>

<template>
  <span class="ua">
    <span class="ua-img" :style="{ width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.45) + 'px', background: isImg ? 'transparent' : colorOf(name || '') }">
      <img v-if="isImg" :src="avatar!" alt="" />
      <template v-else>{{ initials(name || '?') }}</template>
    </span>
    <span v-if="showName" class="ua-text">
      <span class="ua-name ellipsis">{{ name }}</span>
      <span v-if="sub" class="ua-sub ellipsis">{{ sub }}</span>
    </span>
  </span>
</template>

<style scoped>
.ua { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.ua-img { border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; color: #fff; font-weight: 700; overflow: hidden; flex-shrink: 0; }
.ua-img img { width: 100%; height: 100%; object-fit: cover; }
.ua-text { display: flex; flex-direction: column; min-width: 0; line-height: 1.3; }
.ua-name { font-weight: 600; color: var(--text); font-size: 13px; }
.ua-sub { font-size: 11.5px; color: var(--text-3); }
</style>
