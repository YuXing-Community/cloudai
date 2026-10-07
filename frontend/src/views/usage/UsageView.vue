<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { BarChart3, Users, AlertCircle, CircleDollarSign, MessagesSquare, Clock, Sparkles, ChevronRight, Layers } from 'lucide-vue-next'
import { http } from '@/api/http'
import { optionApi } from '@/api/session'
import Select from '@/components/ui/Select.vue'
import AreaChart from '@/components/charts/AreaChart.vue'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import { cnNum, fmtClock, thousands } from '@/utils/format'

const agents = ref<any[]>([])
const agentId = ref('')
const granularity = ref<'day' | 'week'>('day')
const mode = ref<'default' | 'custom'>('default')
const range = ref('7d')
const from = ref('')
const to = ref('')
const metric = ref<'tokens' | 'cost' | 'sessions' | 'turns'>('tokens')
const data = ref<any>(null)
const loading = ref(false)
const expanded = ref<Set<string>>(new Set())

async function load() {
  loading.value = true
  try {
    data.value = await http.get('/usage/summary', {
      agentId: agentId.value, granularity: granularity.value,
      range: mode.value === 'default' ? range.value : undefined,
      from: mode.value === 'custom' ? from.value : undefined, to: mode.value === 'custom' ? to.value : undefined,
    })
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  agents.value = await optionApi.agents()
  load()
})
watch([agentId, granularity, range, mode], () => { if (mode.value === 'default' || (from.value && to.value)) load() })
watch([from, to], () => { if (mode.value === 'custom' && from.value && to.value) load() })

const agentOptions = computed(() => [{ label: '全部 Agent', value: '' }, ...agents.value.map((a) => ({ label: a.name, value: a.id }))])
const t = computed(() => data.value?.totals || {})
const money = (v: any) => `¥${Number(v || 0).toFixed(2)}`
const fmt = computed(() => (metric.value === 'cost' ? (v: number) => money(v) : metric.value === 'tokens' ? (v: number) => cnNum(v) : (v: number) => thousands(Math.round(v))))
const points = computed(() => (data.value?.series || []).map((s: any) => {
  const [y, mo, da] = String(s.date).split('-').map(Number)
  const d = new Date(y, mo - 1, da)
  return { label: `${d.getMonth() + 1}/${d.getDate()}`, value: Number(s[metric.value] || 0) }
}))
const maxSkill = computed(() => Math.max(1, ...(data.value?.skillRank || []).map((s: any) => s.n)))

function toggle(id: string) {
  const s = new Set(expanded.value)
  s.has(id) ? s.delete(id) : s.add(id)
  expanded.value = s
}
</script>

