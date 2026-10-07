<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  LayoutGrid, Archive, XCircle, RefreshCw, Activity, Loader, AlertCircle, RotateCcw, CheckCircle2, Circle, Clock, ZoomIn, ZoomOut, Maximize2,
  Users, GitBranch, ChevronDown, ExternalLink, FileX, Send,
} from 'lucide-vue-next'
import { teamApi, TS_STATUS, TASK_COLUMNS, PATTERN } from '@/api/team'
import { useAuth } from '@/stores/auth'
import { authedUrl } from '@/api/http'
import Empty from '@/components/ui/Empty.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import { confirm, toast } from '@/components/ui/toast'
import { bytes, duration, fmtClock, fmtTime } from '@/utils/format'

const route = useRoute()
const auth = useAuth()
const canEdit = computed(() => auth.can('team:edit'))
const router = useRouter()
const id = computed(() => route.params.id as string)
const d = ref<any>(null)
const tab = ref('board')
const colFilter = ref<'all' | 'running' | 'attention'>('all')
const runView = ref<'lane' | 'timeline'>('lane')
const zoom = ref(1)
const openRuns = ref<Set<number>>(new Set())
const answers = ref<Record<number, string>>({})

async function load() {
  d.value = await teamApi.detail(id.value)
}
let timer: any
onMounted(() => {
  load()
  timer = setInterval(() => { if (d.value && ['running', 'waiting_input'].includes(d.value.session.status)) load() }, 4000)
})
onBeforeUnmount(() => clearInterval(timer))

const s = computed(() => d.value?.session)
const tasks = computed<any[]>(() => d.value?.tasks || [])
const roles = computed<any[]>(() => d.value?.team?.roles || [])
const tabs = computed(() => [
  { k: 'board', l: '任务看板', n: tasks.value.length },
  { k: 'tree', l: '任务树' },
  { k: 'runs', l: 'Agent Runs', n: d.value?.runs?.length || 0 },
  { k: 'inputs', l: '用户输入', n: d.value?.inputs?.length || 0 },
  { k: 'artifacts', l: '产物' },
  { k: 'messages', l: '消息', n: d.value?.messages?.length || 0 },
  { k: 'events', l: '事件', n: d.value?.events?.length || 0 },
  { k: 'state', l: 'State' },
])

// ---- 看板 ----
const stats = computed(() => {
  const t = tasks.value
  const done = t.filter((x) => x.status === 'done').length
  return {
    progress: t.length ? Math.round((done / t.length) * 100) : 0,
    running: t.filter((x) => x.status === 'running').length,
    attention: t.filter((x) => ['waiting_input', 'waiting_approval', 'failed'].includes(x.status)).length,
    revision: t.filter((x) => x.status === 'needs_revision').length,
    done,
  }
})
const columns = computed(() => TASK_COLUMNS.filter((c) =>
  colFilter.value === 'all' || (colFilter.value === 'running' ? c.k === 'running' : ['waiting_input', 'waiting_approval', 'failed', 'needs_revision'].includes(c.k))))
const roleLabel = (r: string) => roles.value.find((x) => x.role === r)?.label || r
const roleAgent = (r: string) => roles.value.find((x) => x.role === r)?.agentName || ''
const runningAgents = computed(() => new Set((d.value?.runs || []).filter((r: any) => r.status === 'running').map((r: any) => r.role)).size)

