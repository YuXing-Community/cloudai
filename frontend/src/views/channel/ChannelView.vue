<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw, Plus, Bot, Pencil, CircleOff, CirclePlay, Trash2, Copy, Radio, Webhook as WebhookIcon } from 'lucide-vue-next'
import { channelApi, FEISHU_SETTINGS } from '@/api/schedule'
import { optionApi } from '@/api/session'
import { useAuth } from '@/stores/auth'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import Modal from '@/components/ui/Modal.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Empty from '@/components/ui/Empty.vue'
import { confirm, toast } from '@/components/ui/toast'
import { copyText, fmtTime, shortId } from '@/utils/format'

const auth = useAuth()
const canEdit = computed(() => auth.can('channel:edit'))
const type = ref<'feishu' | 'webhook'>('feishu')
const rows = ref<any[]>([])
const counts = ref<Record<string, number>>({})
const active = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await channelApi.list(type.value)
    rows.value = r.records
    counts.value = r.counts
    active.value = r.active
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(type, load)

const STATUS: Record<string, { l: string; t: string }> = { active: { l: '活跃', t: 'tag-green' }, disabled: { l: '禁用', t: 'tag-orange' }, error: { l: '异常', t: 'tag-red' } }
const webhookUrl = (c: any) => `${location.origin}/api/webhooks/${c.webhookToken}`

function menu(c: any) {
  return [
    { label: '编辑', icon: Pencil, onClick: () => edit(c) },
    c.status === 'disabled'
      ? { label: '启用', icon: CirclePlay, onClick: () => setStatus(c, 'active') }
      : { label: '禁用', icon: CircleOff, onClick: () => setStatus(c, 'disabled') },
    { divider: true, label: '' },
    { label: '删除', icon: Trash2, danger: true, onClick: () => remove(c) },
  ]
}
async function setStatus(c: any, s: string) {
  await channelApi.setStatus(c.id, s)
  toast.success(s === 'active' ? '已启用' : '已禁用')
  load()
}
async function remove(c: any) {
  if (!(await confirm({ title: `删除渠道「${c.name}」？`, message: '会断开长连接，该渠道的消息将不再触发 Agent。', danger: true, okText: '删除' }))) return
  await channelApi.remove(c.id)
  load()
}

