<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Check, ChevronDown, ChevronsUpDown, Search } from 'lucide-vue-next'
import Popover from './Popover.vue'

export interface SelectOption {
  label: string
  value: any
  /** 第二行说明 */
  sub?: string
  /** 分组标题 */
  group?: string
  /** 右侧附加文字（如“默认”） */
  extra?: string
  disabled?: boolean
  tag?: string
}

const props = withDefaults(defineProps<{
  modelValue: any
  options: SelectOption[]
  placeholder?: string
  searchable?: boolean
  searchPlaceholder?: string
  disabled?: boolean
  size?: 'sm' | 'md'
  /** chevrons：上下箭头样式（截图里 Agent 选择器） */
  chevrons?: boolean
  clearable?: boolean
  minWidth?: number
  /** 只渲染触发器的自定义内容 */
  bare?: boolean
}>(), { placeholder: '请选择', searchable: false, size: 'md', minWidth: 180 })

const emit = defineEmits<{ (e: 'update:modelValue', v: any): void; (e: 'change', v: any): void }>()

const open = ref(false)
const q = ref('')
const trigger = ref<HTMLElement | null>(null)
const searchEl = ref<HTMLInputElement | null>(null)

const current = computed(() => props.options.find((o) => o.value === props.modelValue))
const filtered = computed(() => {
  const k = q.value.trim().toLowerCase()
  if (!k) return props.options
  return props.options.filter((o) => `${o.label} ${o.sub || ''} ${o.group || ''}`.toLowerCase().includes(k))
})
const grouped = computed(() => {
  const out: { group?: string; items: SelectOption[] }[] = []
  for (const o of filtered.value) {
    const last = out[out.length - 1]
    if (last && last.group === o.group) last.items.push(o)
    else out.push({ group: o.group, items: [o] })
  }
  return out
})

watch(open, async (v) => {
  if (v) {
    q.value = ''
    await nextTick()
    searchEl.value?.focus()
  }
})

function pick(o: SelectOption) {
  if (o.disabled) return
  emit('update:modelValue', o.value)
  emit('change', o.value)
  open.value = false
}
</script>

<template>
  <div class="sel-wrap">
    <button
      ref="trigger"
      type="button"
      class="sel"
      :class="[size, { open, disabled, bare }]"
      :disabled="disabled"
      @click="open = !open"
    >
      <slot name="trigger" :current="current">
        <slot name="prefix" />
        <span v-if="current" class="sel-val ellipsis">{{ current.label }}</span>
        <span v-else class="sel-ph ellipsis">{{ placeholder }}</span>
      </slot>
      <ChevronsUpDown v-if="chevrons" class="sel-arrow" />
      <ChevronDown v-else class="sel-arrow" />
    </button>
    <Popover :open="open" :anchor="trigger" match-width :min-width="minWidth" @close="open = false">
      <div v-if="searchable" class="sel-search">
        <Search />
        <input ref="searchEl" v-model="q" :placeholder="searchPlaceholder || '搜索…'" />
      </div>
      <div class="sel-list">
        <template v-for="(g, gi) in grouped" :key="gi">
          <div v-if="g.group" class="sel-group">{{ g.group }}</div>
          <div
            v-for="o in g.items"
            :key="String(o.value)"
            class="sel-opt"
            :class="{ active: o.value === modelValue, disabled: o.disabled }"
            @click="pick(o)"
          >
            <slot name="option" :option="o">
              <div class="sel-opt-main">
                <div class="ellipsis">{{ o.label }} <span v-if="o.tag" class="tag" style="margin-left: 4px">{{ o.tag }}</span></div>
                <div v-if="o.sub" class="sel-opt-sub ellipsis">{{ o.sub }}</div>
              </div>
            </slot>
            <span v-if="o.extra" class="sel-extra">{{ o.extra }}</span>
            <Check v-if="o.value === modelValue" class="sel-check" />
          </div>
        </template>
        <div v-if="!filtered.length" class="sel-empty">无匹配项</div>
      </div>
      <slot name="footer" :close="() => (open = false)" />
    </Popover>
  </div>
</template>

<style scoped>
.sel-wrap { position: relative; min-width: 0; }
.sel {
  width: 100%; height: 34px; display: flex; align-items: center; gap: 8px; padding: 0 10px 0 11px;
  border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-input);
  cursor: pointer; text-align: left; transition: border-color .15s, box-shadow .15s; font-size: 13px;
}
.sel:hover { border-color: var(--border-strong); }
.sel.open { border-color: var(--primary); box-shadow: var(--ring); }
.sel.sm { height: 28px; font-size: 12.5px; padding: 0 8px; }
.sel.disabled { background: var(--bg-muted); color: var(--text-3); cursor: not-allowed; }
.sel.bare { border-color: transparent; background: transparent; box-shadow: none; padding: 0 6px; width: auto; }
.sel.bare:hover { background: var(--bg-hover); }
.sel-val { flex: 1; color: var(--text); }
.sel-ph { flex: 1; color: var(--text-4); }
.sel-arrow { width: 14px; height: 14px; color: var(--text-4); flex-shrink: 0; margin-left: auto; }
.sel-search { display: flex; align-items: center; gap: 6px; padding: 6px 8px 8px; border-bottom: 1px solid var(--divider); margin-bottom: 4px; }
.sel-search svg { width: 14px; height: 14px; color: var(--text-4); }
.sel-search input { flex: 1; border: none; outline: none; background: transparent; font-size: 13px; }
.sel-list { overflow: auto; }
.sel-group { padding: 8px 8px 4px; font-size: 11.5px; color: var(--text-3); }
.sel-opt { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.sel-opt:hover { background: var(--bg-hover); }
.sel-opt.active { background: var(--primary-soft); color: var(--primary-text); }
.sel-opt.disabled { opacity: .45; cursor: not-allowed; }
.sel-opt-main { flex: 1; min-width: 0; }
.sel-opt-sub { font-size: 11px; color: var(--text-3); font-family: var(--mono); margin-top: 1px; }
.sel-extra { font-size: 11.5px; color: var(--text-3); }
.sel-check { width: 14px; height: 14px; color: var(--primary); flex-shrink: 0; }
.sel-empty { padding: 14px; text-align: center; color: var(--text-4); font-size: 12.5px; }
</style>
