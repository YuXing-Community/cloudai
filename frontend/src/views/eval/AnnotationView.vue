<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw, Download, Tags, ClipboardCheck, MessagesSquare, TableProperties } from 'lucide-vue-next'
import { evalApi, annotationApi, LABELS, LABEL_MAP } from '@/api/eval'
import { optionApi } from '@/api/session'
import Select from '@/components/ui/Select.vue'
import SearchInput from '@/components/ui/SearchInput.vue'
import Pagination from '@/components/ui/Pagination.vue'
import Empty from '@/components/ui/Empty.vue'
import ReviewDrawer from '@/components/eval/ReviewDrawer.vue'
import ScoreBadge from '@/components/eval/ScoreBadge.vue'
import { debounce, fmtTime, shortId } from '@/utils/format'

const route = useRoute()
const router = useRouter()
type Tab = 'eval' | 'session' | 'table'
const tab = ref<Tab>((['eval', 'session', 'table'].includes(route.query.tab as string) ? route.query.tab : 'eval') as Tab)
watch(tab, (t) => router.replace({ query: { tab: t } }))

const agents = ref<any[]>([])
const tasks = ref<any[]>([])
const stats = ref<any>({})

// 共用筛选
const q = ref('')
const agentId = ref('')
const taskId = ref<number | ''>('')
const annotated = ref('no')
const scoreFilter = ref('')
const from = ref('')
const to = ref('')
const label = ref('')
const modeFilter = ref('')
const page = ref(1)
const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

const SCORE_FILTERS = [
  { label: '全部模型分', value: '' }, { label: '模型判不及格', value: 'fail' }, { label: '模型判及格', value: 'pass' }, { label: '60 分以下', value: 'lt60' }, { label: '85 分以上', value: '85' },
]
const agentOptions = computed(() => [{ label: '全部 Agent', value: '' }, ...agents.value.map((a) => ({ label: a.name, value: a.id }))])
const taskOptions = computed(() => [{ label: '全部评测任务', value: '' }, ...tasks.value.map((t) => ({ label: t.name, value: t.id }))])
const dt = (v: string) => (v ? v.replace('T', ' ') : undefined)

async function load() {
  loading.value = true
  try {
    if (tab.value === 'table') {
      const r = await annotationApi.list({ mode: modeFilter.value, agentId: agentId.value, label: label.value, taskId: taskId.value || undefined, q: q.value, page: page.value, size: 20 })
      rows.value = r.records
      total.value = r.total
    } else {
      const s = scoreFilter.value
      const r = await annotationApi.queue({
        mode: tab.value, taskId: tab.value === 'eval' ? taskId.value || undefined : undefined, agentId: agentId.value, annotated: annotated.value, q: q.value,
        from: dt(from.value), to: dt(to.value), page: page.value, size: 20,
        ...(tab.value === 'eval' ? { passed: s === 'fail' ? false : s === 'pass' ? true : undefined, minScore: s === '85' ? 85 : undefined, maxScore: s === 'lt60' ? 59.99 : undefined } : {}),
      })
      rows.value = r.records
      total.value = r.total
    }
  } finally {
    loading.value = false
  }
  stats.value = await annotationApi.stats()
}
onMounted(async () => {
  const [a, t] = await Promise.all([optionApi.agents(), evalApi.list().catch(() => [])])
  agents.value = a
  tasks.value = t
  load()
})
watch(tab, () => { page.value = 1; rows.value = []; load() })
watch([agentId, taskId, annotated, scoreFilter, from, to, label, modeFilter], () => { page.value = 1; load() })
watch(q, debounce(() => { page.value = 1; load() }, 300))
watch(page, load)