<template>
  <div class="page usage">
    <div class="page-head" style="border-bottom: 1px solid var(--border); padding-bottom: 14px">
      <div>
        <h1 class="page-title"><BarChart3 style="width: 20px; color: var(--primary)" /> 用量</h1>
        <div class="page-sub">Workspace 使用概览 · <span class="status-dot" style="background: var(--green)" /> 最近查询 {{ data ? fmtClock(data.queriedAt) : '—' }}</div>
      </div>
      <div class="page-actions">
        <Select v-model="agentId" :options="agentOptions" searchable style="width: 200px">
          <template #prefix><Users style="width: 14px; color: var(--text-3)" /></template>
        </Select>
        <div class="segmented blue">
          <button :class="{ active: granularity === 'day' }" @click="granularity = 'day'">按天</button>
          <button :class="{ active: granularity === 'week' }" @click="granularity = 'week'">按周</button>
        </div>
        <div class="segmented blue">
          <button :class="{ active: mode === 'default' }" @click="mode = 'default'">默认</button>
          <button :class="{ active: mode === 'custom' }" @click="mode = 'custom'">自定义</button>
        </div>
        <div v-if="mode === 'default'" class="segmented blue">
          <button v-for="r in ['1d', '7d', '14d', '30d']" :key="r" :class="{ active: range === r }" @click="range = r">{{ r }}</button>
        </div>
        <div v-else class="row">
          <input v-model="from" type="date" class="input input-sm" style="width: 140px" />
          <span class="muted">至</span>
          <input v-model="to" type="date" class="input input-sm" style="width: 140px" />
        </div>
      </div>
    </div>

    <div v-if="t.skippedModels" class="notice notice-warn" style="margin-bottom: 16px">
      <AlertCircle /> 未配置模型价格已跳过费用计算（{{ t.skippedModels }} 个模型）。页面价格仅供参考，实际以模型网关为准。
    </div>

    <div class="kpis card">
      <div class="kpi"><div class="k-head">费用 <CircleDollarSign /></div><div class="k-v">{{ money(t.cost) }}</div><div class="k-s">{{ t.skippedModels ? `已跳过 ${t.skippedModels} 个未配置价格的模型` : '按模型价格估算' }}</div></div>
      <div class="kpi"><div class="k-head">Token <BarChart3 /></div><div class="k-v">{{ cnNum(t.tokens) }}</div><div class="k-s">输入 {{ cnNum(t.inputTokens) }} · 输出 {{ cnNum(t.outputTokens) }} · 缓存 {{ cnNum(t.cacheTokens) }}</div></div>
      <div class="kpi"><div class="k-head">新建会话 <MessagesSquare /></div><div class="k-v">{{ thousands(t.sessions) }}</div><div class="k-s">成功创建的 Session</div></div>
      <div class="kpi"><div class="k-head">用户轮次 <Clock /></div><div class="k-v">{{ thousands(t.turns) }}</div><div class="k-s">每条成功持久化的用户消息计一次</div></div>
      <div class="kpi"><div class="k-head">Skill 使用 <Sparkles /></div><div class="k-v">{{ thousands(t.skillUses) }}</div><div class="k-s">成功使用已注册 Skill 的次数</div></div>
    </div>

    <section class="block">
      <div class="row" style="justify-content: space-between; margin-bottom: 10px">
        <div>
          <div class="section-title" style="font-size: 16px">每日{{ { tokens: 'Token', cost: '费用', sessions: '会话', turns: '轮次' }[metric] }}</div>
          <div class="section-sub">查看所选范围内的用量变化</div>
        </div>
        <div class="segmented blue">
          <button :class="{ active: metric === 'tokens' }" @click="metric = 'tokens'">Token</button>
          <button :class="{ active: metric === 'cost' }" @click="metric = 'cost'">费用</button>
          <button :class="{ active: metric === 'sessions' }" @click="metric = 'sessions'">会话</button>
          <button :class="{ active: metric === 'turns' }" @click="metric = 'turns'">轮次</button>
        </div>
      </div>
      <AreaChart :points="points" :format="fmt" :height="260" />
    </section>

    <div class="ranks">
      <section>
        <div class="section-title" style="font-size: 16px">Agent × 模型排行</div>
        <div class="section-sub" style="margin-bottom: 10px">每个 Agent 汇总一行，按总 Token 降序；展开可查看模型明细</div>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>Agent</th><th>模型数</th><th style="text-align: right">Token</th><th style="text-align: right">费用</th><th style="text-align: right">会话</th><th style="text-align: right">轮次</th></tr></thead>
            <tbody>
              <template v-for="(a, i) in data?.agentRank || []" :key="a.agentId">
                <tr class="clickable" @click="toggle(a.agentId)">
                  <td>
                    <div class="row" style="gap: 8px">
                      <ChevronRight class="chev" :class="{ rot: expanded.has(a.agentId) }" />
                      <span class="muted mono" style="width: 16px">{{ i + 1 }}</span>
                      <AgentAvatar :name="a.name" :avatar="a.avatar" :size="24" :radius="7" />
                      <span class="bold ellipsis">{{ a.name }}</span>
                    </div>
                  </td>
                  <td><span class="tag tag-round"><Layers /> {{ a.modelCount }}</span></td>
                  <td class="num bold">{{ cnNum(a.tokens) }}</td>
                  <td class="num" :class="{ skip: a.skipped && !Number(a.cost) }">{{ a.skipped && !Number(a.cost) ? '已跳过' : money(a.cost) }}</td>
                  <td class="num">{{ thousands(a.sessions) }}</td>
                  <td class="num">{{ thousands(a.turns) }}</td>
                </tr>
                <tr v-for="m in expanded.has(a.agentId) ? a.models : []" :key="a.agentId + m.model" class="sub-row">
                  <td style="padding-left: 74px" class="mono small">{{ m.model }}</td>
                  <td />
                  <td class="num small">{{ cnNum(m.tokens) }}</td>
                  <td class="num small" :class="{ skip: m.skipped }">{{ m.skipped ? '已跳过' : money(m.cost) }}</td>
                  <td class="num small">{{ m.sessions }}</td>
                  <td class="num small">{{ m.turns }}</td>
                </tr>
              </template>
              <tr v-if="!data?.agentRank?.length"><td colspan="6" class="empty small">所选范围内暂无数据</td></tr>
            </tbody>
          </table>
        </div>
      </section>
      <section>
        <div class="section-title" style="font-size: 16px">Skill 使用排行</div>
        <div class="section-sub" style="margin-bottom: 10px">每个 Skill 汇总一行，不区分 Agent；按使用次数降序</div>
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th style="width: 30px">#</th><th>Skill</th><th>相对使用量</th><th style="text-align: right">次数</th></tr></thead>
            <tbody>
              <tr v-for="(s, i) in data?.skillRank || []" :key="s.name">
                <td class="muted">{{ i + 1 }}</td>
                <td class="mono ellipsis" style="max-width: 140px">{{ s.name }}</td>
                <td><div class="bar"><i :style="{ width: (s.n / maxSkill) * 100 + '%' }" /></div></td>
                <td class="num bold">{{ s.n }}</td>
              </tr>
              <tr v-if="!data?.skillRank?.length"><td colspan="4" class="empty small">暂无 Skill 使用</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.usage { max-width: 1240px; margin: 0 auto; }
.kpis { display: grid; grid-template-columns: repeat(5, 1fr); margin-bottom: 22px; }
.kpi { padding: 16px 18px; border-right: 1px solid var(--divider); }
.kpi:last-child { border-right: none; }
.k-head { display: flex; justify-content: space-between; align-items: center; font-size: 12.5px; color: var(--text-2); }
.k-head svg { width: 14px; height: 14px; color: var(--text-4); }
.k-v { font-size: 26px; font-weight: 700; margin: 8px 0 4px; letter-spacing: -.02em; }
.k-s { font-size: 11.5px; color: var(--text-3); }
.block { padding-bottom: 22px; margin-bottom: 22px; border-bottom: 1px solid var(--border); }
.ranks { display: grid; grid-template-columns: 1.7fr 1fr; gap: 22px; }
.num { text-align: right; font-family: var(--mono); }
.skip { color: var(--orange-text); font-family: var(--font); }
.chev { width: 14px; height: 14px; color: var(--text-4); transition: transform .15s; }
.chev.rot { transform: rotate(90deg); }
.sub-row td { background: var(--bg-muted); padding-top: 8px; padding-bottom: 8px; }
.bar { height: 6px; border-radius: 99px; background: var(--bg-muted); overflow: hidden; min-width: 80px; }
.bar i { display: block; height: 100%; background: var(--primary); border-radius: 99px; }
.tag svg { width: 11px; height: 11px; }
</style>
