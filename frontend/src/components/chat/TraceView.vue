<script setup lang="ts">
import { computed, ref } from 'vue'
import { ListOrdered, Timer, ChevronsDownUp, ChevronsUpDown, ZoomIn, ZoomOut, Maximize2, CheckCircle2, XCircle, Loader2, Info } from 'lucide-vue-next'
import type { SessionEvent } from '@/api/session'
import SearchInput from '@/components/ui/SearchInput.vue'
import { duration, fmtTime } from '@/utils/format'

const props = defineProps<{ events: SessionEvent[] }>()

const mode = ref<'seq' | 'time'>('seq')
const q = ref('')
const expanded = ref<Set<number>>(new Set())
const zoom = ref(1)

const LANES = [
  { key: 'input', label: 'Input', color: 'var(--primary)' },
  { key: 'model', label: 'Model', color: 'var(--purple)' },
  { key: 'tool', label: 'Tools', color: 'var(--orange)' },
] as const

function lane(ev: SessionEvent) {
  if (ev.type === 'input') return 'input'
  if (ev.type === 'model' || ev.type === 'agent' || ev.type === 'thinking') return 'model'
  if (ev.type === 'tool') return 'tool'
  return null
}

const timeline = computed(() => props.events.filter((e) => e.type !== 'status'))
const rows = computed(() => {
  const k = q.value.trim().toLowerCase()
  return timeline.value.filter((e) => e.type !== 'model' || !k).filter((e) =>
    !k || `${e.type} ${e.name || ''} ${e.content || ''} ${e.result || ''} ${e.id}`.toLowerCase().includes(k))
})

/** 泳道块：按序列模式等宽排布，时长模式按耗时比例 */
const blocks = computed(() => {
  const evs = timeline.value.filter((e) => lane(e))
  if (!evs.length) return { items: [] as any[], total: 1 }
  if (mode.value === 'seq') {
    return { items: evs.map((e, i) => ({ ev: e, lane: lane(e), x: i, w: 0.8 })), total: evs.length }
  }
  const t0 = new Date(evs[0].createdAt.replace(' ', 'T')).getTime()
  const items = evs.map((e) => {
    const end = new Date(e.createdAt.replace(' ', 'T')).getTime()
    const start = e.startedAt ? new Date(e.startedAt.replace(' ', 'T')).getTime() : end - (e.durationMs || 0)
    return { ev: e, lane: lane(e), x: Math.max(0, (start - t0) / 1000), w: Math.max(0.6, (e.durationMs || 300) / 1000) }
  })
  const total = Math.max(...items.map((i) => i.x + i.w), 1)
  return { items, total }
})

function blockColor(b: any) {
  if (b.ev.status === 'error') return 'var(--red)'
  return LANES.find((l) => l.key === b.lane)?.color
}

function typeLabel(e: SessionEvent) {
  return { input: 'INPUT', agent: 'AGENT', tool: 'TOOL', model: 'MODEL', thinking: 'THINK', error: 'ERROR', status: 'STATUS' }[e.type] || e.type
}

function preview(e: SessionEvent) {
  if (e.type === 'model') {
    try {
      const m = JSON.parse(e.content || '{}')
      return `in ${m.input_tokens} · out ${m.output_tokens} · cache ${m.cache_read || 0} · tool_calls ${m.tool_calls ?? 0}`
    } catch { return '' }
  }
  return (e.content || '').replace(/\s+/g, ' ').slice(0, 400)
}

