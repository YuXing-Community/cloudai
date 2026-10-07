<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Plus, History, Zap, Pause, Play, Trash2, CalendarClock } from 'lucide-vue-next'
import { scheduleApi, RUN_STATUS } from '@/api/schedule'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import Modal from '@/components/ui/Modal.vue'
import Empty from '@/components/ui/Empty.vue'
import ScheduleEditModal from '@/components/ops/ScheduleEditModal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce, fmtTime, shortId } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const canEdit = computed(() => auth.can('schedule:edit'))
const q = ref('')
const status = ref('all')
const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try { rows.value = await scheduleApi.list(q.value, status.value) } finally { loading.value = false }
}
onMounted(load)
watch(q, debounce(load, 300))
watch(status, load)

const editOpen = ref(false)
const editing = ref<any>(null)
function edit(t?: any) { editing.value = t || null; editOpen.value = true }

async function trigger(t: any) {
  await scheduleApi.trigger(t.id)
  toast.success('已触发执行')
  setTimeout(load, 800)
}
async function toggle(t: any) {
  await scheduleApi.enable(t.id, !t.enabled)
  toast.success(t.enabled ? '已暂停' : '已启用')
  load()
}
async function remove(t: any) {
  if (!(await confirm({ title: `删除定时任务「${t.name}」？`, message: '执行记录会一并删除，已产生的会话保留。', danger: true, okText: '删除' }))) return
  await scheduleApi.remove(t.id)
  load()
}

// 执行记录
const runsOf = ref<any>(null)
const runs = ref<any[]>([])
const onlyUnfinished = ref(false)
const errDetail = ref('')
async function openRuns(t: any) {
  runsOf.value = t
  onlyUnfinished.value = false
  await loadRuns()
}
async function loadRuns() {
  runs.value = (await scheduleApi.runs(runsOf.value.id, onlyUnfinished.value)).records
}
watch(onlyUnfinished, () => runsOf.value && loadRuns())
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">定时任务</h1>
        <div class="page-sub">按 cron 周期创建 Agent Session，并自动发送任务说明执行。</div>
      </div>
      <div class="page-actions">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button v-if="canEdit" class="btn btn-primary" @click="edit()"><Plus /> 新建任务</button>
      </div>
    </div>
    <div class="row" style="margin-bottom: 12px">
      <SearchInput v-model="q" placeholder="搜索任务名、描述或 Prompt" width="100%" style="flex: 1" />
      <Select v-model="status" :options="[{ label: '全部状态', value: 'all' }, { label: '已开启', value: 'enabled' }, { label: '已暂停', value: 'disabled' }]" style="width: 130px" />
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>任务</th><th>Agent</th><th>创建人</th><th>周期</th><th>下次触发</th><th>最近执行</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="t in rows" :key="t.id">
            <td>
              <div class="row" style="gap: 8px">
                <span class="cell-main">{{ t.name }}</span>
                <span class="tag tag-round" :class="t.enabled ? 'tag-green' : ''">{{ t.enabled ? '已开启' : '已暂停' }}</span>
              </div>
            </td>
            <td><div>{{ t.agentName }}</div><div class="cell-sub">{{ t.agentVersion ? `v${t.agentVersion}` : 'latest' }}</div></td>
            <td class="small">{{ t.creator }}</td>
            <td><div class="mono small">{{ t.cron }}</div><div class="tiny muted">{{ t.timezone }}</div></td>
            <td class="small">{{ t.enabled ? fmtTime(t.nextFireAt) : '—' }}</td>
            <td>
              <span v-if="t.running" class="tag tag-blue tag-round st">执行中</span>
              <span v-else-if="t.lastStatus" class="tag tag-round st" :class="RUN_STATUS[t.lastStatus]?.tone">{{ RUN_STATUS[t.lastStatus]?.label }}</span>
              <span v-else class="muted small">—</span>
              <div v-if="t.lastRunAt" class="tiny muted">{{ fmtTime(t.lastRunAt) }}</div>
            </td>
            <td class="actions">
              <button class="icon-btn" title="执行记录" @click="openRuns(t)"><History /></button>
              <template v-if="canEdit">
                <button class="icon-btn" title="立即执行" @click="trigger(t)"><Zap /></button>
                <button class="icon-btn" :title="t.enabled ? '暂停' : '启用'" @click="toggle(t)"><Pause v-if="t.enabled" /><Play v-else /></button>
                <button class="btn btn-sm" @click="edit(t)">编辑</button>
                <button class="icon-btn danger" title="删除" @click="remove(t)"><Trash2 /></button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="CalendarClock" title="暂无定时任务" desc="让 Agent 按计划自动执行，例如每天 9 点生成日报" />
    </div>

    <ScheduleEditModal :open="editOpen" :task="editing" @close="editOpen = false" @saved="load" />

    <Modal :open="!!runsOf" :title="runsOf?.name" width="820px" @close="runsOf = null">
      <label class="row small" style="gap: 6px; margin-bottom: 12px; cursor: pointer"><input v-model="onlyUnfinished" type="checkbox" /> 仅未完成执行记录</label>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>计划时间</th><th>状态</th><th>Session</th><th>错误</th><th class="actions">操作</th></tr></thead>
          <tbody>
            <tr v-for="r in runs" :key="r.id">
              <td class="small">{{ fmtTime(r.plannedAt) }} <span v-if="r.manual" class="tag">手动</span><span v-if="r.attempt > 1" class="tag">第 {{ r.attempt }} 次</span></td>
              <td><span class="tag tag-round" :class="RUN_STATUS[r.status]?.tone">{{ RUN_STATUS[r.status]?.label || r.status }}</span></td>
              <td><button v-if="r.sessionId" class="link-btn mono" @click="router.push(`/sessions/${r.sessionId}`)">{{ shortId(r.sessionId) }}</button><span v-else>—</span></td>
              <td class="small ellipsis" style="max-width: 280px">{{ r.error || '—' }}</td>
              <td class="actions"><button v-if="r.error" class="link-btn small" style="color: var(--text)" @click="errDetail = r.error">详情</button></td>
            </tr>
          </tbody>
        </table>
        <Empty v-if="!runs.length" title="暂无执行记录" compact />
      </div>
    </Modal>
    <Modal :open="!!errDetail" title="错误详情" width="560px" @close="errDetail = ''">
      <pre class="code-soft">{{ errDetail }}</pre>
    </Modal>
  </div>
</template>

<style scoped>
.st { min-width: 70px; justify-content: center; }
</style>
