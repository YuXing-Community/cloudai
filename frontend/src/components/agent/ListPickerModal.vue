<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search, RefreshCw, Plus, Layers, Check } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'

/**
 * 通用“列表 + 添加”选择弹窗：MCP Server / 记忆库 / SubAgent 共用
 * items: {id, title, sub?, desc?, tags?: string[], avatar?: any}
 */
const props = defineProps<{
  open: boolean
  title: string
  subtitle?: string
  items: any[]
  addedIds: (string | number)[]
  categoryLabel?: string
  categorySub?: string
  createText?: string
  createTo?: string
  searchPlaceholder?: string
  modes?: { label: string; value: string }[]
  mono?: boolean
}>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'add', item: any): void; (e: 'refresh'): void }>()
const router = useRouter()

const q = ref('')
const mode = ref(props.modes?.[0]?.value || '')
watch(() => props.open, (v) => { if (v) q.value = '' })
const filtered = computed(() => props.items.filter((i) =>
  (!props.modes || !i.mode || i.mode === mode.value) &&
  (!q.value || `${i.title} ${i.sub || ''} ${i.desc || ''}`.toLowerCase().includes(q.value.toLowerCase()))))
</script>

<template>
  <Modal :open="open" :title="title" :subtitle="subtitle" width="860px" flush @close="emit('close')">
    <div class="lp">
      <aside v-if="categoryLabel" class="lp-cats">
        <div class="cat active">
          <Layers class="cat-ico" />
          <div style="flex: 1; min-width: 0">
            <div class="bold">{{ categoryLabel }}</div>
            <div v-if="categorySub" class="tiny muted">{{ categorySub }}</div>
          </div>
          <span class="tag tag-blue">{{ items.length }}</span>
        </div>
      </aside>
      <div class="lp-main">
        <div v-if="modes" class="lp-modes">
          <div class="segmented" style="width: 360px">
            <button v-for="m in modes" :key="m.value" style="flex: 1; justify-content: center" :class="{ active: mode === m.value }" @click="mode = m.value">{{ m.label }}</button>
          </div>
        </div>
        <div class="lp-bar">
          <div class="lp-search"><Search /><input v-model="q" :placeholder="searchPlaceholder || '搜索名称或描述'" /></div>
          <span v-if="!createTo" class="small muted">共 {{ filtered.length }} 个</span>
          <button class="icon-btn bordered" title="刷新" @click="emit('refresh')"><RefreshCw /></button>
          <button v-if="createTo" class="btn" :class="createText?.includes('MCP') ? 'btn-primary' : ''" @click="router.push(createTo)"><Plus /> {{ createText }}</button>
        </div>
        <div class="lp-list">
          <div v-for="it in filtered" :key="it.id" class="lp-item">
            <slot name="icon" :item="it" />
            <div style="flex: 1; min-width: 0">
              <div class="row" style="gap: 6px; flex-wrap: wrap">
                <span class="bold">{{ it.title }}</span>
                <span v-for="t in it.tags || []" :key="t" class="tag tag-round" :class="t === 'active' ? '' : 'tag-mono'">{{ t }}</span>
              </div>
              <div v-if="it.sub" class="lp-sub ellipsis" :class="{ mono }">{{ it.sub }}</div>
              <div v-if="it.desc" class="small muted ellipsis">{{ it.desc }}</div>
            </div>
            <button v-if="addedIds.includes(it.id)" class="btn btn-sm" disabled><Check /> 已添加</button>
            <button v-else class="btn btn-sm" @click="emit('add', it)"><Plus /> 添加</button>
          </div>
          <div v-if="!filtered.length" class="empty small">暂无可添加的条目</div>
        </div>
      </div>
    </div>
  </Modal>
</template>

<style scoped>
.lp { display: flex; height: 580px; border-top: 1px solid var(--divider); }
.lp-cats { width: 230px; flex-shrink: 0; border-right: 1px solid var(--divider); padding: 12px; }
.cat { display: flex; align-items: center; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 12px; }
.cat.active { box-shadow: var(--shadow); }
.cat-ico { width: 18px; height: 18px; color: var(--primary); }
.lp-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.lp-modes { padding: 14px 16px 0; }
.lp-bar { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--divider); }
.lp-search { flex: 1; display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; }
.lp-search:focus-within { border-color: var(--primary); box-shadow: var(--ring); }
.lp-search svg { width: 15px; height: 15px; color: var(--text-4); }
.lp-search input { flex: 1; border: none; outline: none; background: transparent; }
.lp-list { flex: 1; overflow: auto; }
.lp-item { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--divider); }
.lp-item:hover { background: var(--bg-hover); }
.lp-sub { font-size: 12px; color: var(--text-3); margin-top: 2px; }
</style>