// ---- 任务树：按 parent 分层布局 ----
const layout = computed(() => {
  const byParent: Record<string, any[]> = {}
  for (const t of tasks.value) (byParent[t.parentId || '_'] ||= []).push(t)
  const nodes: any[] = []
  const W = 300, H = 150
  function walk(pid: string, depth: number, x0: number): number {
    const kids = byParent[pid] || []
    let x = x0
    for (const k of kids) {
      const width = Math.max(1, walk(k.id, depth + 1, x))
      nodes.push({ t: k, x: x + (width - 1) * W / 2, y: depth * H })
      x += width * W
    }
    return Math.max(1, (x - x0) / W)
  }
  walk('_', 0, 0)
  const pos = Object.fromEntries(nodes.map((n) => [n.t.id, n]))
  const edges = nodes.filter((n) => n.t.parentId && pos[n.t.parentId]).map((n) => ({ from: pos[n.t.parentId], to: n }))
  const w = Math.max(...nodes.map((n) => n.x + W), W), h = Math.max(...nodes.map((n) => n.y + H), H)
  return { nodes, edges, w, h }
})
const roleTasks = (r: string | null) => tasks.value.filter((t) => (t.role || null) === r).length
function elapsed(t: any) {
  const a = new Date(t.createdAt?.replace(' ', 'T')).getTime()
  const b = new Date((t.updatedAt || t.createdAt)?.replace(' ', 'T')).getTime()
  return duration(Math.max(1000, (['running', 'waiting_input'].includes(t.status) ? Date.now() : b) - a))
}

// ---- Agent Runs ----
const lanes = computed(() => {
  const m: Record<string, any[]> = {}
  for (const r of d.value?.runs || []) (m[r.role] ||= []).push(r)
  return Object.entries(m)
})
function toggleRun(rid: number) {
  const x = new Set(openRuns.value)
  x.has(rid) ? x.delete(rid) : x.add(rid)
  openRuns.value = x
}
const taskTitle = (tid: string) => tasks.value.find((t) => t.id === tid)?.title || tid

async function answer(m: any) {
  const a = (answers.value[m.id] || '').trim()
  if (!a) return toast.warning('请填写回复')
  await teamApi.answer(id.value, m.id, a)
  toast.success('已回复，router 继续执行')
  load()
}
async function terminate() {
  if (!(await confirm({ title: '终止本次任务？', message: '正在执行的 Agent 会被停止。', danger: true, okText: '终止' }))) return
  await teamApi.terminate(id.value)
  load()
}
async function archive() {
  await teamApi.archive(id.value)
  toast.success('已归档')
  router.push('/taskboard')
}
const statusIcon = (st: string) => ({ done: CheckCircle2, failed: XCircle, running: Loader, waiting_input: Clock }[st] || Circle)
</script>

