<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = withDefaults(defineProps<{ source: string | null | undefined; compact?: boolean }>(), { compact: false })

marked.setOptions({ gfm: true, breaks: false })

/** 去掉 YAML front matter，作为元数据卡片单独展示由调用方处理 */
const html = computed(() => {
  const raw = props.source || ''
  try {
    return DOMPurify.sanitize(marked.parse(raw, { async: false }) as string)
  } catch {
    return DOMPurify.sanitize(raw)
  }
})
</script>

<template>
  <div class="md" :class="{ compact }" v-html="html" />
</template>
