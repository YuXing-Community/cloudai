<script setup lang="ts">
import { X } from 'lucide-vue-next'

withDefaults(defineProps<{ open: boolean; title?: string; subtitle?: string; width?: string }>(), { width: '480px' })
const emit = defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="open" class="drawer-mask" @mousedown.self="emit('close')">
        <aside class="drawer" :style="{ width }">
          <div class="drawer-head">
            <slot name="header">
              <div style="flex: 1; min-width: 0">
                <h3 class="drawer-title">{{ title }}</h3>
                <p v-if="subtitle" class="drawer-sub">{{ subtitle }}</p>
              </div>
            </slot>
            <button class="icon-btn" @click="emit('close')"><X /></button>
          </div>
          <div class="drawer-body"><slot /></div>
          <div v-if="$slots.footer" class="drawer-foot"><slot name="footer" /></div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; z-index: 1000; background: rgba(15, 18, 24, 0.42); display: flex; justify-content: flex-end; }
.drawer { height: 100%; background: var(--bg-elev); box-shadow: var(--shadow-lg); display: flex; flex-direction: column; max-width: 100vw; border-left: 1px solid var(--border); }
.drawer-head { display: flex; align-items: flex-start; gap: 10px; padding: 16px 18px 12px; border-bottom: 1px solid var(--divider); }
.drawer-title { font-size: 16px; font-weight: 650; display: flex; align-items: center; gap: 8px; }
.drawer-sub { font-size: 12.5px; color: var(--text-3); margin-top: 3px; line-height: 1.5; word-break: break-all; }
.drawer-body { flex: 1; overflow: auto; padding: 16px 18px; }
.drawer-foot { padding: 12px 18px; border-top: 1px solid var(--divider); display: flex; justify-content: flex-end; gap: 8px; }
.drawer-enter-active, .drawer-leave-active { transition: opacity .2s ease; }
.drawer-enter-active .drawer, .drawer-leave-active .drawer { transition: transform .22s cubic-bezier(.2,.8,.2,1); }
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
.drawer-enter-from .drawer, .drawer-leave-to .drawer { transform: translateX(32px); }
</style>
