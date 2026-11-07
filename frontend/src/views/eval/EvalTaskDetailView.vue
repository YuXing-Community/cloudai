<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Play, Pause, Zap, Pencil, RefreshCw, Loader2, Square, ClipboardCheck, Repeat, ExternalLink } from 'lucide-vue-next'
import { evalApi, RUN_STATUS, RESULT_STATUS, TRIGGER_LABEL, LABEL_MAP, SESSION_SOURCES, SESSION_STATUSES } from '@/api/eval'
import { useAuth } from '@/stores/auth'
import Select from '@/components/ui/Select.vue'
import SearchInput from '@/components/ui/SearchInput.vue'
import Pagination from '@/components/ui/Pagination.vue'
import Empty from '@/components/ui/Empty.vue'
import EvalTaskModal from '@/components/eval/EvalTaskModal.vue'
import ReviewDrawer from '@/components/eval/ReviewDrawer.vue'
import ScoreBadge from '@/components/eval/ScoreBadge.vue'
import { toast } from '@/components/ui/toast'
import { debounce, duration, fmtTime, shortId } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('eval:edit'))
const id = computed(() => Number(route.params.id))

const task = ref<any>(null)
const tab = ref<'results' | 'runs'>('results')

async function loadTask() {
  task.value = await evalApi.get(id.value)
}

// ---------------- 评测结果 ----------------
const results = ref<any[]>([])
const rTotal = ref(0)
const rPage = ref(1)
const q = ref('')
const scoreFilter = ref('')
const statusFilter = ref('')
const annotated = ref('')
const runFilter = ref<number | ''>('')
const SCORE_FILTERS = [
  { label: '全部分数', value: '' }, { label: '不及格', value: 'fail' }, { label: '及格', value: 'pass' },
  { label: '85 分以上', value: '85' }, { label: '60 分以下', value: 'lt60' },
]
async function loadResults() {
  const s = scoreFilter.value
  const r = await evalApi.results({
    taskId: id.value, runId: runFilter.value || undefined, q: q.value, status: statusFilter.value, annotated: annotated.value,
    passed: s === 'fail' ? false : s === 'pass' ? true : undefined, minScore: s === '85' ? 85 : undefined, maxScore: s === 'lt60' ? 59.99 : undefined,
    page: rPage.value, size: 20,
  })
  results.value = r.records
  rTotal.value = r.total
}
watch([scoreFilter, statusFilter, annotated, runFilter], () => { rPage.value = 1; loadResults() })
watch(q, debounce(() => { rPage.value = 1; loadResults() }, 300))
watch(rPage, loadResults)

// ---------------- 执行批次 ----------------
const runs = ref<any[]>([])
const runTotal = ref(0)
const runPage = ref(1)
async function loadRuns() {
  const r = await evalApi.runs(id.value, runPage.value)
  runs.value = r.records
  runTotal.value = r.total
}
watch(runPage, loadRuns)

async function loadAll() {
  await Promise.all([loadTask(), loadResults(), loadRuns()])
}
onMounted(loadAll)
watch(id, () => id.value && loadAll())

// 执行中自动刷新
let timer: any
const busy = computed(() => task.value?.running || results.value.some((r) => r.status === 'pending' || r.status === 'running'))
watch(busy, (b) => { clearInterval(timer); if (b) timer = setInterval(loadAll, 4000) })
onUnmounted(() => clearInterval(timer))

async function run() {
  const r = await evalApi.run(id.value)
  toast.success(r.total ? `已开始评测 ${r.total} 条执行记录` : '没有需要评测的执行记录（可能都已评测过）')
  runFilter.value = ''
  loadAll()
}
async function toggle() {
  await evalApi.enable(id.value, !task.value.enabled)
  toast.success(task.value.enabled ? '已暂停' : '已启用')
  loadTask()
}
async function stopRun(r: any) {
  await evalApi.stopRun(r.id)
  toast.success('正在停止，进行中的会话评测完成后结束')
}
function viewRun(r: any) {
  runFilter.value = r.id
  tab.value = 'results'
}

