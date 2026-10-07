<script setup lang="ts">
import { computed } from 'vue'
import { colorOf, initials } from '@/utils/format'
import { PRESET_AVATARS } from './avatars'

/** Agent 头像：avatar 为 preset:<n>、color:#hex、图片 URL，或为空时按名称取首字 + 稳定底色 */
const props = withDefaults(defineProps<{ name: string; avatar?: string | null; size?: number; radius?: number; letters?: number }>(), {
  size: 24, radius: 7, letters: 1,
})

const isImg = computed(() => !!props.avatar && /^(https?:|data:|\/)/.test(props.avatar))
const preset = computed(() => {
  const m = /^preset:(\d+)$/.exec(props.avatar || '')
  return m ? PRESET_AVATARS[Number(m[1]) % PRESET_AVATARS.length] : null
})
const letterBg = computed(() => {
  const m = /^color:(#[0-9a-fA-F]{6})$/.exec(props.avatar || '')
  return m ? m[1] : colorOf(props.name || '')
})
</script>

<template>
  <span
    class="av"
    :style="{
      width: size + 'px', height: size + 'px', borderRadius: radius + 'px',
      fontSize: Math.round(size * (letters > 1 ? 0.38 : 0.46)) + 'px',
      background: isImg ? 'transparent' : preset ? preset.bg : letterBg,
    }"
  >
    <img v-if="isImg" :src="avatar!" alt="" />
    <span v-else-if="preset" :style="{ fontSize: Math.round(size * 0.5) + 'px' }">{{ preset.emoji }}</span>
    <template v-else>{{ initials(name, letters) }}</template>
  </span>
</template>

<style scoped>
.av { display: inline-flex; align-items: center; justify-content: center; color: #fff; font-weight: 700; flex-shrink: 0; overflow: hidden; line-height: 1; box-shadow: inset 0 -1px 0 rgba(0,0,0,.08); }
.av img { width: 100%; height: 100%; object-fit: cover; }
</style>