<template>
  <div v-if="d" class="page tb">
    <div class="tb-head">
      <div class="tb-ico"><LayoutGrid /></div>
      <div style="flex: 1; min-width: 0">
        <div class="row small" style="gap: 6px"><span class="bold">Agent Team 看板</span><span class="tag tag-blue tag-round">{{ PATTERN[d.team?.pattern] || '专家池' }}</span><span class="tag tag-round" :class="TS_STATUS[s.status]?.t">{{ TS_STATUS[s.status]?.l }}</span></div>
        <h1 class="tb-name">{{ s.name }}</h1>
        <div class="tiny muted mono">Session {{ s.id }}</div>
      </div>
      <button v-if="canEdit" class="btn btn-ghost btn-sm" @click="archive"><Archive /> 归档</button>
      <button v-if="canEdit && ['running', 'waiting_input'].includes(s.status)" class="btn btn-ghost btn-sm danger-text" @click="terminate"><XCircle /> 终止</button>
      <button class="icon-btn" @click="load"><RefreshCw /></button>
    </div>
    <div class="tabs">
      <button v-for="t in tabs" :key="t.k" :class="{ active: tab === t.k }" @click="tab = t.k">{{ t.l }} <span v-if="t.n !== undefined" class="count">{{ t.n }}</span></button>
    </div>

    <!-- 任务看板 -->
    <div v-if="tab === 'board'" class="pane">
      <div class="row" style="justify-content: space-between; margin-bottom: 10px">
        <span class="bold small">任务看板 <span class="kbd">{{ tasks.length }}</span></span>
        <div class="row">
          <div class="segmented">
            <button :class="{ active: colFilter === 'all' }" @click="colFilter = 'all'">全部</button>
            <button :class="{ active: colFilter === 'running' }" @click="colFilter = 'running'">执行中</button>
            <button :class="{ active: colFilter === 'attention' }" @click="colFilter = 'attention'">需处理</button>
          </div>
          <button class="icon-btn" @click="load"><RefreshCw /></button>
        </div>
      </div>
      <div class="kpis">
        <div class="kpi blue"><div class="k-h">整体进度 <Activity /></div><div class="k-v">{{ stats.progress }}%</div><div class="pbar"><i :style="{ width: stats.progress + '%' }" /></div></div>
        <div class="kpi"><div class="k-h">正在执行 <Loader /></div><div class="k-v">{{ stats.running }}</div><div class="k-s">{{ runningAgents }} 个 Agent 活跃</div></div>
        <div class="kpi warn"><div class="k-h">需要处理 <AlertCircle /></div><div class="k-v">{{ stats.attention }}</div><div class="k-s">等待输入、审批、失败或阻塞</div></div>
        <div class="kpi warn2"><div class="k-h">需修订 <RotateCcw /></div><div class="k-v">{{ stats.revision }}</div><div class="k-s">语义失败需要跟进</div></div>
        <div class="kpi"><div class="k-h">已完成 <CheckCircle2 class="ok" /></div><div class="k-v">{{ stats.done }}</div><div class="k-s">{{ stats.done ? '' : '暂无完成任务' }}</div></div>
      </div>
      <div class="cols">
        <div v-for="c in columns" :key="c.k" class="col">
          <div class="col-h"><span class="status-dot" :style="{ background: c.c }" /><span class="bold small">{{ c.l }}</span><span class="spacer" /><span class="tiny muted">{{ tasks.filter((t) => t.status === c.k).length }}</span></div>
          <div v-for="t in tasks.filter((x) => x.status === c.k)" :key="t.id" class="tcard" :style="{ borderLeftColor: c.c }">
            <div class="row tiny muted" style="gap: 5px"><component :is="statusIcon(t.status)" class="tc-ico" :class="{ spin: t.status === 'running' }" /> TASK</div>
            <div class="bold" style="margin: 4px 0 6px">{{ t.title }}</div>
            <div class="row tiny" style="gap: 6px"><span class="tag tag-blue">{{ t.role ? roleLabel(t.role) : '未分' }}</span><span class="muted">{{ t.role ? roleAgent(t.role) : '未分配' }}</span></div>
            <div class="row tiny muted" style="justify-content: space-between; margin-top: 8px"><span>{{ c.l }}</span><span>{{ fmtClock(t.createdAt) }}</span></div>
          </div>
          <div v-if="!tasks.filter((x) => x.status === c.k).length" class="empty-box small">暂无任务</div>
        </div>
      </div>
    </div>

    <!-- 任务树 -->
    <div v-else-if="tab === 'tree'" class="tree-pane">
      <aside class="roles-side">
        <div class="row bold small" style="gap: 6px; padding: 12px"><Users style="width: 14px" /> 角色与 Agent</div>
        <div class="ra">
          <span class="ra-tag">未分</span><div style="flex: 1"><div class="bold small">未分配</div><div class="tiny muted">尚未运行</div><div class="tiny muted">{{ roleTasks(null) }} 个任务</div></div>
        </div>
        <div v-for="r in roles" :key="r.role" class="ra">
          <span class="ra-tag">{{ r.role.slice(0, 2).toUpperCase() }}</span>
          <div style="flex: 1"><div class="bold small">{{ r.role }}</div><div class="tiny muted">{{ r.agentName }}</div><div class="tiny muted">{{ roleTasks(r.role) }} 个任务</div></div>
          <span class="tiny muted">{{ (d.runs || []).some((x: any) => x.role === r.role && x.status === 'running') ? '运行中' : '空闲' }}</span>
        </div>
      </aside>
      <div class="graph">
        <div class="g-bar">
          <GitBranch style="width: 14px" /><span class="bold small">任务依赖图</span><span class="kbd">{{ tasks.length }} 个任务</span>
          <span class="spacer" /><span class="small muted">{{ Math.round(zoom * 100) }}%</span>
          <button class="icon-btn sm bordered" @click="zoom = Math.max(0.4, zoom - 0.1)"><ZoomOut /></button>
          <button class="icon-btn sm bordered" @click="zoom = Math.min(2, zoom + 0.1)"><ZoomIn /></button>
          <button class="btn btn-sm" @click="zoom = 1"><Maximize2 /> 适应画布</button>
        </div>
        <div class="canvas">
          <div class="stage" :style="{ width: layout.w + 'px', height: layout.h + 'px', transform: `scale(${zoom})` }">
            <svg :width="layout.w" :height="layout.h" class="edges">
              <path v-for="(e, i) in layout.edges" :key="i" :d="`M ${e.from.x + 130} ${e.from.y + 92} C ${e.from.x + 130} ${e.from.y + 125}, ${e.to.x + 130} ${e.to.y - 25}, ${e.to.x + 130} ${e.to.y + 8}`" />
            </svg>
            <div v-for="n in layout.nodes" :key="n.t.id" class="gnode" :class="n.t.status" :style="{ left: n.x + 'px', top: n.y + 8 + 'px' }">
              <div class="row" style="gap: 6px">
                <component :is="statusIcon(n.t.status)" class="tc-ico" /><span class="bold small ellipsis" style="flex: 1">{{ n.t.title }}</span>
                <span class="tag tag-round" :class="TASK_COLUMNS.find((c) => c.k === n.t.status) ? '' : ''">{{ TASK_COLUMNS.find((c) => c.k === n.t.status)?.l }}</span>
              </div>
              <div class="tiny muted clamp-2" style="margin: 6px 0">{{ n.t.description }}</div>
              <div class="row tiny" style="justify-content: space-between">
                <span><span class="tag tag-mono">{{ n.t.role || '未分配' }}</span> <span v-if="n.t.role" class="tag tag-blue">{{ roleAgent(n.t.role) }}</span></span>
                <span class="muted"><Clock style="width: 11px; vertical-align: -1px" /> {{ elapsed(n.t) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Agent Runs -->
    <div v-else-if="tab === 'runs'" class="pane">
      <div class="segmented" style="margin-bottom: 12px">
        <button :class="{ active: runView === 'lane' }" @click="runView = 'lane'">泳道 <span class="count">{{ lanes.length }} roles</span></button>
        <button :class="{ active: runView === 'timeline' }" @click="runView = 'timeline'">时间线 <span class="count">{{ d.runs.length }}</span></button>
      </div>
      <template v-if="runView === 'lane'">
        <div v-for="[role, rs] in lanes" :key="role" class="lane">
          <div class="lane-h"><ChevronDown style="width: 14px" /><span class="bold small">{{ role }}</span><span class="spacer" /><span class="tag tag-blue">{{ rs.length }} runs</span><span class="tag tag-green">{{ rs.filter((r: any) => r.status === 'done').length }} done</span></div>
          <div v-for="r in rs" :key="r.id" class="run">
            <div class="run-h" @click="toggleRun(r.id)">
              <ChevronDown class="chev" :class="{ rot: !openRuns.has(r.id) }" />
              <span class="small">{{ taskTitle(r.taskId) }}</span>
              <span class="spacer" />
              <span class="tag tag-blue">{{ roleAgent(role) }}</span>
              <button v-if="r.childSessionId" class="link-btn tiny" @click.stop="router.push(`/sessions/${r.childSessionId}`)"><ExternalLink style="width: 11px" /> 会话</button>
              <span class="tiny muted mono">{{ duration(r.durationMs) }}</span>
              <span class="tag" :class="r.status === 'done' ? 'tag-green' : r.status === 'failed' ? 'tag-red' : 'tag-blue'">{{ r.status }}</span>
              <span class="tiny muted mono">{{ fmtClock(r.createdAt) }}</span>
            </div>
            <div v-if="!openRuns.has(r.id)" class="run-b">
              <div class="lbl">INPUT</div><pre class="code-soft">{{ r.input }}</pre>
              <div class="lbl">OUTPUT</div><pre class="code-soft">{{ r.output || '（执行中…）' }}</pre>
              <div v-if="r.childSessionId" class="tiny muted mono" style="margin-top: 6px">session: {{ r.childSessionId }} <button class="link-btn tiny" @click="router.push(`/sessions/${r.childSessionId}`)">跳转 child session</button></div>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="timeline">
        <div v-for="r in d.runs" :key="r.id" class="tl">
          <span class="tl-dot" :class="r.status" />
          <span class="mono tiny muted">{{ fmtTime(r.createdAt) }}</span>
          <span class="tag tag-mono">{{ r.role }}</span>
          <span class="small">{{ taskTitle(r.taskId) }}</span>
          <span class="tiny muted">{{ duration(r.durationMs) }}</span>
        </div>
      </div>
      <Empty v-if="!d.runs.length" title="暂无 Agent Run" compact />
    </div>

    <!-- 用户输入 -->
    <div v-else-if="tab === 'inputs'" class="pane">
      <div v-for="m in d.inputs" :key="m.id" class="input-card card">
        <div class="tiny muted">{{ taskTitle(m.taskId) }} · {{ fmtTime(m.createdAt) }} · {{ m.status === 'open' ? '未消费' : '已回复' }}</div>
        <div class="bold" style="color: var(--purple-text); margin: 6px 0 4px">问题：</div>
        <MarkdownView :source="m.content" compact />
        <template v-if="m.status === 'open' && canEdit">
          <div class="row" style="margin-top: 10px">
            <input v-model="answers[m.id]" class="input" placeholder="输入回复，router 会据此继续分派" @keydown.enter="answer(m)" />
            <button class="btn btn-primary" @click="answer(m)"><Send /> 回复</button>
          </div>
        </template>
        <div v-else class="small" style="margin-top: 6px">回复：{{ m.answer }}</div>
      </div>
      <Empty v-if="!d.inputs.length" title="暂无用户输入请求" compact />
    </div>

    <!-- 产物 -->
    <div v-else-if="tab === 'artifacts'" class="pane">
      <div v-for="f in d.artifacts" :key="f.id" class="art card">
        <span class="bold">{{ f.name }}</span><span class="tag">v{{ f.version }}</span><span class="tiny muted">{{ bytes(f.size) }} · {{ f.mimeType }}</span>
        <span class="spacer" /><a class="btn btn-sm" :href="authedUrl(`/files/${f.id}/download`)">下载</a>
      </div>
      <Empty v-if="!d.artifacts.length" :icon="FileX" title="本次 Team 暂无产物" desc="文件、Git 提交和链接类结果会在 Agent 完成任务后按执行 Agent 汇总到这里。" />
    </div>

    <div v-else-if="tab === 'messages'" class="pane">
      <div v-for="m in d.messages" :key="m.id" class="ev">{{ fmtTime(m.createdAt) }} · {{ m.content }}</div>
      <Empty v-if="!d.messages.length" title="暂无消息" compact />
    </div>
    <div v-else-if="tab === 'events'" class="pane">
      <div v-for="m in d.events" :key="m.id" class="ev"><span class="mono tiny muted">{{ fmtTime(m.createdAt) }}</span> {{ m.content }}</div>
      <Empty v-if="!d.events.length" title="暂无事件" compact />
    </div>
    <div v-else class="pane">
      <pre class="code-soft">{{ JSON.stringify({ session: s, team: d.team, state: s.state }, null, 2) }}</pre>
      <template v-if="s.state?.result"><div class="bold" style="margin: 14px 0 8px">最终结果</div><MarkdownView :source="s.state.result" /></template>
    </div>
  </div>
</template>

<style scoped>
.tb { padding-top: 16px; }
.tb-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.tb-ico { width: 36px; height: 36px; border-radius: 10px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.tb-ico svg { width: 18px; height: 18px; }
.tb-name { font-size: 17px; margin: 2px 0; }
.pane { padding-top: 14px; }
.kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 14px; }
.kpi { border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; background: var(--bg-elev); }
.kpi.blue { background: var(--primary-soft); border-color: var(--primary-soft-2); }
.kpi.warn { background: var(--yellow-soft); }
.kpi.warn2 { background: color-mix(in srgb, var(--orange-soft) 60%, var(--bg-elev)); }
.k-h { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-2); }
.k-h svg { width: 14px; height: 14px; color: var(--primary); }
.k-h svg.ok { color: var(--green); }
.k-v { font-size: 22px; font-weight: 700; margin: 6px 0 2px; }
.k-s { font-size: 11px; color: var(--text-3); }
.pbar { height: 4px; border-radius: 4px; background: var(--primary-soft-2); margin-top: 6px; overflow: hidden; }
.pbar i { display: block; height: 100%; background: var(--primary); }
.cols { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.col { background: var(--bg-muted); border-radius: 12px; padding: 10px; min-height: 120px; }
.col-h { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.tcard { background: var(--bg-elev); border: 1px solid var(--border); border-left: 3px solid; border-radius: 10px; padding: 10px; margin-bottom: 8px; }
.tc-ico { width: 13px; height: 13px; }
.tree-pane { display: flex; border: 1px solid var(--border); border-radius: 12px; margin-top: 14px; height: calc(100vh - 230px); overflow: hidden; background: var(--bg-elev); }
.roles-side { width: 260px; border-right: 1px solid var(--border); overflow: auto; }
.ra { display: flex; gap: 10px; padding: 10px 12px; align-items: flex-start; }
.ra-tag { width: 28px; height: 22px; border-radius: 6px; background: var(--primary-soft); color: var(--primary-text); font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.graph { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.g-bar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--border); }
.canvas { flex: 1; overflow: auto; background-image: radial-gradient(var(--border) 1px, transparent 1px); background-size: 16px 16px; padding: 40px; }
.stage { position: relative; transform-origin: top left; margin: 0 auto; }
.edges { position: absolute; inset: 0; overflow: visible; }
.edges path { fill: none; stroke: var(--text-4); stroke-width: 1.5; }
.gnode { position: absolute; width: 260px; background: var(--bg-elev); border: 1px solid var(--border); border-left: 3px solid var(--text-4); border-radius: 10px; padding: 10px 12px; box-shadow: var(--shadow); }
.gnode.running { border-left-color: var(--primary); }
.gnode.waiting_input { border-left-color: var(--purple); }
.gnode.done { border-left-color: var(--green); }
.gnode.failed { border-left-color: var(--red); }
.lane { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; margin-bottom: 12px; }
.lane-h { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: var(--primary-soft); }
.run { border-top: 1px solid var(--divider); }
.run-h { display: flex; align-items: center; gap: 8px; padding: 8px 12px; cursor: pointer; }
.chev { width: 13px; height: 13px; transition: transform .15s; }
.chev.rot { transform: rotate(-90deg); }
.run-b { padding: 0 14px 14px 32px; }
.lbl { font-size: 10.5px; font-weight: 700; color: var(--text-3); margin: 8px 0 4px; letter-spacing: .06em; }
.run-b pre { margin: 0; max-height: 300px; }
.timeline { border-left: 2px solid var(--border); margin-left: 8px; padding-left: 14px; }
.tl { position: relative; display: flex; align-items: center; gap: 10px; padding: 8px 0; }
.tl-dot { position: absolute; left: -21px; width: 12px; height: 12px; border-radius: 50%; background: var(--primary); border: 2px solid var(--bg-elev); }
.tl-dot.done { background: var(--green); }
.tl-dot.failed { background: var(--red); }
.input-card { padding: 12px 14px; margin-bottom: 10px; }
.art { display: flex; align-items: center; gap: 10px; padding: 10px 14px; margin-bottom: 8px; }
.ev { padding: 8px 4px; border-bottom: 1px solid var(--divider); font-size: 12.5px; }
</style>
