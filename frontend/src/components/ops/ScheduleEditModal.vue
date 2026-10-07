<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Bot, Plus, Trash2, CheckCircle2, X } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import { scheduleApi, channelApi } from '@/api/schedule'
import { optionApi } from '@/api/session'
import { http } from '@/api/http'
import { toast } from '@/components/ui/toast'
import { fmtTime } from '@/utils/format'

const props = defineProps<{ open: boolean; task: any | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const agents = ref<any[]>([])
const envs = ref<any[]>([])
const channels = ref<any[]>([])
const versions = ref<any[]>([])
const f = ref<any>({})
const mode = ref('hourly')
const hour = ref(9)
const minute = ref(0)
const weekdays = ref<number[]>([1])
const monthDay = ref(1)
const customCron = ref('0 * * * *')
const next = ref<string[]>([])
const saving = ref(false)

const MODES = [
  { k: 'hourly', l: '每小时' }, { k: 'daily', l: '每天' }, { k: 'workday', l: '工作日' },
  { k: 'weekly', l: '每周' }, { k: 'monthly', l: '每月' }, { k: 'cron', l: 'Cron' },
]
const WEEK = ['日', '一', '二', '三', '四', '五', '六']

watch(() => props.open, async (v) => {
  if (!v) return
  const [a, e, c] = await Promise.all([optionApi.agents(), optionApi.envs(), channelApi.options().catch(() => [])])
  agents.value = a
  envs.value = e
  channels.value = c.filter((x: any) => x.type === 'feishu')
  const t = props.task
  f.value = t
    ? { name: t.name, agentId: t.agentId, agentVersion: t.agentVersion ?? '', runtimeEnv: t.runtimeEnv || '', prompt: t.prompt, enabled: t.enabled, retryTimes: t.retryTimes, receivers: JSON.parse(JSON.stringify(t.receivers || [])) }
    : { name: '', agentId: a[0]?.id || '', agentVersion: '', runtimeEnv: e.find((x) => x.isDefault)?.name || '', prompt: '', enabled: true, retryTimes: 1, receivers: [] }
  parseCron(t?.cron || '0 * * * *', t?.cronMode || 'hourly')
})
watch(() => f.value.agentId, async (id) => {
  versions.value = []
  if (!id) return
  try { versions.value = (await http.get<any>(`/agents/${id}`, undefined, { silent: true })).versions || [] } catch { /* ignore */ }
})

function parseCron(c: string, m: string) {
  mode.value = m
  customCron.value = c
  const p = c.split(/\s+/)
  minute.value = Number(p[0]) || 0
  hour.value = Number(p[1]) || 0
  weekdays.value = [1]
  monthDay.value = 1
  if (m === 'weekly') weekdays.value = (p[4] || '1').split(',').map(Number)
  if (m === 'monthly') monthDay.value = Number(p[2]) || 1
}
const cron = computed(() => {
  const mi = minute.value, h = hour.value
  switch (mode.value) {
    case 'hourly': return `${mi} * * * *`
    case 'daily': return `${mi} ${h} * * *`
    case 'workday': return `${mi} ${h} * * 1-5`
    case 'weekly': return `${mi} ${h} * * ${[...weekdays.value].sort().join(',') || '1'}`
    case 'monthly': return `${mi} ${h} ${monthDay.value} * *`
    default: return customCron.value.trim()
  }
})
let timer: any
watch(cron, (c) => {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    try { next.value = await scheduleApi.preview(c) } catch { next.value = [] }
  }, 300)
}, { immediate: true })
const modeLabel = computed(() => (mode.value === 'cron' ? '自定义周期' : MODES.find((m) => m.k === mode.value)?.l + '执行'))

function toggleDay(d: number) {
  const i = weekdays.value.indexOf(d)
  i >= 0 ? weekdays.value.splice(i, 1) : weekdays.value.push(d)
}
function addReceiver() {
  if (f.value.receivers.length >= 10) return toast.warning('最多 10 个接收方')
  f.value.receivers.push({ channelId: channels.value[0]?.id || '', chatId: '', notifyResult: true, notifyFailure: false })
}

async function save() {
  const v = f.value
  if (!v.name.trim()) return toast.warning('请填写任务名')
  if (!v.agentId) return toast.warning('请选择 Agent')
  if (!v.prompt.trim()) return toast.warning('请填写任务说明')
  if (!next.value.length) return toast.warning('Cron 表达式无效')
  saving.value = true
  try {
    const data = { ...v, agentVersion: v.agentVersion === '' ? null : v.agentVersion, cron: cron.value, cronMode: mode.value, timezone: 'Asia/Shanghai' }
    if (props.task) await scheduleApi.update(props.task.id, data)
    else await scheduleApi.create(data)
    toast.success('已保存')
    emit('saved')
    emit('close')
  } finally {
    saving.value = false
  }
}
const agentOptions = computed(() => agents.value.map((a) => ({ label: a.name, value: a.id, sub: a.identifier })))
const versionOptions = computed(() => [{ label: '最新版本', value: '' }, ...versions.value.map((x: any) => ({ label: `v${x.version}`, value: x.version, sub: x.note || '' }))])
const envOptions = computed(() => [{ label: '跟随 Agent 默认', value: '' }, ...envs.value.map((e) => ({ label: e.name, value: e.name }))])
</script>

