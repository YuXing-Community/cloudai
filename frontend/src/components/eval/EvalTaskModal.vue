<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Bot, Check, CheckCircle2, X, Search, Hand, CalendarClock, Zap } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import Pagination from '@/components/ui/Pagination.vue'
import { evalApi, TRIGGER_MODES, SESSION_SOURCES, SESSION_STATUSES } from '@/api/eval'
import { optionApi } from '@/api/session'
import { scheduleApi } from '@/api/schedule'
import { toast } from '@/components/ui/toast'
import { debounce, fmtTime, shortId } from '@/utils/format'
import { levelLabel } from '@/api/settings'

const props = defineProps<{ open: boolean; task: any | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved', id: number): void }>()

const agents = ref<any[]>([])
const models = ref<any[]>([])
const f = ref<any>({})
const saving = ref(false)
const next = ref<string[]>([])

const TRIGGER_ICONS: Record<string, any> = { manual: Hand, schedule: CalendarClock, realtime: Zap }

function blank() {
  return {
    name: '', description: '', agentIds: [] as string[], judgeModelRef: '', replay: false, replayModelRef: '',
    timeMode: 'all', from: '', to: '', recentHours: 24, sessionIdsText: '', keyword: '', sources: [] as string[], statuses: [] as string[],
    criteria: '', passScore: 60, skipEvaluated: true, triggerMode: 'manual', cron: '0 2 * * *', enabled: true,
  }
}
const toInput = (v?: string) => (v ? v.replace(' ', 'T').slice(0, 16) : '')
const fromInput = (v?: string) => (v ? v.replace('T', ' ') : '')

watch(() => props.open, async (v) => {
  if (!v) return
  const [a, m] = await Promise.all([optionApi.agents(), optionApi.models()])
  agents.value = a
  models.value = m
  const t = props.task
  const b = blank()
  if (t) {
    const fl = t.filters || {}
    Object.assign(b, {
      name: t.name, description: t.description || '', agentIds: [...(t.agentIds || [])], judgeModelRef: t.judgeModelRef,
      replay: !!t.replayModelRef, replayModelRef: t.replayModelRef || '',
      timeMode: fl.timeMode || 'all', from: toInput(fl.from), to: toInput(fl.to), recentHours: fl.recentHours || 24,
      sessionIdsText: (fl.sessionIds || []).join('\n'), keyword: fl.keyword || '', sources: fl.sources || [], statuses: fl.statuses || [],
      criteria: t.criteria || '', passScore: t.passScore ?? 60, skipEvaluated: t.skipEvaluated !== false,
      triggerMode: t.triggerMode, cron: t.cron || '0 2 * * *', enabled: t.enabled,
    })
  } else {
    b.agentIds = a[0] ? [a[0].id] : []
    b.judgeModelRef = modelOptions.value[0]?.value || ''
  }
  f.value = b
  page.value = 1
  loadCandidates()
})

const modelOptions = computed(() => models.value.flatMap((p: any) => p.models.map((m: any) => ({
  label: m.displayName || m.modelId, value: m.ref, sub: m.ref, group: p.providerName || p.providerKey, extra: m.level ? levelLabel(m.level) : '',
}))))

function toggle(list: string[], v: string) {
  const i = list.indexOf(v)
  i >= 0 ? list.splice(i, 1) : list.push(v)
}

const filters = computed(() => {
  const v = f.value
  const fl: any = { timeMode: v.timeMode, sources: v.sources, statuses: v.statuses, keyword: (v.keyword || '').trim() }
  if (v.timeMode === 'range') { fl.from = fromInput(v.from); fl.to = fromInput(v.to) }
  if (v.timeMode === 'recent') fl.recentHours = Number(v.recentHours) || 24
  fl.sessionIds = (v.sessionIdsText || '').split(/[\s,，]+/).map((s: string) => s.trim()).filter(Boolean)
  return fl
})

// ---------------- 执行记录预览 ----------------
const candidates = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const loadingC = ref(false)
async function loadCandidates() {
  if (!props.open || !f.value.agentIds?.length) { candidates.value = []; total.value = 0; return }
  loadingC.value = true
  try {
    const r = await evalApi.candidates({ agentIds: f.value.agentIds, filters: filters.value, taskId: props.task?.id, page: page.value, size: 5 })
    candidates.value = r.records
    total.value = r.total
  } catch { candidates.value = []; total.value = 0 } finally { loadingC.value = false }
}
const reload = debounce(() => { page.value = 1; loadCandidates() }, 350)
watch(() => [f.value.agentIds?.join(','), JSON.stringify(filters.value)], reload)
watch(page, loadCandidates)

// ---------------- cron 预览 ----------------
let timer: any
watch(() => [f.value.triggerMode, f.value.cron], () => {
  clearTimeout(timer)
  if (f.value.triggerMode !== 'schedule') return
  timer = setTimeout(async () => {
    try { next.value = await scheduleApi.preview(f.value.cron) } catch { next.value = [] }
  }, 300)
})
const CRON_PRESETS = [
  { l: '每小时', c: '0 * * * *' }, { l: '每天 02:00', c: '0 2 * * *' }, { l: '工作日 09:00', c: '0 9 * * 1-5' }, { l: '每周一 08:00', c: '0 8 * * 1' },
]

async function save() {
  const v = f.value
  if (!v.name.trim()) return toast.warning('请填写任务名称')
  if (!v.agentIds.length) return toast.warning('请至少选择一个 Agent')
  if (!v.judgeModelRef) return toast.warning('请选择评测模型')
  if (v.replay && !v.replayModelRef) return toast.warning('请选择重放执行模型')
  if (v.timeMode === 'range' && !v.from && !v.to) return toast.warning('请选择时间范围')
  if (v.triggerMode === 'schedule' && !next.value.length) return toast.warning('Cron 表达式无效')
  saving.value = true
  try {
    const data = {
      name: v.name.trim(), description: v.description, agentIds: v.agentIds, judgeModelRef: v.judgeModelRef,
      replayModelRef: v.replay ? v.replayModelRef : null, filters: filters.value, criteria: v.criteria, passScore: v.passScore,
      skipEvaluated: v.skipEvaluated, triggerMode: v.triggerMode, cron: v.triggerMode === 'schedule' ? v.cron : null,
      timezone: 'Asia/Shanghai', enabled: v.enabled,
    }
    const r: any = props.task ? await evalApi.update(props.task.id, data) : await evalApi.create(data)
    toast.success('已保存')
    emit('saved', r.id)
    emit('close')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" :title="task ? '编辑评测任务' : '新建评测任务'" subtitle="选择 Agent 与执行记录，由评测模型逐个会话打分并给出修改意见，结果按会话 ID 落表。" width="860px" @close="emit('close')">
    <fieldset class="fieldset">
      <legend>基本信息</legend>
      <div class="form-row">
        <div class="field"><label>任务名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="例如：SQL 助手每日质量评测" /></div>
        <div class="field"><label>描述</label><input v-model="f.description" class="input" placeholder="可选" /></div>
      </div>
      <div class="field">
        <label>评测的 Agent <span class="req">*</span></label>
        <div class="chips">
          <button v-for="a in agents" :key="a.id" class="chip" :class="{ on: f.agentIds?.includes(a.id) }" @click="toggle(f.agentIds, a.id)">
            <Check v-if="f.agentIds?.includes(a.id)" /><Bot v-else />{{ a.name }}
          </button>
          <span v-if="!agents.length" class="hint">当前空间还没有 Agent</span>
        </div>
      </div>
    </fieldset>

    <fieldset class="fieldset">
      <legend>模型</legend>
      <div class="form-row">
        <div class="field">
          <label>评测模型 <span class="req">*</span></label>
          <Select v-model="f.judgeModelRef" :options="modelOptions" searchable placeholder="选择 Provider 下的模型" />
          <div class="hint">负责阅读执行轨迹、打分并给出修改意见，建议选择能力更强的模型</div>
        </div>
        <div class="field">
          <label>评测方式</label>
          <div class="segmented block">
            <button :class="{ active: !f.replay }" @click="f.replay = false">直接评测原会话日志</button>
            <button :class="{ active: f.replay }" @click="f.replay = true">指定模型重放后评测</button>
          </div>
          <div class="hint">{{ f.replay ? '用下方模型把原会话的用户输入重新执行一遍，生成新会话后再评测，用于对比不同模型效果' : '读取原会话的用户输入、工具调用、模型输出等完整日志进行评测' }}</div>
        </div>
      </div>
      <div v-if="f.replay" class="field" style="max-width: 50%"><label>重放执行模型 <span class="req">*</span></label><Select v-model="f.replayModelRef" :options="modelOptions" searchable placeholder="选择执行模型" /></div>
    </fieldset>

    <fieldset class="fieldset">
      <legend>执行记录</legend>
      <div class="field">
        <label>时间（会话最后活动时间）</label>
        <div class="row" style="gap: 10px; flex-wrap: wrap">
          <div class="segmented">
            <button :class="{ active: f.timeMode === 'all' }" @click="f.timeMode = 'all'">全部</button>
            <button :class="{ active: f.timeMode === 'range' }" @click="f.timeMode = 'range'">时间范围</button>
            <button :class="{ active: f.timeMode === 'recent' }" @click="f.timeMode = 'recent'">最近</button>
          </div>
          <template v-if="f.timeMode === 'range'">
            <input v-model="f.from" type="datetime-local" class="input dt" />
            <span class="muted">至</span>
            <input v-model="f.to" type="datetime-local" class="input dt" />
          </template>
          <template v-if="f.timeMode === 'recent'">
            <input v-model.number="f.recentHours" type="number" min="1" class="input" style="width: 90px" /><span class="muted small">小时内（定时执行时按触发时刻滚动计算）</span>
          </template>
        </div>
      </div>
      <div class="form-row">
        <div class="field"><label>会话 ID</label><textarea v-model="f.sessionIdsText" class="textarea mono" rows="2" placeholder="可选，多个用换行或逗号分隔，支持 ID 前缀" /></div>
        <div class="field"><label>会话内容</label><textarea v-model="f.keyword" class="textarea" rows="2" placeholder="可选，模糊匹配会话标题、用户输入和 Agent 回复" /></div>
      </div>
      <div class="form-row">
        <div class="field">
          <label>来源 <span class="hint" style="font-weight: 400">不选为全部</span></label>
          <div class="chips"><button v-for="s in SESSION_SOURCES" :key="s.value" class="chip sm" :class="{ on: f.sources?.includes(s.value) }" @click="toggle(f.sources, s.value)">{{ s.label }}</button></div>
        </div>
        <div class="field">
          <label>会话状态 <span class="hint" style="font-weight: 400">不选为全部已结束会话</span></label>
          <div class="chips"><button v-for="s in SESSION_STATUSES" :key="s.value" class="chip sm" :class="{ on: f.statuses?.includes(s.value) }" @click="toggle(f.statuses, s.value)">{{ s.label }}</button></div>
        </div>
      </div>
      <div class="preview">
        <div class="preview-head">
          <Search /><span class="bold">匹配的执行记录</span><span class="muted">共 {{ total }} 条</span>
          <span v-if="f.triggerMode === 'realtime'" class="hint" style="margin-left: auto">实时模式只评测开启后新产生的会话，时间条件不生效</span>
        </div>
        <table class="table">
          <tbody>
            <tr v-for="c in candidates" :key="c.id">
              <td style="width: 90px"><span class="mono small">{{ shortId(c.id) }}</span></td>
              <td><div class="ellipsis bold small" style="max-width: 300px">{{ c.title }}</div><div class="tiny muted ellipsis" style="max-width: 300px">{{ c.reply || '—' }}</div></td>
              <td class="small">{{ c.agentName }}</td>
              <td class="tiny muted">{{ fmtTime(c.lastEventAt) }}</td>
              <td v-if="task" class="tiny" style="text-align: right">{{ c.lastScore != null ? `上次 ${c.lastScore} 分` : '未评测' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!candidates.length" class="empty-box" style="margin: 10px">{{ loadingC ? '加载中…' : '没有匹配的执行记录（只包含已结束的会话）' }}</div>
        <div v-if="total > 5" style="padding: 0 10px"><Pagination v-model:page="page" :size="5" :total="total" compact /></div>
      </div>
    </fieldset>

    <fieldset class="fieldset">
      <legend>评分规则</legend>
      <div class="form-row">
        <div class="field"><label>及格分（0-100）</label><input v-model.number="f.passScore" type="number" min="0" max="100" class="input" /></div>
        <div class="field"><label>重复评测</label><div class="en-box"><Switch v-model="f.skipEvaluated" label="跳过本任务已评测且之后没有新消息的会话" /></div></div>
      </div>
      <div class="field">
        <label>自定义评测标准</label>
        <textarea v-model="f.criteria" class="textarea" rows="3" placeholder="可选，会追加到评测提示词。例如：SQL 必须兼容 MySQL 8；涉及删除数据必须提示备份；回答需给出索引建议" />
        <div class="hint">内置维度：任务完成度 30%、准确性 25%、工具使用 15%、指令遵循 15%、回复质量 15%</div>
      </div>
    </fieldset>

    <fieldset class="fieldset">
      <legend>触发方式</legend>
      <div class="modes">
        <button v-for="m in TRIGGER_MODES" :key="m.value" class="mode" :class="{ on: f.triggerMode === m.value }" @click="f.triggerMode = m.value">
          <component :is="TRIGGER_ICONS[m.value]" />
          <div><div class="bold">{{ m.label }}</div><div class="tiny muted">{{ m.desc }}</div></div>
        </button>
      </div>
      <template v-if="f.triggerMode === 'schedule'">
        <div class="field">
          <label>Cron 表达式 <span class="hint" style="margin-left: auto; font-weight: 400">时区：Asia/Shanghai</span></label>
          <div class="row" style="gap: 6px">
            <input v-model="f.cron" class="input mono" placeholder="分 时 日 月 周" style="flex: 1" />
            <button v-for="p in CRON_PRESETS" :key="p.c" class="btn btn-sm" @click="f.cron = p.c">{{ p.l }}</button>
          </div>
        </div>
        <div class="next" :class="{ bad: !next.length }">
          <CheckCircle2 v-if="next.length" /><X v-else />
          <span v-if="next.length" class="muted">下次：{{ next.map((n) => fmtTime(n)).join('、') }}</span>
          <span v-else class="bold">Cron 表达式无效</span>
        </div>
      </template>
      <div v-if="f.triggerMode === 'realtime'" class="notice notice-info" style="margin-bottom: 12px">
        <Zap />开启后每 15 秒检查一次：所选 Agent 有新会话执行完成（或已评测的会话有了新消息），就自动创建评测批次。
      </div>
      <div v-if="f.triggerMode !== 'manual'" class="en-box" style="margin-bottom: 4px"><Switch v-model="f.enabled" :label="f.triggerMode === 'realtime' ? '启用实时评测' : '启用定时评测'" /></div>
    </fieldset>

    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="save">保存</button>
    </template>
  </Modal>
</template>

<style scoped>
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 5px; height: 30px; padding: 0 12px; border: 1px solid var(--border); border-radius: 999px; background: var(--bg-elev); color: var(--text-2); cursor: pointer; font-size: 12.5px; }
.chip.sm { height: 26px; padding: 0 10px; font-size: 12px; }
.chip svg { width: 13px; height: 13px; }
.chip:hover { border-color: var(--border-strong); }
.chip.on { border-color: var(--primary); background: var(--primary-soft); color: var(--primary-text); font-weight: 600; }
.input.dt { width: 200px; }
.en-box { min-height: 34px; display: flex; align-items: center; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); }
.preview { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; margin-bottom: 14px; }
.preview-head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; background: var(--bg-muted); border-bottom: 1px solid var(--divider); font-size: 13px; }
.preview-head svg { width: 14px; height: 14px; color: var(--text-3); }
.preview .table td { padding: 8px 12px; }
.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px; }
.mode { display: flex; gap: 10px; align-items: flex-start; text-align: left; padding: 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elev); cursor: pointer; color: var(--text); }
.mode svg { width: 18px; height: 18px; color: var(--text-3); flex-shrink: 0; margin-top: 1px; }
.mode.on { border-color: var(--primary); background: var(--primary-soft); }
.mode.on svg { color: var(--primary); }
.next { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 14px; font-size: 13px; flex-wrap: wrap; }
.next svg { width: 16px; height: 16px; color: var(--green); }
.next.bad svg { color: var(--red); }
</style>
