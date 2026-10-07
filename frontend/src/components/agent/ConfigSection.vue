<script setup lang="ts">
import { ref } from 'vue'
import { ChevronDown, Plus } from 'lucide-vue-next'

const props = withDefaults(defineProps<{ title: string; icon?: any; count?: number; tone?: string; addable?: boolean; defaultOpen?: boolean }>(), {
  tone: 'blue', addable: false, defaultOpen: true,
})
const emit = defineEmits<{ (e: 'add', ev: MouseEvent): void }>()
const open = ref(props.defaultOpen)
</script>

<template>
  <section class="sec" :class="[`tone-${tone}`, { open }]">
    <header class="sec-head" @click="open = !open">
      <ChevronDown class="sec-chev" />
      <span class="sec-ico"><component :is="icon" /></span>
      <span class="sec-title">{{ title }}</span>
      <span v-if="count" class="sec-count">{{ count }}</span>
      <div class="spacer" />
      <slot name="extra" />
      <button v-if="addable" class="icon-btn sm primary-ring add" @click.stop="emit('add', $event)"><Plus /></button>
    </header>
    <div v-show="open" class="sec-body"><slot /></div>
  </section>
</template>

<style scoped>
.sec { border: 1px solid var(--border); border-radius: 14px; background: var(--bg-elev); margin-bottom: 14px; overflow: hidden; }
.sec-head { display: flex; align-items: center; gap: 9px; padding: 10px 14px; cursor: pointer; user-select: none; background: var(--sec-bg); border-bottom: 1px solid transparent; }
.open .sec-head { border-bottom-color: var(--divider); }
.sec-chev { width: 14px; height: 14px; color: var(--text-4); transition: transform .15s; transform: rotate(-90deg); }
.open .sec-chev { transform: none; }
.sec-ico { width: 26px; height: 26px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; background: var(--sec-ico-bg); color: var(--sec-ico); }
.sec-ico svg { width: 14px; height: 14px; }
.sec-title { font-weight: 600; font-size: 13.5px; }
.sec-count { font-size: 11.5px; color: var(--sec-ico); font-family: var(--mono); }
.add { width: 24px; height: 24px; }
.sec-body { padding: 12px 14px 14px; }
.tone-purple { --sec-bg: color-mix(in srgb, var(--purple-soft) 55%, var(--bg-elev)); --sec-ico-bg: var(--purple-soft); --sec-ico: var(--purple-text); }
.tone-orange { --sec-bg: color-mix(in srgb, var(--orange-soft) 55%, var(--bg-elev)); --sec-ico-bg: var(--orange-soft); --sec-ico: var(--orange-text); }
.tone-blue { --sec-bg: color-mix(in srgb, var(--primary-soft) 55%, var(--bg-elev)); --sec-ico-bg: var(--primary-soft); --sec-ico: var(--primary-text); }
.tone-cyan { --sec-bg: color-mix(in srgb, var(--cyan-soft) 55%, var(--bg-elev)); --sec-ico-bg: var(--cyan-soft); --sec-ico: var(--cyan-text); }
.tone-green { --sec-bg: color-mix(in srgb, var(--green-soft) 55%, var(--bg-elev)); --sec-ico-bg: var(--green-soft); --sec-ico: var(--green-text); }
.tone-gray { --sec-bg: var(--bg-muted); --sec-ico-bg: var(--bg-hover); --sec-ico: var(--text-2); }
</style>
