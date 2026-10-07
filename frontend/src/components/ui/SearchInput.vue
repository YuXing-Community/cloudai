<script setup lang="ts">
import { Search, X } from 'lucide-vue-next'

withDefaults(defineProps<{ modelValue: string; placeholder?: string; width?: string; size?: 'sm' | 'md' }>(), {
  placeholder: '搜索…', width: '240px', size: 'md',
})
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void; (e: 'enter'): void }>()
</script>

<template>
  <div class="search" :class="size" :style="{ width }">
    <Search class="s-icon" />
    <input
      :value="modelValue"
      :placeholder="placeholder"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @keydown.enter="emit('enter')"
    />
    <button v-if="modelValue" class="s-clear" @click="emit('update:modelValue', '')"><X /></button>
  </div>
</template>

<style scoped>
.search { position: relative; display: flex; align-items: center; height: 32px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-input); transition: border-color .15s, box-shadow .15s; }
.search.sm { height: 28px; }
.search:focus-within { border-color: var(--primary); box-shadow: var(--ring); }
.s-icon { width: 14px; height: 14px; color: var(--text-4); margin: 0 6px 0 10px; flex-shrink: 0; }
input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 12.5px; height: 100%; }
input::placeholder { color: var(--text-4); }
.s-clear { border: none; background: transparent; color: var(--text-4); cursor: pointer; display: flex; padding: 0 8px; }
.s-clear svg { width: 13px; height: 13px; }
</style>