const editOpen = ref(false)

// ---------------- 详情抽屉 ----------------
const drawerIdx = ref(-1)
const current = computed(() => results.value[drawerIdx.value])
function open(i: number) { drawerIdx.value = i }

const filterText = computed(() => {
  const f = task.value?.filters || {}
  const parts: string[] = []
  if (f.timeMode === 'range') parts.push(`${f.from || '不限'} 至 ${f.to || '不限'}`)
  else if (f.timeMode === 'recent') parts.push(`最近 ${f.recentHours} 小时`)
  else parts.push('全部时间')
  if (f.sessionIds?.length) parts.push(`会话 ${f.sessionIds.length} 个`)
  if (f.keyword) parts.push(`内容含「${f.keyword}」`)
  if (f.sources?.length) parts.push(f.sources.map((s: string) => SESSION_SOURCES.find((x) => x.value === s)?.label || s).join('/'))
  if (f.statuses?.length) parts.push(f.statuses.map((s: string) => SESSION_STATUSES.find((x) => x.value === s)?.label || s).join('/'))
  return parts.join(' · ')
})
const runOptions = computed(() => [{ label: '全部批次', value: '' }, ...runs.value.map((r) => ({ label: `#${r.id} · ${fmtTime(r.startedAt, false)}`, value: r.id }))])
</script>

