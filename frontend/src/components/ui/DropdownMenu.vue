<script setup lang="ts">
import { ref } from 'vue'
import { MoreHorizontal } from 'lucide-vue-next'
import Popover from './Popover.vue'

export interface MenuItem {
  label: string
  icon?: any
  danger?: boolean
  disabled?: boolean
  divider?: boolean
  onClick?: () => void
}

withDefaults(defineProps<{ items: MenuItem[]; align?: 'start' | 'end' }>(), { align: 'end' })
const open = ref(false)
const trigger = ref<HTMLElement | null>(null)

function run(it: MenuItem) {
  if (it.disabled) return
  open.value = false
  it.onClick?.()
}
</script>

<template>
  <span ref="trigger" class="dd-trigger" @click.stop="open = !open">
    <slot :open="open">
      <button class="icon-btn" :class="{ on: open }"><MoreHorizontal /></button>
    </slot>
  </span>
  <Popover :open="open" :anchor="trigger" :align="align" :min-width="150" @close="open = false">
    <template v-for="(it, i) in items" :key="i">
      <div v-if="it.divider" class="dd-divider" />
      <div v-else class="dd-item" :class="{ danger: it.danger, disabled: it.disabled }" @click.stop="run(it)">
        <component :is="it.icon" v-if="it.icon" />
        <span>{{ it.label }}</span>
      </div>
    </template>
  </Popover>
</template>

<style scoped>
.dd-trigger { display: inline-flex; }
.icon-btn.on { background: var(--bg-hover); color: var(--text); }
.dd-item { display: flex; align-items: center; gap: 9px; padding: 7px 10px; border-radius: 8px; cursor: pointer; font-size: 13px; color: var(--text); white-space: nowrap; }
.dd-item:hover { background: var(--bg-hover); }
.dd-item svg { width: 15px; height: 15px; color: var(--text-2); }
.dd-item.danger, .dd-item.danger svg { color: var(--red-text); }
.dd-item.danger:hover { background: var(--red-soft); }
.dd-item.disabled { opacity: .45; cursor: not-allowed; }
.dd-divider { height: 1px; background: var(--divider); margin: 4px 2px; }
</style>