// ---------------- 抽屉 ----------------
const idx = ref(-1)
const current = computed(() => {
  const r = rows.value[idx.value]
  if (!r) return null
  return { mode: r.mode as 'eval' | 'session', evalResultId: r.evalResultId || null, sessionId: r.sessionId }
})
const exportUrl = computed(() => annotationApi.exportUrl({ mode: modeFilter.value, agentId: agentId.value, label: label.value, taskId: taskId.value || undefined, q: q.value }))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">人工打标</h1>
        <div class="page-sub">对模型会话进行人工打标、打分与意见反馈，形成人工审核表；可基于模型评测结果复核，也可直接基于会话内容打标。</div>
      </div>
      <div class="page-actions">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <a class="btn" :href="exportUrl" download><Download /> 导出审核表</a>
      </div>
    </div>

    <div class="stats">
      <div class="card st"><div class="st-k">已标注</div><div class="st-v">{{ stats.total ?? 0 }}</div></div>
      <div v-for="l in LABELS" :key="l.value" class="card st"><div class="st-k">{{ l.label }}</div><div class="st-v"><span class="dot" :class="l.value" />{{ stats.labels?.[l.value] ?? 0 }}</div></div>
      <div class="card st"><div class="st-k">人工平均分</div><div class="st-v"><ScoreBadge :score="stats.avgScore" size="lg" /></div></div>
      <div class="card st" title="认可模型结论，或人工分与模型分相差 ≤15 分视为一致"><div class="st-k">与模型评测一致率</div><div class="st-v">{{ stats.agreementRate == null ? '—' : stats.agreementRate + '%' }}<span v-if="stats.comparedCount" class="unit">（{{ stats.comparedCount }} 条）</span></div></div>
    </div>

    <div class="segmented blue mode-tabs">
      <button :class="{ active: tab === 'eval' }" @click="tab = 'eval'"><ClipboardCheck /> 模式一：基于模型评测打标</button>
      <button :class="{ active: tab === 'session' }" @click="tab = 'session'"><MessagesSquare /> 模式二：直接基于会话打标</button>
      <button :class="{ active: tab === 'table' }" @click="tab = 'table'"><TableProperties /> 人工审核表</button>
    </div>

    <div class="row filters">
      <SearchInput v-model="q" :placeholder="tab === 'session' ? '会话 ID / 标题 / 内容' : tab === 'table' ? '会话 ID 或意见' : '会话 ID 或标题'" width="220px" />
      <Select v-model="agentId" :options="agentOptions" style="width: 160px" />
      <Select v-if="tab !== 'session'" v-model="taskId" :options="taskOptions" style="width: 200px" />
      <Select v-if="tab === 'eval'" v-model="scoreFilter" :options="SCORE_FILTERS" style="width: 140px" />
      <template v-if="tab !== 'table'">
        <Select v-model="annotated" :options="[{ label: '待标注', value: 'no' }, { label: '已标注', value: 'yes' }, { label: '全部', value: 'all' }]" style="width: 110px" />
        <input v-model="from" type="datetime-local" class="input dt" title="会话时间起" />
        <span class="muted small">至</span>
        <input v-model="to" type="datetime-local" class="input dt" title="会话时间止" />
      </template>
      <template v-else>
        <Select v-model="modeFilter" :options="[{ label: '全部模式', value: '' }, { label: '基于模型评测', value: 'eval' }, { label: '直接基于会话', value: 'session' }]" style="width: 140px" />
        <Select v-model="label" :options="[{ label: '全部结论', value: '' }, ...LABELS.map((l) => ({ label: l.label, value: l.value }))]" style="width: 120px" />
      </template>
    </div>

    <!-- 模式一：基于模型评测 -->
    <div v-if="tab === 'eval'" class="table-wrap">
      <table class="table">
        <thead><tr><th>会话</th><th>Agent / 评测任务</th><th>模型评分</th><th>模型评测结论</th><th>我的标注</th><th>会话时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.evalResultId" class="clickable" @click="idx = i">
            <td><div class="cell-main ellipsis" style="max-width: 220px">{{ r.title }}</div><div class="cell-sub">{{ shortId(r.sessionId) }}</div></td>
            <td><div class="small">{{ r.agentName }}</div><div class="tiny muted ellipsis" style="max-width: 180px">{{ r.taskName }}</div></td>
            <td><ScoreBadge :score="r.modelScore" /> <span class="tag tag-round" :class="r.passed ? 'tag-green' : 'tag-red'">{{ r.passed ? '及格' : '不及格' }}</span></td>
            <td class="small"><div class="clamp-2" style="max-width: 360px">{{ r.summary }}</div></td>
            <td>
              <template v-if="r.myAnnotation"><ScoreBadge :score="r.myAnnotation.score" size="sm" /> <span v-if="r.myAnnotation.label" class="tag" :class="LABEL_MAP[r.myAnnotation.label]?.tone">{{ LABEL_MAP[r.myAnnotation.label]?.label }}</span></template>
              <span v-else class="muted small nowrap">待标注</span>
              <div v-if="r.annotationCount > (r.myAnnotation ? 1 : 0)" class="tiny muted">共 {{ r.annotationCount }} 人标注</div>
            </td>
            <td class="tiny muted">{{ fmtTime(r.time) }}</td>
            <td class="actions" @click.stop><button class="btn btn-sm" :class="{ 'btn-primary': !r.myAnnotation }" @click="idx = i">{{ r.myAnnotation ? '查看 / 修改' : '打标' }}</button></td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="Tags" :title="annotated === 'no' ? '没有待标注的评测结果' : '暂无评测结果'" desc="先在「Agent 评测」中执行评测任务，再对评测结果进行人工复核" />
    </div>

    <!-- 模式二：直接基于会话 -->
    <div v-if="tab === 'session'" class="table-wrap">
      <table class="table">
        <thead><tr><th>会话</th><th>用户输入 / Agent 回复</th><th>Agent / 执行模型</th><th>我的标注</th><th>会话时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.sessionId" class="clickable" @click="idx = i">
            <td><div class="cell-main ellipsis" style="max-width: 220px">{{ r.title }}</div><div class="cell-sub">{{ shortId(r.sessionId) }}</div></td>
            <td class="small" style="max-width: 300px"><div class="ellipsis"><span class="muted">问：</span>{{ r.input || '—' }}</div><div class="ellipsis muted"><span>答：</span>{{ r.reply || '—' }}</div></td>
            <td><div class="small">{{ r.agentName }}</div><div class="tiny muted mono">{{ r.modelRef }}</div></td>
            <td>
              <template v-if="r.myAnnotation"><ScoreBadge :score="r.myAnnotation.score" size="sm" /> <span v-if="r.myAnnotation.label" class="tag" :class="LABEL_MAP[r.myAnnotation.label]?.tone">{{ LABEL_MAP[r.myAnnotation.label]?.label }}</span></template>
              <span v-else class="muted small nowrap">待标注</span>
              <div v-if="r.annotationCount > (r.myAnnotation ? 1 : 0)" class="tiny muted">共 {{ r.annotationCount }} 人标注</div>
            </td>
            <td class="tiny muted">{{ fmtTime(r.time) }}</td>
            <td class="actions" @click.stop><button class="btn btn-sm" :class="{ 'btn-primary': !r.myAnnotation }" @click="idx = i">{{ r.myAnnotation ? '查看 / 修改' : '打标' }}</button></td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="MessagesSquare" :title="annotated === 'no' ? '没有待标注的会话' : '暂无会话'" desc="只包含已结束的会话" />
    </div>

    <!-- 人工审核表 -->
    <div v-if="tab === 'table'" class="table-wrap">
      <table class="table">
        <thead><tr><th>会话</th><th>模式</th><th>Agent</th><th>模型分</th><th>人工分</th><th>结论</th><th>问题标签</th><th>人工意见</th><th>标注人</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.id" class="clickable" @click="idx = i">
            <td><div class="cell-main ellipsis" style="max-width: 180px">{{ r.title }}</div><div class="cell-sub">{{ shortId(r.sessionId) }}</div></td>
            <td><span class="tag" :class="r.mode === 'eval' ? 'tag-purple' : 'tag-cyan'">{{ r.mode === 'eval' ? '模型评测' : '会话' }}</span></td>
            <td class="small">{{ r.agentName }}</td>
            <td><ScoreBadge v-if="r.mode === 'eval'" :score="r.modelScore" size="sm" /><span v-else class="muted">—</span></td>
            <td><ScoreBadge :score="r.score" size="sm" /></td>
            <td>
              <span v-if="r.label" class="tag" :class="LABEL_MAP[r.label]?.tone">{{ LABEL_MAP[r.label]?.label }}</span>
              <div v-if="r.agreeWithModel != null" class="tiny muted">{{ r.agreeWithModel ? '认可模型' : '不认可模型' }}</div>
            </td>
            <td><div class="row" style="gap: 4px; flex-wrap: wrap; max-width: 200px"><span v-for="t in r.tags" :key="t" class="tag">{{ t }}</span></div></td>
            <td class="small"><div class="clamp-2" style="max-width: 260px">{{ r.comment || '—' }}</div></td>
            <td class="small">{{ r.annotator?.displayName || r.annotator?.username }}</td>
            <td class="tiny muted">{{ fmtTime(r.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="TableProperties" title="人工审核表为空" desc="在「模式一」或「模式二」中完成打标后，记录会汇总到这里" />
    </div>
    <Pagination v-if="total > 20" v-model:page="page" :size="20" :total="total" />

    <ReviewDrawer :open="!!current" :mode="current?.mode || 'eval'" :eval-result-id="current?.evalResultId" :session-id="current?.sessionId"
                  :has-next="tab !== 'table' && idx < rows.length - 1" @close="idx = -1" @saved="load" @next="idx++" />
  </div>
</template>

<style scoped>
.table-wrap { overflow-x: auto; }
.table td.tiny, .nowrap { white-space: nowrap; }
.stats { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 16px; }
.st { padding: 14px 16px; }
.st-k { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.st-v { font-size: 24px; font-weight: 700; min-height: 40px; display: flex; align-items: center; gap: 8px; }
.st-v .unit { font-size: 12px; color: var(--text-3); font-weight: 500; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.good { background: var(--green); }
.dot.acceptable { background: var(--primary); }
.dot.bad { background: var(--red); }
.mode-tabs { margin-bottom: 12px; }
.mode-tabs button { height: 32px; padding: 0 14px; }
.filters { gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.input.dt { width: 190px; }
</style>
