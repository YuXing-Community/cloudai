<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, MessageSquare, Pencil, RefreshCw, Share2, Save, Code2, Eye, Sparkles, Settings2, SlidersHorizontal, Link2,
  Wrench, Plug, Terminal, Database, Brain, ShieldCheck, KeyRound, Tags, Trash2, Plus, Box, Code, Bot, Check, Loader2, Image,
} from 'lucide-vue-next'
import { agentApi, refApi, BUILTIN_TOOLS, type AgentRow } from '@/api/agent'
import { useAuth } from '@/stores/auth'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import Modal from '@/components/ui/Modal.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import ConfigSection from '@/components/agent/ConfigSection.vue'
import ModelConfigModal from '@/components/agent/ModelConfigModal.vue'
import SkillPickerModal from '@/components/agent/SkillPickerModal.vue'
import ListPickerModal from '@/components/agent/ListPickerModal.vue'
import CliPluginModal from '@/components/agent/CliPluginModal.vue'
import SecretEnvModal from '@/components/agent/SecretEnvModal.vue'
import ShareAgentModal from '@/components/agent/ShareAgentModal.vue'
import DebugChat from '@/components/chat/DebugChat.vue'
import { PRESET_AVATARS } from '@/components/ui/avatars'
import { confirm, toast } from '@/components/ui/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const id = computed(() => route.params.id as string)
const canEdit = computed(() => auth.can('agent:edit'))

const agent = ref<AgentRow | null>(null)
const form = ref<any>(null)
const savedJson = ref('')
const saving = ref(false)
const promptMode = ref<'preview' | 'edit'>('preview')
const editingName = ref(false)
const editingDesc = ref(false)
const moreRuntime = ref(false)

// 候选数据
const models = ref<any[]>([])
const envs = ref<any[]>([])
const hooks = ref<any[]>([])
const plugins = ref<any[]>([])
const skills = ref<any[]>([])
const stores = ref<any[]>([])
const agents = ref<any[]>([])
const creds = ref<any[]>([])

// 弹窗
const modelOpen = ref(false)
const skillOpen = ref(false)
const mcpOpen = ref(false)
const cliOpen = ref(false)
const storeOpen = ref(false)
const subOpen = ref(false)
const secretOpen = ref(false)
const hooksOpen = ref(false)
const shareOpen = ref(false)
const avatarOpen = ref(false)

const cfg = computed(() => form.value?.config || {})
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== savedJson.value)

function normalize(a: AgentRow) {
  const c = JSON.parse(JSON.stringify(a.config || {}))
  c.model = { primary: null, fallbacks: [], retry: { enabled: true, times: 3, baseDelay: 5, maxDelay: 60 }, showThinking: false, ...(c.model || {}) }
  c.runtime = { kind: 'claudecode', env: '', maxSessions: 10, sandboxIsolation: false, workspace: '', maxTurns: 40, timeoutSeconds: 1800, temperature: null, ...(c.runtime || {}) }
  for (const k of ['hooks', 'mcpServers', 'cliPlugins', 'skills', 'memoryStores', 'subAgents', 'envVars', 'metadata']) c[k] = c[k] || []
  c.builtinTools = c.builtinTools || BUILTIN_TOOLS.map((t) => t.key)
  c.memoryEnabled = !!c.memoryEnabled
  c.prompt = c.prompt || ''
  return { name: a.name, description: a.description || '', avatar: a.avatar || '', groupName: a.groupName || '', config: c }
}

async function load() {
  const a = await agentApi.get(id.value)
  agent.value = a
  form.value = normalize(a)
  savedJson.value = JSON.stringify(form.value)
}
async function loadRefs() {
  const [m, e, h, p, s, st, ag, cr] = await Promise.all([refApi.models(), refApi.envs(), refApi.hooks(), refApi.plugins(), refApi.skills(), refApi.stores(),
    agentApi.options().catch(() => []), refApi.credentials()])
  models.value = m; envs.value = e; hooks.value = h; plugins.value = p; skills.value = s; stores.value = st; agents.value = ag; creds.value = cr
}

async function save() {
  if (!canEdit.value) return toast.warning('没有编辑权限')
  saving.value = true
  try {
    const r = await agentApi.save(id.value, form.value)
    toast.success(`已保存为 v${r.version}`)
    await load()
  } finally {
    saving.value = false
  }
}
async function reload() {
  if (dirty.value && !(await confirm({ title: '放弃未保存的修改？', message: '重新加载会丢失当前修改。', danger: true, okText: '放弃并刷新' }))) return
  await Promise.all([load(), loadRefs()])
}