function toggle(id: number) {
  const s = new Set(expanded.value)
  s.has(id) ? s.delete(id) : s.add(id)
  expanded.value = s
}
function expandAll(open: boolean) {
  expanded.value = open ? new Set(rows.value.map((r) => r.id)) : new Set()
}
function focus(id: number) {
  const s = new Set(expanded.value)
  s.add(id)
  expanded.value = s
  requestAnimationFrame(() => document.getElementById(`ev-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }))
}
function pretty(s?: string) {
  if (!s) return ''
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}
</script>

<template>
  <div class="trace">
    <div class="trace-bar">
      <div class="segmented">
        <button :class="{ active: mode === 'seq' }" @click="mode = 'seq'"><ListOrdered /> 顺序</button>
        <button :class="{ active: mode === 'time' }" @click="mode = 'time'"><Timer /> 时长</button>
      </div>
      <button class="icon-btn" title="全部展开" @click="expandAll(true)"><ChevronsUpDown /></button>
      <button class="icon-btn" title="全部折叠" @click="expandAll(false)"><ChevronsDownUp /></button>
      <div class="spacer" />
      <SearchInput v-model="q" placeholder="搜索事件、内容、工具或 ID…" width="300px" />
      <span class="tag">{{ timeline.length }} 事件</span>
    </div>

    <div class="overview card">
      <div class="ov-head">
        <span class="bold">轨迹概览</span><span class="faint small">点击色块定位事件</span>
        <div class="spacer" />
        <span class="small muted">按{{ mode === 'seq' ? '事件序列 (Sequence)' : '时长 (Duration)' }} · {{ zoom.toFixed(1) }}x</span>
        <button class="icon-btn sm" @click="zoom = Math.max(1, zoom - 0.5)"><ZoomOut /></button>
        <button class="icon-btn sm" @click="zoom = Math.min(6, zoom + 0.5)"><ZoomIn /></button>
        <button class="icon-btn sm" @click="zoom = 1"><Maximize2 /></button>
      </div>
      <div class="lanes">
        <div class="lane-labels">
          <div v-for="l in LANES" :key="l.key" class="lane-label"><span class="status-dot" :style="{ background: l.color }" />{{ l.label }}</div>
        </div>
        <div class="lane-scroll">
          <div class="lane-canvas" :style="{ width: zoom * 100 + '%' }">
            <div v-for="l in LANES" :key="l.key" class="lane-row">
              <button
                v-for="b in blocks.items.filter((x) => x.lane === l.key)"
                :key="b.ev.id"
                class="blk"
                :title="`${typeLabel(b.ev)} ${b.ev.name || ''} · ${duration(b.ev.durationMs)}`"
                :style="{ left: (b.x / blocks.total) * 100 + '%', width: `max(6px, ${(b.w / blocks.total) * 100}%)`, background: blockColor(b) }"
                @click="focus(b.ev.id)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="ev-table card">
      <div v-for="e in rows" :id="`ev-${e.id}`" :key="e.id" class="ev-row" :class="{ err: e.status === 'error', open: expanded.has(e.id) }">
        <div class="ev-line" @click="toggle(e.id)">
          <span class="ev-type" :class="e.type">{{ typeLabel(e) }}</span>
          <Loader2 v-if="e.status === 'running'" class="ev-st spin run" />
          <XCircle v-else-if="e.status === 'error'" class="ev-st err" />
          <Info v-else-if="e.type === 'model'" class="ev-st muted" />
          <CheckCircle2 v-else class="ev-st ok" />
          <span class="ev-name ellipsis">{{ e.type === 'input' ? 'User' : e.type === 'agent' ? 'Agent' : e.name || e.type }}</span>
          <span class="ev-dur">{{ e.durationMs ? duration(e.durationMs) : '' }}</span>
          <span class="ev-prev ellipsis">{{ preview(e) }}</span>
          <span class="ev-time">{{ fmtTime(e.createdAt) }}</span>
        </div>
        <div v-if="expanded.has(e.id)" class="ev-detail">
          <div class="d-meta">seq {{ e.seq }} · id {{ e.id }}<template v-if="e.runId"> · run {{ e.runId }}</template></div>
          <div v-if="e.content" class="d-label">{{ e.type === 'tool' ? '参数' : '内容' }}</div>
          <pre v-if="e.content" class="code-soft">{{ pretty(e.content) }}</pre>
          <div v-if="e.result" class="d-label">结果</div>
          <pre v-if="e.result" class="code-soft">{{ e.result }}</pre>
        </div>
      </div>
      <div v-if="!rows.length" class="empty small">暂无事件</div>
    </div>
  </div>
</template>

<style scoped>
.trace { padding: 14px 18px 24px; display: flex; flex-direction: column; gap: 12px; }
.trace-bar { display: flex; align-items: center; gap: 8px; }
.overview { padding: 10px 14px 12px; border-radius: 12px; }
.ov-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12.5px; }
.lanes { display: flex; gap: 10px; }
.lane-labels { width: 64px; flex-shrink: 0; }
.lane-label, .lane-row { height: 18px; margin-bottom: 4px; }
.lane-label { display: flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--text-3); }
.lane-scroll { flex: 1; overflow-x: auto; }
.lane-canvas { position: relative; min-width: 100%; }
.lane-row { position: relative; background: repeating-linear-gradient(90deg, transparent 0 calc(10% - 1px), var(--divider) calc(10% - 1px) 10%); border-radius: 4px; }
.blk { position: absolute; top: 3px; height: 12px; border-radius: 3px; border: none; cursor: pointer; opacity: .9; padding: 0; }
.blk:hover { opacity: 1; outline: 2px solid var(--bg-elev); box-shadow: 0 0 0 3px currentColor; }
.ev-table { border-radius: 12px; overflow: hidden; }
.ev-row { border-bottom: 1px solid var(--divider); }
.ev-row:last-child { border-bottom: none; }
.ev-row.err { background: color-mix(in srgb, var(--red-soft) 70%, transparent); }
.ev-line { display: flex; align-items: center; gap: 10px; padding: 8px 12px; cursor: pointer; font-size: 12.5px; }
.ev-line:hover { background: var(--bg-hover); }
.ev-type { width: 52px; flex-shrink: 0; text-align: center; font-size: 10.5px; font-weight: 700; padding: 2px 0; border-radius: 5px; font-family: var(--mono); background: var(--bg-muted); color: var(--text-3); }
.ev-type.tool { background: var(--orange-soft); color: var(--orange-text); }
.ev-type.agent { background: var(--purple-soft); color: var(--purple-text); }
.ev-type.input { background: var(--primary-soft); color: var(--primary-text); }
.ev-type.error { background: var(--red-soft); color: var(--red-text); }
.ev-type.thinking { background: var(--cyan-soft); color: var(--cyan-text); }
.ev-st { width: 14px; height: 14px; flex-shrink: 0; }
.ev-st.ok { color: var(--green); }
.ev-st.err { color: var(--red); }
.ev-st.run { color: var(--primary); }
.ev-name { width: 130px; flex-shrink: 0; font-weight: 600; }
.ev-dur { width: 64px; flex-shrink: 0; font-family: var(--mono); font-size: 11.5px; color: var(--text-3); }
.ev-prev { flex: 1; font-family: var(--mono); font-size: 11.5px; color: var(--text-2); }
.ev-time { flex-shrink: 0; font-family: var(--mono); font-size: 11px; color: var(--text-4); }
.ev-detail { padding: 2px 14px 14px 76px; }
.d-meta { font-size: 11px; color: var(--text-4); font-family: var(--mono); margin-bottom: 6px; }
.d-label { font-size: 11px; color: var(--text-3); font-weight: 600; margin: 8px 0 4px; }
.ev-detail pre { margin: 0; max-height: 360px; }
</style>
