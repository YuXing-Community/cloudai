<script setup lang="ts">
import { Inbox } from 'lucide-vue-next'

defineProps<{ title?: string; desc?: string; icon?: any; compact?: boolean }>()
</script>

<template>
  <div class="empty" :style="compact ? 'padding: 18px 12px' : ''">
    <div class="empty-icon"><component :is="icon || Inbox" /></div>
    <div v-if="title" class="empty-title">{{ title }}</div>
    <div v-if="desc" class="small">{{ desc }}</div>
    <slot />
  </div>
</template>