<template>
  <Modal :open="open" :title="task ? '编辑定时任务' : '新建定时任务'" subtitle="到点后会创建新的 Session，并把任务说明作为用户消息发送给 Agent。" width="760px" @close="emit('close')">
    <fieldset class="fieldset">
      <legend>基本信息</legend>
      <div class="form-row">
        <div class="field"><label>任务名</label><input v-model="f.name" class="input" placeholder="例如：离线推数任务延迟归因" /></div>
        <div class="field"><label>Agent</label><Select v-model="f.agentId" :options="agentOptions" searchable chevrons><template #prefix><Bot style="width: 14px; color: var(--text-3)" /></template></Select></div>
      </div>
      <div class="form-row-3">
        <div class="field"><label>版本</label><Select v-model="f.agentVersion" :options="versionOptions" /></div>
        <div class="field"><label>运行环境</label><Select v-model="f.runtimeEnv" :options="envOptions" /></div>
        <div class="field"><label>启用</label><div class="en-box"><Switch v-model="f.enabled" label="创建后启用" /></div></div>
      </div>
      <div class="field"><label>任务说明</label><textarea v-model="f.prompt" class="textarea" rows="3" placeholder="写给 Agent 的任务指令，每次执行都会作为用户消息发送" /></div>
      <div class="field" style="max-width: 180px"><label>失败重试次数</label><input v-model.number="f.retryTimes" type="number" min="0" max="5" class="input" /></div>
      <div class="row" style="justify-content: space-between; align-items: flex-start; margin-bottom: 8px">
        <div><div class="label">消息接收方</div><div class="hint">每个接收方可独立开启结果通知和失败告警；新接收方默认仅开启结果通知（最多 10 个）。</div></div>
        <button class="btn" @click="addReceiver"><Plus /> 添加接收方</button>
      </div>
      <div v-for="(r, i) in f.receivers" :key="i" class="recv">
        <Select v-model="r.channelId" :options="channels.map((c) => ({ label: c.name, value: c.id }))" placeholder="选择飞书通道" style="width: 180px" />
        <input v-model="r.chatId" class="input mono" placeholder="群 chat_id，如 oc_xxx" />
        <Switch v-model="r.notifyResult" size="sm" label="结果" />
        <Switch v-model="r.notifyFailure" size="sm" label="失败告警" />
        <button class="icon-btn sm danger" @click="f.receivers.splice(i, 1)"><Trash2 /></button>
      </div>
      <div v-if="!f.receivers.length" class="empty-box" style="margin-bottom: 14px">{{ channels.length ? '未配置消息接收方，执行完成后仅保留执行记录' : '空间内还没有飞书通道，可先在「消息通道」中创建' }}</div>
    </fieldset>

    <fieldset class="fieldset">
      <legend>执行周期</legend>
      <div class="modes">
        <button v-for="m in MODES" :key="m.k" :class="{ on: mode === m.k }" @click="mode = m.k">{{ m.l }}</button>
      </div>
      <div v-if="mode !== 'cron'" class="row" style="gap: 12px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 12px">
        <div v-if="mode === 'weekly'" class="field" style="margin: 0">
          <label>星期</label>
          <div class="days"><button v-for="(d, i) in WEEK" :key="i" :class="{ on: weekdays.includes(i) }" @click="toggleDay(i)">{{ d }}</button></div>
        </div>
        <div v-if="mode === 'monthly'" class="field" style="margin: 0; width: 100px"><label>日期</label><input v-model.number="monthDay" type="number" min="1" max="31" class="input" /></div>
        <div class="field" style="margin: 0; width: 100px"><label>小时</label><input v-model.number="hour" type="number" min="0" max="23" class="input" :disabled="mode === 'hourly'" /></div>
        <div class="field" style="margin: 0; width: 100px"><label>分钟</label><input v-model.number="minute" type="number" min="0" max="59" class="input" /></div>
      </div>
      <div class="field">
        <label>生成的 Cron 表达式 <span class="hint" style="margin-left: auto; font-weight: 400">时区：Asia/Shanghai</span></label>
        <input v-if="mode === 'cron'" v-model="customCron" class="input mono big" placeholder="分 时 日 月 周，如 */30 * * * *" />
        <input v-else class="input mono big" :value="cron" readonly />
      </div>
      <div class="next" :class="{ bad: !next.length }">
        <CheckCircle2 v-if="next.length" /><X v-else />
        <span class="bold">{{ next.length ? modeLabel : 'Cron 表达式无效' }}</span>
        <span v-if="next.length" class="muted">下次：{{ next.map((n) => fmtTime(n)).join('、') }}</span>
      </div>
    </fieldset>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="save">保存</button>
    </template>
  </Modal>
</template>

<style scoped>
.en-box { height: 34px; display: flex; align-items: center; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); }
.recv { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 10px; margin-bottom: 8px; }
.recv .input { flex: 1; }
.modes { display: flex; gap: 6px; margin-bottom: 14px; }
.modes button { height: 34px; padding: 0 14px; border: none; border-radius: 10px; background: transparent; cursor: pointer; font-weight: 600; color: var(--text-2); }
.modes button.on { background: var(--primary-soft); color: var(--primary-text); }
.days { display: flex; gap: 4px; }
.days button { width: 32px; height: 32px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; }
.days button.on { background: var(--primary); border-color: var(--primary); color: #fff; }
.input.big { height: 40px; background: var(--bg-muted); font-size: 14px; }
.next { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 14px; font-size: 13px; flex-wrap: wrap; }
.next svg { width: 16px; height: 16px; color: var(--green); }
.next.bad svg { color: var(--red); }
</style>
