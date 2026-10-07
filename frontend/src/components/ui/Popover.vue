<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 浮层：渲染到 body，按锚点元素定位（下方放不下时翻转到上方），点击外部关闭。
 */
const props = withDefaults(defineProps<{
  open: boolean
  anchor: HTMLElement | null
  align?: 'start' | 'end'
  matchWidth?: boolean
  minWidth?: number
  offset?: number
}>(), { align: 'start', matchWidth: false, minWidth: 160, offset: 6 })
const emit = defineEmits<{ (e: 'close'): void }>()

const el = ref<HTMLElement | null>(null)
const style = ref<Record<string, string>>({})

function place() {
  if (!props.anchor || !el.value) return
  const r = props.anchor.getBoundingClientRect()
  const pop = el.value.getBoundingClientRect()
  const vw = window.innerWidth, vh = window.innerHeight
  const width = props.matchWidth ? Math.max(r.width, props.minWidth) : Math.max(pop.width, props.minWidth)
  let left = props.align === 'end' ? r.right - width : r.left
  left = Math.min(Math.max(8, left), vw - width - 8)
  let top = r.bottom + props.offset
  const h = pop.height
  if (top + h > vh - 8 && r.top - props.offset - h > 8) top = r.top - props.offset - h
  const maxH = top > r.top ? r.top - 16 : vh - top - 12
  style.value = {
    left: `${left}px`, top: `${top}px`,
    ...(props.matchWidth ? { width: `${width}px` } : { minWidth: `${props.minWidth}px` }),
    maxHeight: `${Math.max(160, maxH)}px`,
  }
}

function onDown(e: MouseEvent) {
  const t = e.target as Node
  if (el.value?.contains(t) || props.anchor?.contains(t)) return
  emit('close')
}
function onKey(e: KeyboardEvent) { if (e.key === 'Escape') emit('close') }
function onScroll(e: Event) {
  if (el.value && e.target instanceof Node && el.value.contains(e.target)) return
  place()
}

watch(() => props.open, async (v) => {
  if (v) {
    style.value = { left: '-9999px', top: '-9999px' }
    await nextTick()
    place()
    document.addEventListener('mousedown', onDown, true)
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', place)
    window.addEventListener('scroll', onScroll, true)
  } else cleanup()
})
function cleanup() {
  document.removeEventListener('mousedown', onDown, true)
  document.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', onScroll, true)
}
onBeforeUnmount(cleanup)
defineExpose({ place })
</script>

<template>
  <Teleport to="body">
    <Transition name="pop">
      <div v-if="open" ref="el" class="popover" :style="style">
        <slot />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.popover {
  position: fixed; z-index: 2000; background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: 12px; box-shadow: var(--shadow-md); overflow: auto; padding: 4px;
}
</style>
