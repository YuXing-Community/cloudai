<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronRight, CheckCircle2, XCircle, Loader2, Terminal, FileText, FilePen, FolderTree, Search, Package, Sparkles, Database, Bot, Plug } from 'lucide-vue-next'
import type { SessionEvent } from '@/api/session'
import { duration } from '@/utils/format'

const props = defineProps<{ ev: SessionEvent; defaultOpen?: boolean }>()
const open = ref(!!props.defaultOpen)

const args = computed(() => {
  try { return JSON.parse(props.ev.content || '{}') } catch { return props.ev.content }
})
const icon = computed(() => {
  const n = props.ev.name || ''
  if (n === 'Bash') return Terminal
  if (n === 'Read') return FileText
  if (n === 'Write' || n === 'Edit') return FilePen
  if (n === 'LS' || n === 'Glob') return FolderTree
  if (n === 'Grep') return Search
  if (n === 'artifact_save') return Package
  if (n === 'Skill') return Sparkles
  if (n.startsWith('memory') || n === 'remember') return Database
  if (n === 'delegate_to_agent') return Bot
  return Plug
})
const summary = computed(() => {
  const a: any = args.value
  if (!a || typeof a !== 'object') return String(a || '')
  return a.command || a.description || a.file_path || a.path || a.pattern || a.query || a.name || a.task || a.artifact_name ||
    Object.entries(a).map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`).join(' ')
})
const pretty = computed(() => (typeof args.value === 'string' ? args.value : JSON.stringify(args.value, null, 2)))
</script>

<template>
  <div class="tool" :class="[ev.status, { open }]">
    <button class="tool-head" @click="open = !open">
      <ChevronRight class="chev" />
      <component :is="icon" class="t-ico" />
      <span class="t-name">{{ ev.name }}</span>
      <span class="t-sum ellipsis">{{ summary }}</span>
      <span class="t-dur">{{ ev.status === 'running' ? '执行中' : duration(ev.durationMs) }}</span>
      <Loader2 v-if="ev.status === 'running'" class="st spin running" />
      <XCircle v-else-if="ev.status === 'error'" class="st err" />
      <CheckCircle2 v-else class="st ok" />
    </button>
    <div v-if="open" class="tool-body">
      <div class="tb-label">参数</div>
      <pre class="code-soft">{{ pretty }}</pre>
      <template v-if="ev.result">
        <div class="tb-label">结果</div>
        <pre class="code-soft result">{{ ev.result }}</pre>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tool { border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elev); overflow: hidden; }
.tool.error { border-color: color-mix(in srgb, var(--red) 35%, var(--border)); }
.tool-head { width: 100%; display: flex; align-items: center; gap: 8px; padding: 7px 10px; border: none; background: transparent; cursor: pointer; text-align: left; font-size: 12.5px; }
.tool-head:hover { background: var(--bg-hover); }
.chev { width: 13px; height: 13px; color: var(--text-4); transition: transform .15s; flex-shrink: 0; }
.open .chev { transform: rotate(90deg); }
.t-ico { width: 14px; height: 14px; color: var(--text-3); flex-shrink: 0; }
.t-name { font-family: var(--mono); font-weight: 600; color: var(--text); flex-shrink: 0; }
.t-sum { flex: 1; color: var(--text-3); font-family: var(--mono); font-size: 12px; }
.t-dur { font-size: 11.5px; color: var(--text-4); font-family: var(--mono); flex-shrink: 0; }
.st { width: 14px; height: 14px; flex-shrink: 0; }
.st.ok { color: var(--green); }
.st.err { color: var(--red); }
.st.running { color: var(--primary); }
.tool-body { padding: 4px 12px 12px; border-top: 1px solid var(--divider); }
.tb-label { font-size: 11px; color: var(--text-3); margin: 8px 0 4px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; }
pre { margin: 0; max-height: 320px; }
.result { max-height: 420px; }
</style>
