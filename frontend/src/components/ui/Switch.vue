<script setup lang="ts">
defineProps<{ modelValue: boolean; disabled?: boolean; size?: 'sm' | 'md'; label?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'change', v: boolean): void }>()
</script>

<template>
  <label class="sw-wrap" :class="{ disabled }" @click.stop>
    <button
      type="button"
      role="switch"
      class="sw"
      :class="[{ on: modelValue }, size || 'md']"
      :aria-checked="modelValue"
      :disabled="disabled"
      @click="emit('update:modelValue', !modelValue); emit('change', !modelValue)"
    >
      <span class="knob" />
    </button>
    <span v-if="label" class="sw-label">{{ label }}</span>
  </label>
</template>

<style scoped>
.sw-wrap { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; }
.sw-wrap.disabled { cursor: not-allowed; opacity: .55; }
.sw { position: relative; width: 36px; height: 20px; border-radius: 999px; border: none; background: var(--border-strong); cursor: pointer; transition: background .18s; padding: 0; flex-shrink: 0; }
.sw.sm { width: 30px; height: 17px; }
.sw.on { background: var(--primary); }
.sw:disabled { cursor: not-allowed; }
.knob { position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.2); transition: transform .18s; }
.sw.sm .knob { width: 13px; height: 13px; }
.sw.on .knob { transform: translateX(16px); }
.sw.sm.on .knob { transform: translateX(13px); }
.sw-label { font-size: 13px; color: var(--text-2); }
</style>