<template>
  <div class="page">
    <button class="link-btn small back" @click="router.push('/evals')"><ArrowLeft /> Agent 评测</button>
    <template v-if="task">
      <div class="page-head">
        <div style="min-width: 0">
          <div class="row" style="gap: 8px">
            <h1 class="page-title">{{ task.name }}</h1>
            <span class="tag tag-round" :class="task.triggerMode !== 'manual' && task.enabled ? 'tag-green' : ''">{{ TRIGGER_LABEL[task.triggerMode] }}执行{{ task.triggerMode !== 'manual' && !task.enabled ? ' · 已暂停' : '' }}</span>
            <span v-if="task.running" class="tag tag-blue tag-round"><Loader2 class="spin" />评测中</span>
          </div>
          <div class="page-sub">{{ task.description || '—' }}</div>
        </div>
        <div class="page-actions">
          <button class="btn" @click="loadAll"><RefreshCw /> 刷新</button>
          <template v-if="canEdit">
            <button v-if="task.triggerMode !== 'manual'" class="btn" @click="toggle"><Pause v-if="task.enabled" /><Zap v-else />{{ task.enabled ? '暂停' : '启用' }}</button>
            <button class="btn" @click="editOpen = true"><Pencil /> 编辑</button>
            <button class="btn btn-primary" :disabled="task.running" @click="run"><Play /> 立即执行</button>
          </template>
        </div>
      </div>

      <div class="stats">
        <div class="card st"><div class="st-k">评测会话</div><div class="st-v">{{ task.sessionCount }}</div></div>
        <div class="card st"><div class="st-k">平均分</div><div class="st-v"><ScoreBadge :score="task.avgScore" size="lg" /></div></div>
        <div class="card st"><div class="st-k">通过率（≥{{ task.passScore }} 分）</div><div class="st-v">{{ task.passRate == null ? '—' : task.passRate + '%' }}</div></div>
        <div class="card st"><div class="st-k">已人工标注</div><div class="st-v">{{ task.annotatedCount }}<span class="unit"> / {{ task.sessionCount }}</span></div></div>
        <div class="card st"><div class="st-k">执行批次</div><div class="st-v">{{ task.runCount }}</div></div>
      </div>

      <div class="card cfg">
        <div><span class="k">Agent</span>{{ task.agents.map((a: any) => a.name).join('、') }}</div>
        <div><span class="k">评测模型</span><span class="mono">{{ task.judgeModelRef }}</span></div>
        <div><span class="k">评测方式</span><template v-if="task.replayModelRef"><Repeat class="ico" />用 <span class="mono">{{ task.replayModelRef }}</span> 重放后评测</template><template v-else>直接评测原会话日志</template></div>
        <div><span class="k">执行记录</span>{{ filterText }}{{ task.skipEvaluated ? ' · 跳过已评测' : '' }}</div>
        <div v-if="task.triggerMode === 'schedule'"><span class="k">周期</span><span class="mono">{{ task.cron }}</span><template v-if="task.enabled"> · 下次 {{ fmtTime(task.nextFireAt) }}</template></div>
        <div v-if="task.triggerMode === 'realtime'"><span class="k">实时</span>{{ task.enabled ? `自 ${fmtTime(task.realtimeSince)} 起，新会话执行完成后自动评测` : '已暂停' }}</div>
        <div v-if="task.criteria" class="full"><span class="k">评测标准</span><span class="pre">{{ task.criteria }}</span></div>
      </div>

      <div class="tabs" style="margin: 18px 0 12px">
        <button :class="{ active: tab === 'results' }" @click="tab = 'results'">评测结果 <span class="count">{{ rTotal }}</span></button>
        <button :class="{ active: tab === 'runs' }" @click="tab = 'runs'">执行批次 <span class="count">{{ runTotal }}</span></button>
      </div>

      <template v-if="tab === 'results'">
        <div class="row" style="margin-bottom: 12px; gap: 8px; flex-wrap: wrap">
          <SearchInput v-model="q" placeholder="会话 ID 或标题" width="240px" />
          <Select v-model="scoreFilter" :options="SCORE_FILTERS" style="width: 130px" />
          <Select v-model="statusFilter" :options="[{ label: '全部状态', value: '' }, ...Object.entries(RESULT_STATUS).map(([k, v]) => ({ label: v.label, value: k }))]" style="width: 120px" />
          <Select v-model="annotated" :options="[{ label: '全部标注', value: '' }, { label: '已人工标注', value: 'yes' }, { label: '未人工标注', value: 'no' }]" style="width: 130px" />
          <Select v-model="runFilter" :options="runOptions" style="width: 190px" />
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>会话</th><th>Agent / 执行模型</th><th>模型评分</th><th>评测结论</th><th>人工</th><th>评测时间</th><th class="actions">操作</th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in results" :key="r.id" class="clickable" @click="open(i)">
                <td>
                  <div class="cell-main ellipsis" style="max-width: 240px">{{ r.sessionTitle }}</div>
                  <div class="cell-sub">{{ shortId(r.sessionId) }}<template v-if="r.replaySessionId"> → 重放 {{ shortId(r.replaySessionId) }}</template></div>
                </td>
                <td><div class="small">{{ r.agentName }} <span class="muted">v{{ r.agentVersion }}</span></div><div class="tiny muted mono">{{ r.sessionModelRef }}</div></td>
                <td>
                  <template v-if="r.status === 'success'">
                    <ScoreBadge :score="r.score" />
                    <span class="tag tag-round pass" :class="r.passed ? 'tag-green' : 'tag-red'">{{ r.passed ? '及格' : '不及格' }}</span>
                  </template>
                  <span v-else class="tag tag-round" :class="RESULT_STATUS[r.status]?.tone"><Loader2 v-if="r.status === 'running'" class="spin" />{{ RESULT_STATUS[r.status]?.label }}</span>
                </td>
                <td class="small"><div class="clamp-2" style="max-width: 380px">{{ r.summary || r.error || '—' }}</div><div v-if="r.issueCount" class="tiny muted">{{ r.issueCount }} 个问题</div></td>
                <td>
                  <template v-if="r.annotationCount">
                    <ScoreBadge :score="r.humanScore" size="sm" />
                    <span v-if="r.humanLabel" class="tag" :class="LABEL_MAP[r.humanLabel]?.tone" style="margin-left: 4px">{{ LABEL_MAP[r.humanLabel]?.label }}</span>
                  </template>
                  <span v-else class="muted small" style="white-space: nowrap">未标注</span>
                </td>
                <td class="tiny muted">{{ fmtTime(r.finishedAt || r.createdAt) }}<div v-if="r.durationMs">{{ duration(r.durationMs) }}</div></td>
                <td class="actions" @click.stop>
                  <button class="btn btn-sm" @click="open(i)">{{ auth.can('eval:annotate') && r.status === 'success' ? '详情 / 打标' : '详情' }}</button>
                  <button class="icon-btn" title="打开会话" @click="router.push(`/sessions/${r.sessionId}`)"><ExternalLink /></button>
                </td>
              </tr>
            </tbody>
          </table>
          <Empty v-if="!results.length" :icon="ClipboardCheck" title="暂无评测结果" :desc="canEdit ? '点击右上角「立即执行」开始评测' : ''" />
        </div>
        <Pagination v-if="rTotal > 20" v-model:page="rPage" :size="20" :total="rTotal" />
      </template>

      <template v-else>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>批次</th><th>触发</th><th>状态</th><th>进度</th><th>平均分</th><th>及格</th><th>开始 / 耗时</th><th class="actions">操作</th></tr></thead>
            <tbody>
              <tr v-for="r in runs" :key="r.id">
                <td class="mono small">#{{ r.id }}</td>
                <td><span class="tag">{{ TRIGGER_LABEL[r.triggerType] }}</span></td>
                <td><span class="tag tag-round" :class="RUN_STATUS[r.status]?.tone"><Loader2 v-if="r.status === 'running'" class="spin" />{{ RUN_STATUS[r.status]?.label }}</span></td>
                <td class="small">{{ r.done + r.failed }} / {{ r.total }}<span v-if="r.failed" class="danger-text"> · 失败 {{ r.failed }}</span></td>
                <td><ScoreBadge :score="r.avgScore" size="sm" /></td>
                <td class="small">{{ r.done ? `${r.passCount} / ${r.done}` : '—' }}</td>
                <td class="tiny muted">{{ fmtTime(r.startedAt) }}<div v-if="r.finishedAt">{{ duration(new Date(r.finishedAt.replace(' ', 'T')).getTime() - new Date(r.startedAt.replace(' ', 'T')).getTime()) }}</div></td>
                <td class="actions">
                  <button v-if="r.status === 'running' && canEdit" class="btn btn-sm" @click="stopRun(r)"><Square /> 停止</button>
                  <button class="btn btn-sm" @click="viewRun(r)">查看结果</button>
                </td>
              </tr>
            </tbody>
          </table>
          <Empty v-if="!runs.length" title="暂无执行批次" compact />
        </div>
        <Pagination v-if="runTotal > 20" v-model:page="runPage" :size="20" :total="runTotal" />
      </template>
    </template>

    <EvalTaskModal :open="editOpen" :task="task" @close="editOpen = false" @saved="loadAll" />
    <ReviewDrawer :open="!!current" mode="eval" :eval-result-id="current?.id" :has-next="drawerIdx < results.length - 1"
                  @close="drawerIdx = -1" @saved="loadResults(); loadTask()" @next="drawerIdx++" />
  </div>
</template>

<style scoped>
.table-wrap { overflow-x: auto; }
.table td.tiny { white-space: nowrap; }
.back { display: inline-flex; align-items: center; gap: 4px; margin-bottom: 10px; color: var(--text-3); }
.back svg { width: 14px; height: 14px; }
.stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 12px; }
.st { padding: 14px 16px; }
.st-k { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.st-v { font-size: 24px; font-weight: 700; min-height: 40px; display: flex; align-items: center; }
.st-v .unit { font-size: 13px; color: var(--text-3); font-weight: 500; margin-left: 2px; }
.cfg { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; padding: 14px 16px; font-size: 13px; }
.cfg .full { grid-column: 1 / -1; display: flex; }
.cfg .k { display: inline-block; width: 72px; color: var(--text-3); flex-shrink: 0; }
.cfg .pre { white-space: pre-wrap; line-height: 1.6; }
.cfg .ico { width: 13px; height: 13px; vertical-align: -2px; margin-right: 3px; color: var(--text-3); }
.pass { margin-left: 6px; }
</style>
