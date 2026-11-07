<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Plus, Play, Pause, Trash2, ClipboardCheck, Loader2, Zap, CalendarClock, Hand, Repeat } from 'lucide-vue-next'
import { evalApi, RUN_STATUS, TRIGGER_LABEL } from '@/api/eval'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Empty from '@/components/ui/Empty.vue'
import EvalTaskModal from '@/components/eval/EvalTaskModal.vue'
import ScoreBadge from '@/components/eval/ScoreBadge.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce, fmtTime } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const canEdit = computed(() => auth.can('eval:edit'))
const q = ref('')
const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try { rows.value = await evalApi.list(q.value) } finally { loading.value = false }
}
onMounted(load)
watch(q, debounce(load, 300))
// 有任务在执行时自动刷新
let timer: any
watch(rows, (r) => {
  clearTimeout(timer)
  if (r.some((x) => x.running)) timer = setTimeout(load, 4000)
})
onUnmounted(() => clearTimeout(timer))

const editOpen = ref(false)
const editing = ref<any>(null)
function edit(t?: any) { editing.value = t || null; editOpen.value = true }
function onSaved(id: number) {
  if (!editing.value && id) router.push(`/evals/${id}`)
  else load()
}

async function run(t: any) {
  const r = await evalApi.run(t.id)
  toast.success(r.total ? `已开始评测 ${r.total} 条执行记录` : '没有需要评测的执行记录')
  load()
}
async function toggle(t: any) {
  await evalApi.enable(t.id, !t.enabled)
  toast.success(t.enabled ? '已暂停' : '已启用')
  load()
}
async function remove(t: any) {
  if (!(await confirm({ title: `删除评测任务「${t.name}」？`, message: '执行批次和评测结果会一并删除；人工审核表中的标注会保留。', danger: true, okText: '删除' }))) return
  await evalApi.remove(t.id)
  load()
}
const TRIGGER_ICON: Record<string, any> = { manual: Hand, schedule: CalendarClock, realtime: Zap }
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Agent 评测</h1>
        <div class="page-sub">选择模型、时间、Agent 与执行记录，由评测模型逐个会话复盘执行日志和模型输出，给出得分与回复修改意见。</div>
      </div>
      <div class="page-actions">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button v-if="canEdit" class="btn btn-primary" @click="edit()"><Plus /> 新建评测任务</button>
      </div>
    </div>
    <div class="row" style="margin-bottom: 12px"><SearchInput v-model="q" placeholder="搜索任务名称或描述" width="100%" style="flex: 1" /></div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>评测任务</th><th>Agent / 评测模型</th><th>触发方式</th><th>评测会话</th><th>平均分</th><th>通过率</th><th>最近执行</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="t in rows" :key="t.id" class="clickable" @click="router.push(`/evals/${t.id}`)">
            <td>
              <div class="cell-main">{{ t.name }}</div>
              <div class="tiny muted ellipsis" style="max-width: 260px">{{ t.description || '—' }}</div>
            </td>
            <td>
              <div class="small ellipsis" style="max-width: 200px">{{ t.agents.map((a: any) => a.name).join('、') }}</div>
              <div class="mono tiny muted">{{ t.judgeModelRef }}</div>
              <div v-if="t.replayModelRef" class="tiny muted row" style="gap: 4px"><Repeat style="width: 11px" />重放 {{ t.replayModelRef }}</div>
            </td>
            <td>
              <span class="tag tag-round" :class="t.triggerMode === 'manual' ? '' : t.enabled ? 'tag-green' : ''">
                <component :is="TRIGGER_ICON[t.triggerMode]" />{{ TRIGGER_LABEL[t.triggerMode] }}{{ t.triggerMode !== 'manual' && !t.enabled ? '（已暂停）' : '' }}
              </span>
              <div v-if="t.triggerMode === 'schedule'" class="tiny muted mono">{{ t.cron }}<template v-if="t.enabled"> · 下次 {{ fmtTime(t.nextFireAt, false) }}</template></div>
              <div v-if="t.triggerMode === 'realtime' && t.enabled" class="tiny muted">自 {{ fmtTime(t.realtimeSince, false) }} 起</div>
            </td>
            <td class="small nowrap">{{ t.sessionCount }}<span v-if="t.annotatedCount" class="tiny muted"> · 已标 {{ t.annotatedCount }}</span></td>
            <td><ScoreBadge :score="t.avgScore" /></td>
            <td class="small">{{ t.passRate == null ? '—' : t.passRate + '%' }}</td>
            <td>
              <span v-if="t.running" class="tag tag-blue tag-round"><Loader2 class="spin" />评测中</span>
              <span v-else-if="t.lastStatus" class="tag tag-round" :class="RUN_STATUS[t.lastStatus]?.tone">{{ RUN_STATUS[t.lastStatus]?.label }}</span>
              <span v-else class="muted small">—</span>
              <div v-if="t.lastRunAt" class="tiny muted">{{ fmtTime(t.lastRunAt) }}</div>
            </td>
            <td class="actions" @click.stop>
              <template v-if="canEdit">
                <button class="icon-btn" title="立即执行" :disabled="t.running" @click="run(t)"><Play /></button>
                <button v-if="t.triggerMode !== 'manual'" class="icon-btn" :title="t.enabled ? '暂停' : '启用'" @click="toggle(t)"><Pause v-if="t.enabled" /><Zap v-else /></button>
                <button class="btn btn-sm" @click="edit(t)">编辑</button>
                <button class="icon-btn danger" title="删除" @click="remove(t)"><Trash2 /></button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="ClipboardCheck" title="暂无评测任务" desc="创建评测任务，用更强的模型复盘 Agent 的执行记录并打分" />
    </div>
    <EvalTaskModal :open="editOpen" :task="editing" @close="editOpen = false" @saved="onSaved" />
  </div>
</template>

<style scoped>
.table-wrap { overflow-x: auto; }
.nowrap, .table td.tiny { white-space: nowrap; }
</style>