// 新建 / 编辑
const open = ref(false)
const editing = ref<any>(null)
const f = ref<any>({})
const agents = ref<any[]>([])
const envs = ref<any[]>([])
const saving = ref(false)
const created = ref<any>(null)
async function edit(c?: any) {
  editing.value = c || null
  const [a, e] = await Promise.all([optionApi.agents(), optionApi.envs()])
  agents.value = a
  envs.value = e
  const defaults = Object.fromEntries(FEISHU_SETTINGS.map((s) => [s.key, s.key === 'requireMention' || s.key === 'showTyping']))
  f.value = c
    ? { type: c.type, name: c.name, appId: c.appId || '', appSecret: '', agentId: c.agentId, runtimeEnv: c.runtimeEnv || '', settings: { ...defaults, ...(c.settings || {}) } }
    : { type: type.value, name: '', appId: '', appSecret: '', agentId: '', runtimeEnv: '', settings: defaults }
  open.value = true
}
async function save() {
  const v = f.value
  if (!v.agentId) return toast.warning('请选择 Agent')
  if (v.type === 'feishu' && !editing.value && (!v.appId || !v.appSecret)) return toast.warning('请填写 App ID 和 App Secret')
  if (v.type === 'webhook' && !v.name) return toast.warning('请填写名称')
  saving.value = true
  try {
    if (editing.value) await channelApi.update(editing.value.id, v)
    else {
      const r: any = await channelApi.create(v)
      if (v.type === 'webhook') created.value = r
    }
    toast.success(editing.value ? '已保存' : v.type === 'feishu' ? '渠道已创建，正在建立长连接' : 'Webhook 已创建')
    open.value = false
    type.value = v.type
    load()
  } finally {
    saving.value = false
  }
}
const agentOptions = computed(() => agents.value.map((a) => ({ label: a.name, value: a.id, sub: a.identifier })))
const envOptions = computed(() => [{ label: '跟随 Agent 默认', value: '' }, ...envs.value.map((e) => ({ label: e.name, value: e.name }))])
function copy(t: string) { copyText(t); toast.success('已复制') }
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">消息通道</h1>
        <div class="page-sub">{{ auth.space?.name }} · {{ active }} 个活跃渠道</div>
      </div>
      <div class="page-actions">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button v-if="canEdit" class="btn btn-primary" @click="edit()"><Plus /> 新建渠道</button>
      </div>
    </div>
    <div class="segmented" style="margin-bottom: 14px">
      <button :class="{ active: type === 'feishu' }" @click="type = 'feishu'">飞书 <span class="count">{{ counts.feishu || 0 }}</span></button>
      <button :class="{ active: type === 'webhook' }" @click="type = 'webhook'">Webhook <span class="count">{{ counts.webhook || 0 }}</span></button>
    </div>
    <div class="table-wrap">
      <table v-if="type === 'feishu'" class="table">
        <thead><tr><th>名称</th><th>App ID</th><th>机器人</th><th>绑定目标</th><th>状态</th><th>创建时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td><div class="cell-main">{{ c.name }}</div><div class="cell-sub">{{ shortId(c.id, 20) }}…</div></td>
            <td class="mono small">{{ c.appId }}</td>
            <td><span class="row small" style="gap: 6px"><Bot style="width: 14px; color: var(--text-3)" />{{ c.botName }}</span></td>
            <td class="small" :title="c.agentName">{{ c.agentIdentifier }}</td>
            <td><span class="tag tag-round" :class="STATUS[c.status]?.t" :title="c.statusMessage || ''">{{ STATUS[c.status]?.l || c.status }}</span></td>
            <td class="small">{{ fmtTime(c.createdAt) }}</td>
            <td class="actions"><DropdownMenu v-if="canEdit" :items="menu(c)" /></td>
          </tr>
        </tbody>
      </table>
      <table v-else class="table">
        <thead><tr><th>名称</th><th>Webhook URL</th><th>绑定目标</th><th>状态</th><th>创建时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td class="cell-main">{{ c.name }}</td>
            <td><div class="row" style="gap: 4px"><span class="mono small ellipsis" style="max-width: 360px">{{ webhookUrl(c) }}</span><button class="icon-btn sm" @click="copy(webhookUrl(c))"><Copy /></button></div></td>
            <td class="small">{{ c.agentIdentifier }}</td>
            <td><span class="tag tag-round" :class="STATUS[c.status]?.t">{{ STATUS[c.status]?.l }}</span></td>
            <td class="small">{{ fmtTime(c.createdAt) }}</td>
            <td class="actions"><DropdownMenu v-if="canEdit" :items="menu(c)" /></td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="type === 'feishu' ? Radio : WebhookIcon" :title="type === 'feishu' ? '还没有飞书渠道' : '还没有 Webhook'" :desc="type === 'feishu' ? '填写飞书应用的 App ID / Secret，平台会自动建立 WebSocket 长连接，把群聊/私聊消息转给 Agent' : '生成一个 HTTP 地址，外部系统 POST 消息即可调用 Agent'" />
    </div>

    <Modal :open="open" :title="editing ? '编辑 Channel' : '新建 Channel'" :subtitle="f.type === 'feishu' ? '填写飞书应用信息，创建后服务将自动建立 WebSocket 长连接。' : '创建后会生成一个可被外部系统调用的 Webhook 地址。'" width="560px" @close="open = false">
      <div class="field"><label>渠道类型</label><Select v-model="f.type" :disabled="!!editing" :options="[{ label: '飞书 (Feishu)', value: 'feishu' }, { label: 'Webhook', value: 'webhook' }]" /></div>
      <template v-if="f.type === 'feishu'">
        <div class="field"><label>App ID</label><input v-model="f.appId" class="input mono" placeholder="cli_xxxxxxxxxxxxxxxx" /></div>
        <div class="field"><label>App Secret</label><input v-model="f.appSecret" class="input" type="password" :placeholder="editing ? '留空则不修改' : '输入飞书 App Secret'" autocomplete="new-password" /></div>
      </template>
      <div v-else class="field"><label>名称</label><input v-model="f.name" class="input" placeholder="例如：告警平台回调" /></div>
      <div class="field"><label>绑定 Agent</label><Select v-model="f.agentId" :options="agentOptions" searchable chevrons placeholder="请选择 Agent"><template #prefix><Bot style="width: 14px; color: var(--text-3)" /></template></Select></div>
      <div class="field"><label>运行环境</label><Select v-model="f.runtimeEnv" :options="envOptions" /></div>
      <div v-if="f.type === 'feishu'" class="settings">
        <div class="bold" style="margin-bottom: 6px">飞书设置</div>
        <div v-for="s in FEISHU_SETTINGS" :key="s.key" class="set-row">
          <div><div class="small bold">{{ s.label }}</div><div class="tiny muted">{{ s.desc }}</div></div>
          <Switch v-model="f.settings[s.key]" />
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="open = false">取消</button>
        <button class="btn btn-primary" :disabled="saving" @click="save">{{ editing ? '保存' : '创建' }}</button>
      </template>
    </Modal>

    <Modal :open="!!created" title="Webhook 已创建" width="600px" @close="created = null">
      <div class="label" style="margin-bottom: 6px">调用地址</div>
      <div class="row"><input class="input mono" :value="created ? webhookUrl(created) : ''" readonly /><button class="icon-btn bordered" @click="copy(webhookUrl(created))"><Copy /></button></div>
      <div class="label" style="margin: 14px 0 6px">调用示例</div>
      <pre class="code-block">curl -X POST '{{ created ? webhookUrl(created) : '' }}' \
  -H 'Content-Type: application/json' \
  -d '{"text": "帮我总结今天的告警", "sessionKey": "alert-bot"}'</pre>
      <p class="hint" style="margin-top: 8px">同一个 sessionKey 会复用同一个会话（保留上下文）；接口同步返回 {reply, sessionId, status}。</p>
    </Modal>
  </div>
</template>

<style scoped>
.settings { border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; }
.set-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 0; }
</style>