function onKey(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key === 's') { e.preventDefault(); save() }
}
onMounted(() => { load(); loadRefs(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return await confirm({ title: '有未保存的修改', message: '离开后修改将丢失，确定离开？', danger: true, okText: '离开' })
})
watch(id, () => { load() })

// ---------------- 模型 ----------------
const modelLabel = computed(() => cfg.value.model?.primary || '')
const modelInfo = computed(() => {
  for (const p of models.value) for (const m of p.models) if (m.ref === modelLabel.value) return { ...m, providerName: p.providerName }
  return null
})
const envOptions = computed(() => envs.value.map((e) => ({ label: e.name, value: e.name, sub: e.description, tag: e.isSystem ? '系统' : undefined })))

// ---------------- 工具 ----------------
function toggleTool(k: string) {
  const arr: string[] = cfg.value.builtinTools
  const i = arr.indexOf(k)
  i >= 0 ? arr.splice(i, 1) : arr.push(k)
}
const mcpItems = computed(() => plugins.value.map((p) => ({ id: p.id, title: p.name, sub: p.endpoint, desc: p.description, tags: [p.type === 'mcp' ? 'MCP' : p.type === 'openapi' ? 'OpenAPI' : '云日志', `${p.toolCount ?? 0} 工具`] })))
function pluginOf(pid: number) { return plugins.value.find((p) => p.id === pid) }

// ---------------- Skills ----------------
function skillOf(sid: string) { return skills.value.find((s) => s.id === sid) }
function skillVersions(sid: string) {
  const s = skillOf(sid)
  return [{ label: `最新 (v${s?.currentVersion || '-'})`, value: '' }, ...((s?.versions || []) as string[]).map((v) => ({ label: `v${v}`, value: v }))]
}

// ---------------- 记忆库 ----------------
const storeItems = computed(() => stores.value.map((s) => ({ id: s.id, title: s.name, desc: s.description, tags: [s.provider || 'openviking', s.status || 'active'], mode: s.mode || 'compat' })))
function storeOf(sid: string) { return stores.value.find((s) => s.id === sid) }

// ---------------- SubAgents ----------------
const subItems = computed(() => agents.value.filter((a) => a.id !== id.value).map((a) => ({ id: a.id, title: a.name, tags: [a.identifier], desc: a.description, raw: a })))
function agentOf(aid: string) { return agents.value.find((a) => a.id === aid) }

// ---------------- 环境变量 ----------------
const plainVars = computed(() => cfg.value.envVars.filter((v: any) => v.type === 'plain'))
const secretVars = computed(() => cfg.value.envVars.filter((v: any) => v.type === 'secret'))
function addPlain() { cfg.value.envVars.push({ type: 'plain', name: '', value: '' }) }
function removeVar(v: any) { cfg.value.envVars.splice(cfg.value.envVars.indexOf(v), 1) }
function credName(cid: number) { return creds.value.find((c) => c.id === cid)?.name || `凭据 #${cid}` }
const envMenu = [
  { label: '普通变量 · 适用于非敏感配置参数', icon: Code, onClick: addPlain },
  { label: '密钥 · 适用于敏感凭证，保障数据安全', icon: KeyRound, onClick: () => (secretOpen.value = true) },
]

const summary = computed(() => {
  const c = cfg.value
  const parts = []
  if (c.skills?.length) parts.push(`${c.skills.length} Skills`)
  if (c.mcpServers?.length) parts.push(`${c.mcpServers.length} MCP`)
  if (c.subAgents?.length) parts.push(`${c.subAgents.length} Agents`)
  return parts
})

function hookName(hid: number) { return hooks.value.find((h) => h.id === hid)?.name || `Hook #${hid}` }
function toggleHook(hid: number) {
  const arr: number[] = cfg.value.hooks
  const i = arr.indexOf(hid)
  i >= 0 ? arr.splice(i, 1) : arr.push(hid)
}
</script>

<template>
  <div v-if="form" class="edit">
    <!-- 顶栏 -->
    <header class="e-head">
      <button class="icon-btn" @click="router.push('/agents')"><ArrowLeft /></button>
      <button class="av-btn" :disabled="!canEdit" @click="avatarOpen = true"><AgentAvatar :name="form.name" :avatar="form.avatar" :size="32" :radius="9" /></button>
      <div class="e-title">
        <div class="row" style="gap: 8px">
          <input v-if="editingName" v-model="form.name" class="input input-sm name-input" maxlength="60" autofocus @blur="editingName = false" @keydown.enter="editingName = false" />
          <span v-else class="e-name">{{ form.name }}</span>
          <span class="tag" :class="agent?.status === 'archived' ? '' : 'tag-green'">{{ agent?.status === 'archived' ? '已归档' : '可用' }}</span>
          <span class="tag tag-mono">v{{ agent?.version }}</span>
          <button class="btn btn-ghost btn-sm" @click="router.push(`/sessions?agentId=${id}`)"><MessageSquare /> 会话</button>
          <button v-if="canEdit" class="icon-btn sm" title="编辑名称" @click="editingName = true"><Pencil /></button>
        </div>
        <input v-if="editingDesc" v-model="form.description" class="input input-sm desc-input" placeholder="添加 Agent 描述" autofocus @blur="editingDesc = false" @keydown.enter="editingDesc = false" />
        <div v-else class="e-desc" @click="canEdit && (editingDesc = true)">{{ form.description || '添加 Agent 描述' }}</div>
      </div>
      <div class="spacer" />
      <span class="save-state" :class="{ dirty }">{{ dirty ? '未保存' : '已保存' }}</span>
      <button class="icon-btn" title="刷新" @click="reload"><RefreshCw /></button>
      <button class="icon-btn" title="分享" @click="shareOpen = true"><Share2 /></button>
      <button class="btn" @click="router.push(`/sessions?agentId=${id}`)"><MessageSquare /> 打开会话</button>
      <button class="btn btn-primary" :disabled="saving || !canEdit" @click="save"><Loader2 v-if="saving" class="spin" /><Save v-else /> 保存</button>
    </header>

    <div class="e-cols">
      <!-- 左：人设 -->
      <section class="col col-prompt">
        <div class="col-head">
          <Code2 class="ch-ico" /><span class="bold">人设与回复逻辑</span>
          <div class="spacer" />
          <div class="segmented">
            <button :class="{ active: promptMode === 'preview' }" @click="promptMode = 'preview'"><Eye /> 预览</button>
            <button :class="{ active: promptMode === 'edit' }" @click="promptMode = 'edit'"><Pencil /> 编辑</button>
          </div>
          <span class="tiny faint mono">{{ cfg.prompt.length.toLocaleString() }} chars</span>
        </div>
        <div class="col-body">
          <textarea v-if="promptMode === 'edit'" v-model="cfg.prompt" class="prompt-ta" spellcheck="false" placeholder="用 Markdown 编写 Agent 的人设、目标、工作流程、边界与输出格式…" />
          <div v-else class="prompt-preview" @dblclick="promptMode = 'edit'">
            <MarkdownView v-if="cfg.prompt" :source="cfg.prompt" compact />
            <div v-else class="empty small">还没有提示词，点击「编辑」开始编写</div>
          </div>
        </div>
      </section>

      <!-- 中：编排配置 -->
      <section class="col col-config">
        <div class="col-head">
          <div>
            <div class="bold">编排配置</div>
            <div class="tiny muted">模型 · 工具 · CLI · Skills · 记忆 · SubAgents</div>
          </div>
          <div class="spacer" />
          <span v-for="s in summary" :key="s" class="tag tag-mono">{{ s }}</span>
        </div>
        <div class="col-body config-body">
          <!-- 模型与运行 -->
          <ConfigSection title="模型与运行" :icon="Sparkles" tone="purple">
            <div class="sub-label">模型配置</div>
            <div class="model-card" @click="modelOpen = true">
              <div class="mc-ico"><Sparkles /></div>
              <div style="flex: 1; min-width: 0">
                <div class="bold mono ellipsis">{{ modelLabel || '未选择模型' }}</div>
                <div class="tiny muted">{{ modelInfo ? `${modelInfo.providerName} · 上下文 ${Math.round((modelInfo.contextWindow || 0) / 1000)}K` : modelLabel ? '模型不存在或已删除' : '未配置模型' }}</div>
              </div>
              <span v-if="cfg.model.fallbacks?.length" class="tag">备用 {{ cfg.model.fallbacks.length }}</span>
              <span class="tag tag-round">重试 {{ cfg.model.retry?.enabled ? cfg.model.retry.times : 0 }} 次</span>
              <SlidersHorizontal class="mc-set" />
            </div>
            <div class="sub-label" style="margin-top: 14px">运行环境配置</div>
            <div class="form-row">
              <div class="field">
                <label>运行时类型</label>
                <Select v-model="cfg.runtime.kind" :options="[{ label: 'claudecode', value: 'claudecode', sub: 'Claude Code 风格 Agent 循环' }, { label: 'cloudai', value: 'cloudai', sub: 'CloudAI 原生运行时' }]" />
              </div>
              <div class="field">
                <label>默认运行环境</label>
                <Select v-model="cfg.runtime.env" :options="envOptions" placeholder="选择运行环境" />
              </div>
            </div>
            <div class="form-row">
              <div class="field">
                <label>单实例最大会话数</label>
                <div class="row">
                  <input v-model.number="cfg.runtime.maxSessions" type="number" min="1" class="input" style="flex: 1" />
                  <div class="iso"><span>用户沙箱隔离</span><Switch v-model="cfg.runtime.sandboxIsolation" size="sm" /></div>
                </div>
              </div>
              <div class="field">
                <label>默认工作区 (持久化存储)</label>
                <Select v-model="cfg.runtime.workspace" :options="[{ label: '不使用工作区', value: '' }]" />
              </div>
            </div>
            <div class="row" style="justify-content: flex-end; margin-bottom: 10px">
              <button class="btn" @click="moreRuntime = !moreRuntime"><Settings2 /> 更多运行配置</button>
            </div>
            <div v-if="moreRuntime" class="form-row-3">
              <div class="field"><label>最大迭代次数</label><input v-model.number="cfg.runtime.maxTurns" type="number" min="1" max="200" class="input" /></div>
              <div class="field"><label>执行超时 (秒)</label><input v-model.number="cfg.runtime.timeoutSeconds" type="number" min="60" class="input" /></div>
              <div class="field"><label>Temperature</label><input v-model.number="cfg.runtime.temperature" type="number" step="0.1" min="0" max="2" class="input" placeholder="默认" /></div>
            </div>
            <div class="hooks-card">
              <Link2 class="hk-ico" /><span class="bold">Hooks</span>
              <span v-for="h in cfg.hooks" :key="h" class="tag tag-blue">{{ hookName(h) }}</span>
              <div class="spacer" />
              <button class="btn" @click="hooksOpen = true">配置 Hooks</button>
            </div>
          </ConfigSection>

          <!-- 工具与 MCP -->
          <ConfigSection title="工具与 MCP" :icon="Wrench" tone="orange">
            <div class="sub-label">内置工具</div>
            <div class="chips">
              <button v-for="t in BUILTIN_TOOLS" :key="t.key" class="chip" :class="{ on: cfg.builtinTools.includes(t.key) }" @click="toggleTool(t.key)">
                {{ t.label }} <span class="mono">{{ t.key }}</span>
              </button>
            </div>
            <div class="sub-head"><span>MCP SERVERS</span><span v-if="cfg.mcpServers.length" class="faint">{{ cfg.mcpServers.length }} 已添加</span><button class="icon-btn sm primary-ring" @click="mcpOpen = true"><Plus /></button></div>
            <div v-if="cfg.mcpServers.length" class="items">
              <div v-for="(m, i) in cfg.mcpServers" :key="m.pluginId" class="item">
                <Plug class="it-ico" />
                <div style="flex: 1; min-width: 0">
                  <div class="bold">{{ pluginOf(m.pluginId)?.name || m.name }}</div>
                  <div class="tiny muted mono ellipsis">{{ pluginOf(m.pluginId)?.endpoint || '插件已删除' }}</div>
                </div>
                <button class="icon-btn sm danger" @click="cfg.mcpServers.splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
            <div v-else class="empty-box">暂未添加 MCP Server</div>
            <div class="sub-head"><span>CLI 插件</span><span v-if="cfg.cliPlugins.length" class="faint">{{ cfg.cliPlugins.length }} 已添加</span><button class="icon-btn sm primary-ring" @click="cliOpen = true"><Plus /></button></div>
            <div v-if="cfg.cliPlugins.length" class="items">
              <div v-for="(c, i) in cfg.cliPlugins" :key="i" class="item">
                <Terminal class="it-ico" />
                <div style="flex: 1; min-width: 0">
                  <div class="bold">{{ c.type === 'lark' ? 'lark-cli' : c.type === 'gitlab' ? 'GitLab CLI' : 'cloudai-cli' }}</div>
                  <div class="tiny muted">{{ c.label }}</div>
                </div>
                <button class="icon-btn sm danger" @click="cfg.cliPlugins.splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
            <div v-else class="empty-box">暂未添加 CLI 插件</div>
          </ConfigSection>

          <!-- Skills -->
          <ConfigSection title="Skills" :icon="Sparkles" tone="blue" :count="cfg.skills.length" addable @add="skillOpen = true">
            <div v-if="cfg.skills.length" class="items">
              <div v-for="(s, i) in cfg.skills" :key="s.skillId" class="item tall">
                <div style="flex: 1; min-width: 0">
                  <div class="row" style="gap: 6px"><span class="bold">{{ s.name }}</span><span class="tag tag-round">{{ skillOf(s.skillId)?.scope || '工作空间' }}</span></div>
                  <div class="mono tiny muted">{{ s.name }}</div>
                  <div class="small muted clamp-2">{{ skillOf(s.skillId)?.description || 'Skill 已删除或不可用' }}</div>
                </div>
                <Select v-model="s.version" :options="skillVersions(s.skillId)" size="sm" style="width: 130px" />
                <button class="icon-btn sm danger" @click="cfg.skills.splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
            <div v-else class="empty-box">暂未添加 Skill</div>
          </ConfigSection>

          <!-- 记忆库 -->
          <ConfigSection title="记忆库" :icon="Database" tone="cyan" :count="cfg.memoryStores.length" addable @add="storeOpen = true">
            <div class="sub-head"><span>记忆库</span><span class="faint">{{ cfg.memoryStores.length }} 已关联</span></div>
            <div v-if="cfg.memoryStores.length" class="items">
              <div v-for="(s, i) in cfg.memoryStores" :key="s.storeId" class="item col-item">
                <div class="row" style="gap: 10px; width: 100%">
                  <div class="db-ico"><Database /></div>
                  <span class="bold ellipsis">{{ storeOf(s.storeId)?.name || '记忆库已删除' }}</span>
                  <span class="tag tag-round">{{ s.access === 'ro' ? '只读' : '读写' }}</span>
                  <span class="tag tag-round">{{ storeOf(s.storeId)?.status || 'active' }}</span>
                  <div class="spacer" />
                  <button class="icon-btn sm danger" @click="cfg.memoryStores.splice(i, 1)"><Trash2 /></button>
                </div>
                <div class="row" style="gap: 8px; width: 100%">
                  <Select v-model="s.access" :options="[{ label: '读写', value: 'rw' }, { label: '只读', value: 'ro' }]" style="width: 120px" />
                  <input v-model="s.instruction" class="input" placeholder="使用说明，例如：遇到项目背景问题时优先检索该记忆库" />
                </div>
              </div>
            </div>
            <div v-else class="empty-box">暂未关联记忆库</div>
          </ConfigSection>

          <!-- 记忆 -->
          <ConfigSection title="记忆" :icon="Brain" tone="green">
            <div class="toggle-card">
              <div>
                <div class="bold">启用记忆</div>
                <div class="tiny muted">开启后 Agent 可通过 remember 工具沉淀长期记忆，并在后续会话中自动加载</div>
              </div>
              <Switch v-model="cfg.memoryEnabled" />
            </div>
          </ConfigSection>

          <!-- SubAgents -->
          <ConfigSection title="SubAgents" :icon="ShieldCheck" tone="purple" :count="cfg.subAgents.length" addable @add="subOpen = true">
            <div class="sub-head"><span>已添加 SubAgents</span><span class="faint">{{ cfg.subAgents.length }} 已配置</span></div>
            <div v-if="cfg.subAgents.length" class="items">
              <div v-for="(s, i) in cfg.subAgents" :key="s.agentId" class="item">
                <AgentAvatar :name="agentOf(s.agentId)?.name || s.name" :avatar="agentOf(s.agentId)?.avatar" :size="30" :radius="15" :letters="2" />
                <div style="flex: 1; min-width: 0">
                  <div class="row" style="gap: 6px">
                    <span class="bold ellipsis">{{ agentOf(s.agentId)?.name || s.name }}</span>
                    <span class="tag tag-green tag-round"><span class="dot" /> 已配置</span>
                    <span class="tag tag-round">平台 Agent</span>
                  </div>
                  <input v-model="s.description" class="input input-sm" style="margin-top: 6px" placeholder="委派说明：什么情况下把任务交给这个 Agent" />
                </div>
                <button class="icon-btn sm danger" @click="cfg.subAgents.splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
            <div v-else class="empty-box" style="padding: 30px">暂未添加 SubAgent</div>
          </ConfigSection>

          <!-- 环境变量 -->
          <ConfigSection title="环境变量" :icon="KeyRound" tone="green" :count="cfg.envVars.length">
            <template #extra>
              <span @click.stop><DropdownMenu :items="envMenu"><button class="icon-btn sm primary-ring"><Plus /></button></DropdownMenu></span>
            </template>
            <template v-if="plainVars.length">
              <div class="sub-head"><Code class="sh-ico" /><span>普通变量 {{ plainVars.length }}</span></div>
              <div class="var-table">
                <div class="vt-head"><span>变量名</span><span>变量值</span><span /></div>
                <div v-for="(v, i) in plainVars" :key="i" class="vt-row">
                  <input v-model="v.name" class="input mono" placeholder="LOG_LEVEL" />
                  <input v-model="v.value" class="input mono" placeholder="例如 info" />
                  <button class="icon-btn sm danger" @click="removeVar(v)"><Trash2 /></button>
                </div>
              </div>
            </template>
            <template v-if="secretVars.length">
              <div class="sub-head"><KeyRound class="sh-ico" /><span>密钥 {{ secretVars.length }}</span></div>
              <div class="items">
                <div v-for="(v, i) in secretVars" :key="i" class="item">
                  <div class="key-ico"><KeyRound /></div>
                  <div style="flex: 1; min-width: 0">
                    <div class="row" style="gap: 6px"><span class="mono bold">{{ v.scope === 'all' ? credName(v.credentialId) : v.name }}</span><span class="tag tag-round">{{ v.scope === 'all' ? '全部字段' : '单个字段' }}</span></div>
                    <div class="tiny muted">{{ credName(v.credentialId) }}{{ v.field ? ` · ${v.field}` : '' }}</div>
                  </div>
                  <button class="icon-btn sm danger" @click="removeVar(v)"><Trash2 /></button>
                </div>
              </div>
            </template>
            <div v-if="!cfg.envVars.length" class="empty-box">
              <div class="bold" style="color: var(--text); margin-bottom: 4px">还没有环境变量</div>
              点击右上角添加普通变量或密钥。
            </div>
          </ConfigSection>

          <!-- Metadata -->
          <ConfigSection title="Metadata" :icon="Tags" tone="gray" :count="cfg.metadata.length" addable @add="cfg.metadata.push({ key: '', value: '' })">
            <div v-if="cfg.metadata.length" class="var-table">
              <div v-for="(m, i) in cfg.metadata" :key="i" class="vt-row">
                <input v-model="m.key" class="input mono" placeholder="key" />
                <input v-model="m.value" class="input mono" placeholder="value" />
                <button class="icon-btn sm danger" @click="cfg.metadata.splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
            <div v-else class="muted small">暂未配置 Metadata</div>
          </ConfigSection>
        </div>
      </section>

      <!-- 右：调试 -->
      <section class="col col-debug">
        <DebugChat :agent-id="id" :agent-name="form.name" :agent-avatar="form.avatar" :dirty="dirty" :models="models" :model-ref="cfg.model.primary" />
      </section>
    </div>

    <!-- 弹窗 -->
    <ModelConfigModal :open="modelOpen" :model="cfg.model" :options="models" @close="modelOpen = false" @save="(v) => (cfg.model = v)" />
    <SkillPickerModal :open="skillOpen" :skills="skills" :selected="cfg.skills" @close="skillOpen = false" @save="(v) => (cfg.skills = v)" />
    <ListPickerModal
      :open="mcpOpen" title="添加 MCP Server" subtitle="选择工具组或接入已注册的外部 MCP 服务" :items="mcpItems" :added-ids="cfg.mcpServers.map((m: any) => m.pluginId)"
      category-label="自定义 MCP" category-sub="接入已注册的 MCP 服务" create-text="新增 MCP 服务" create-to="/plugins/tools/new" search-placeholder="搜索插件名称或地址..." mono
      @close="mcpOpen = false" @refresh="loadRefs" @add="(it) => cfg.mcpServers.push({ pluginId: it.id, name: it.title })"
    />
    <ListPickerModal
      :open="storeOpen" title="添加记忆库" subtitle="一个 Agent 可以关联多个记忆库，运行时会作为 memory_store 资源挂载。" :items="storeItems" :added-ids="cfg.memoryStores.map((m: any) => m.storeId)"
      :modes="[{ label: '兼容模式', value: 'compat' }, { label: '原生模式', value: 'native' }]" create-text="新建记忆库" create-to="/memory" search-placeholder="搜索记忆库名称或描述"
      @close="storeOpen = false" @refresh="loadRefs" @add="(it) => cfg.memoryStores.push({ storeId: it.id, access: 'rw', instruction: '' })"
    >
      <template #icon><div class="db-ico"><Database /></div></template>
    </ListPickerModal>
    <ListPickerModal
      :open="subOpen" title="添加 SubAgent" subtitle="从当前空间的 Agent 中选择（空间之间相互隔离，不能调用其他空间的 Agent）。" :items="subItems" :added-ids="cfg.subAgents.map((m: any) => m.agentId)"
      category-label="空间 Agent" search-placeholder="搜索 Agent 名称或描述"
      @close="subOpen = false" @refresh="loadRefs" @add="(it) => cfg.subAgents.push({ agentId: it.id, name: it.title, description: it.desc || '' })"
    >
      <template #icon="{ item }"><AgentAvatar :name="item.title" :avatar="item.raw?.avatar" :size="32" :radius="16" :letters="2" /></template>
    </ListPickerModal>
    <CliPluginModal :open="cliOpen" @close="cliOpen = false" @add="(v) => { cfg.cliPlugins.push(v); loadRefs() }" />
    <SecretEnvModal :open="secretOpen" :used="secretVars" @close="secretOpen = false" @add="(v) => { cfg.envVars.push(v); loadRefs() }" />
    <ShareAgentModal :agent="shareOpen ? agent : null" @close="shareOpen = false" />

    <Modal :open="hooksOpen" title="配置 Hooks" subtitle="选择要绑定到该 Agent 的 Hook（在 Plugins → Hooks 中维护）。" width="480px" @close="hooksOpen = false">
      <div v-for="h in hooks" :key="h.id" class="hook-opt" :class="{ on: cfg.hooks.includes(h.id) }" @click="toggleHook(h.id)">
        <span class="cb"><Check v-if="cfg.hooks.includes(h.id)" /></span>
        <div><div class="bold">{{ h.name }}</div><div class="tiny muted">{{ h.description }}</div></div>
      </div>
      <div v-if="!hooks.length" class="empty-box">还没有 Hook，<RouterLink to="/plugins/hooks">去创建</RouterLink></div>
      <template #footer><button class="btn btn-primary" @click="hooksOpen = false">完成</button></template>
    </Modal>

    <Modal :open="avatarOpen" title="更换头像" width="400px" @close="avatarOpen = false">
      <div class="presets">
        <button v-for="(p, i) in PRESET_AVATARS" :key="i" class="preset" :class="{ on: form.avatar === `preset:${i}` }" @click="form.avatar = `preset:${i}`; avatarOpen = false">
          <AgentAvatar name="" :avatar="`preset:${i}`" :size="40" :radius="10" />
        </button>
        <button class="preset" :class="{ on: !form.avatar }" @click="form.avatar = ''; avatarOpen = false">
          <AgentAvatar :name="form.name" avatar="" :size="40" :radius="10" />
        </button>
      </div>
      <p class="hint" style="margin-top: 10px"><Image style="width: 12px" /> 也可在创建时上传图片作为头像</p>
    </Modal>
  </div>
  <div v-else class="loading"><Loader2 class="spin" /></div>
</template>

<style scoped>
.edit { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg); }
.loading { height: 100%; display: flex; align-items: center; justify-content: center; color: var(--text-3); }
.e-head { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-bottom: 1px solid var(--border); background: var(--bg-elev); }
.av-btn { border: none; background: transparent; padding: 0; cursor: pointer; display: flex; }
.e-title { min-width: 0; }
.e-name { font-weight: 650; font-size: 15px; }
.name-input { width: 280px; }
.e-desc { font-size: 12px; color: var(--text-3); cursor: text; margin-top: 2px; }
.desc-input { width: 420px; margin-top: 2px; }
.save-state { font-size: 12px; color: var(--text-4); }
.save-state.dirty { color: var(--orange-text); font-weight: 600; }
.e-cols { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(300px, 30%) minmax(420px, 1fr) minmax(320px, 28%); }
.col { min-width: 0; min-height: 0; display: flex; flex-direction: column; border-right: 1px solid var(--border); }
.col:last-child { border-right: none; }
.col-debug { background: var(--bg-elev); }
.col-head { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--border); background: var(--bg-elev); min-height: 52px; }
.ch-ico { width: 15px; height: 15px; color: var(--text-3); }
.col-body { flex: 1; min-height: 0; overflow: auto; }
.prompt-ta { width: 100%; height: 100%; border: none; outline: none; resize: none; padding: 16px; font-family: var(--mono); font-size: 12.5px; line-height: 1.7; background: var(--bg-elev); color: var(--text); }
.prompt-preview { padding: 16px 18px 40px; }
.prompt-preview :deep(pre) { background: var(--bg-muted); color: var(--text-2); border: 1px solid var(--border); }
.config-body { padding: 14px; }
.sub-label { font-size: 12.5px; font-weight: 600; color: var(--text-2); margin-bottom: 8px; }
.model-card { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; cursor: pointer; transition: border-color .15s; }
.model-card:hover { border-color: var(--purple); }
.mc-ico { width: 32px; height: 32px; border-radius: 9px; background: var(--purple-soft); color: var(--purple-text); display: flex; align-items: center; justify-content: center; }
.mc-ico svg { width: 16px; height: 16px; }
.mc-set { width: 15px; height: 15px; color: var(--text-3); }
.iso { display: flex; align-items: center; gap: 8px; height: 34px; padding: 0 10px; border: 1px solid var(--border); border-radius: var(--radius); font-size: 12.5px; white-space: nowrap; }
.hooks-card { display: flex; align-items: center; gap: 8px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; flex-wrap: wrap; }
.hk-ico { width: 15px; height: 15px; color: var(--text-3); }
.chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 10px; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 4px; }
.chip { display: inline-flex; align-items: center; gap: 5px; height: 26px; padding: 0 9px; border-radius: 7px; border: 1px solid var(--border); background: var(--bg-muted); color: var(--text-4); cursor: pointer; font-size: 12px; text-decoration: line-through; }
.chip .mono { font-size: 10.5px; opacity: .7; }
.chip.on { background: var(--bg-elev); color: var(--text); text-decoration: none; border-color: var(--border-strong); box-shadow: var(--shadow-sm); }
.sub-head { display: flex; align-items: center; gap: 8px; font-size: 11.5px; color: var(--text-3); margin: 14px 0 8px; font-weight: 600; letter-spacing: .02em; }
.sub-head > span:first-of-type { flex: 1; }
.sh-ico { width: 13px; height: 13px; }
.items { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-bottom: 1px solid var(--divider); }
.item:last-child { border-bottom: none; }
.item.tall { align-items: flex-start; }
.item.col-item { flex-direction: column; align-items: stretch; }
.it-ico { width: 16px; height: 16px; color: var(--text-3); flex-shrink: 0; }
.db-ico, .key-ico { width: 32px; height: 32px; border-radius: 9px; background: var(--cyan-soft); color: var(--cyan-text); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.key-ico { background: var(--bg-muted); color: var(--text-2); }
.db-ico svg, .key-ico svg { width: 16px; height: 16px; }
.toggle-card { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; border: 1px solid var(--border); border-radius: 12px; }
.var-table { border: 1px solid var(--border); border-radius: 12px; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.vt-head, .vt-row { display: grid; grid-template-columns: 1fr 1fr 28px; gap: 8px; align-items: center; }
.vt-head { font-size: 12px; color: var(--text-3); }
.hook-opt { display: flex; gap: 10px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; cursor: pointer; margin-bottom: 8px; }
.hook-opt.on { border-color: var(--primary); background: var(--primary-soft); }
.cb { width: 16px; height: 16px; border-radius: 4px; border: 1.5px solid var(--border-strong); display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 2px; }
.hook-opt.on .cb { background: var(--primary); border-color: var(--primary); color: #fff; }
.cb svg { width: 11px; height: 11px; }
.presets { display: flex; flex-wrap: wrap; gap: 10px; }
.preset { padding: 3px; border-radius: 12px; border: 2px solid transparent; background: transparent; cursor: pointer; display: flex; }
.preset.on { border-color: var(--primary); }
</style>
