<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  subtitle?: string
  width?: string
  /** 内容区不留内边距（用于自定义布局，如左右分栏） */
  flush?: boolean
  closeOnMask?: boolean
}>(), { width: '520px', closeOnMask: true })

const emit = defineEmits<{ (e: 'close'): void }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) emit('close')
}
watch(() => props.open, (v) => {
  if (v) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-mask" @mousedown.self="closeOnMask && emit('close')">
        <div class="modal" :style="{ width }" role="dialog">
          <div v-if="title || $slots.header" class="modal-head">
            <slot name="header">
              <div class="modal-title-wrap">
                <h3 class="modal-title">{{ title }}</h3>
                <p v-if="subtitle" class="modal-sub">{{ subtitle }}</p>
              </div>
            </slot>
            <slot name="head-extra" />
            <button class="icon-btn modal-x" aria-label="关闭" @click="emit('close')"><X /></button>
          </div>
          <button v-else class="icon-btn modal-x floating" aria-label="关闭" @click="emit('close')"><X /></button>
          <div class="modal-body" :class="{ flush }">
            <slot />
          </div>
          <div v-if="$slots.footer" class="modal-foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-mask {
  position: fixed; inset: 0; z-index: 1000; background: rgba(15, 18, 24, 0.42);
  display: flex; align-items: center; justify-content: center; padding: 24px;
  backdrop-filter: blur(1.5px);
}
.modal {
  background: var(--bg-elev); border-radius: 16px; box-shadow: var(--shadow-lg);
  max-width: 100%; max-height: calc(100vh - 48px); display: flex; flex-direction: column;
  border: 1px solid var(--border);
}
.modal-head { display: flex; align-items: flex-start; gap: 12px; padding: 18px 20px 10px; }
.modal-title-wrap { flex: 1; min-width: 0; }
.modal-title { font-size: 16px; font-weight: 650; }
.modal-sub { font-size: 12.5px; color: var(--text-3); margin-top: 3px; line-height: 1.5; }
.modal-x { margin: -4px -6px 0 0; }
.modal-x.floating { position: absolute; top: 10px; right: 10px; margin: 0; z-index: 2; background: var(--bg-elev); }
.modal { position: relative; }
.modal-body { padding: 8px 20px 16px; overflow: auto; flex: 1; min-height: 0; }
.modal-body.flush { padding: 0; display: flex; flex-direction: column; }
.modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 20px 16px; }
.modal-enter-active, .modal-leave-active { transition: opacity .18s ease; }
.modal-enter-active .modal, .modal-leave-active .modal { transition: transform .18s ease, opacity .18s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from .modal, .modal-leave-to .modal { transform: translateY(8px) scale(.985); opacity: 0; }
</style>
