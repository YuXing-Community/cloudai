<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  MessagesSquare, Plus, RefreshCw, ArrowDownUp, Search, ChevronsUpDown, Check, AtSign, Copy, Share2, MessageSquare,
  Package, Activity, Info, MoreHorizontal, Trash2, Square, Folder, Box, Send, CalendarClock, Globe, Bug, Webhook, Users, ChevronDown,
} from 'lucide-vue-next'
import { sessionApi, optionApi, SOURCE_LABEL, STATUS_LABEL, type SessionRow, type AgentOption, type ModelOption, type EnvOption } from '@/api/session'
import { http } from '@/api/http'
import { useAuth } from '@/stores/auth'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import Popover from '@/components/ui/Popover.vue'
import Select from '@/components/ui/Select.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Empty from '@/components/ui/Empty.vue'
import MessageList from '@/components/chat/MessageList.vue'
import Composer from '@/components/chat/Composer.vue'
import TraceView from '@/components/chat/TraceView.vue'
import ArtifactsPanel from '@/components/chat/ArtifactsPanel.vue'
import SessionInfo from '@/components/chat/SessionInfo.vue'
import { useSessionStream } from '@/components/chat/useSessionStream'
import { ago, copyText, debounce } from '@/utils/format'
import { confirm, toast } from '@/components/ui/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const sessionId = computed(() => (route.params.id as string) || null)

// ------------------------------------------------------------ 选项
const agents = ref<AgentOption[]>([])
const models = ref<ModelOption[]>([])
const envs = ref<EnvOption[]>([])
const agentKey = computed(() => `cloudai.session.agent.${auth.spaceId}`)
const agentId = ref<string>('')
const agent = computed(() => agents.value.find((a) => a.id === agentId.value))
const agentDetail = ref<any>(null)

// ------------------------------------------------------------ 会话列表
const list = ref<SessionRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 30
const mine = ref(0)
const q = ref('')
const fVersion = ref<any>('')
const fSource = ref('')
const fStatus = ref('')
const listLoading = ref(false)

async function loadList() {
  if (!agentId.value) { list.value = []; total.value = 0; return }
  listLoading.value = true
  try {
    const r = await sessionApi.list({ agentId: agentId.value, mine: mine.value, q: q.value, version: fVersion.value, source: fSource.value, status: fStatus.value, page: page.value, size })
    list.value = r.records
    total.value = r.total
  } finally {
    listLoading.value = false
  }
}
const debouncedLoad = debounce(() => { page.value = 1; loadList() }, 300)
watch(q, debouncedLoad)
watch([mine, fVersion, fSource, fStatus], () => { page.value = 1; loadList() })

const versionOptions = computed(() => {
  const v = agent.value?.version || 0
  return [{ label: '全部版本', value: '' }, ...Array.from({ length: v }, (_, i) => ({ label: `v${v - i}`, value: v - i }))]
})
const sourceOptions = [{ label: '全部来源', value: '' }, ...Object.entries(SOURCE_LABEL).map(([k, v]) => ({ label: v, value: k }))]
const statusOptions = [{ label: '全部状态', value: '' }, ...Object.entries(STATUS_LABEL).map(([k, v]) => ({ label: v, value: k }))]

function sourceIcon(s: string) {
  return { feishu: Send, schedule: CalendarClock, web: Globe, debug: Bug, webhook: Webhook, team: Users, share: Share2 }[s] || MessageSquare
}

// ------------------------------------------------------------ Agent 切换
const agOpen = ref(false)
const agBtn = ref<HTMLElement | null>(null)
const agQ = ref('')
const agFiltered = computed(() => agents.value.filter((a) => !agQ.value || `${a.name} ${a.identifier}`.toLowerCase().includes(agQ.value.toLowerCase())))

async function pickAgent(id: string, keepRoute = false) {
  agOpen.value = false
  if (!id) return
  agentId.value = id
  localStorage.setItem(agentKey.value, id)
  page.value = 1
  fVersion.value = ''
  agentDetail.value = null
  loadList()
  if (!keepRoute && sessionId.value) router.push('/sessions')
  try {
    agentDetail.value = await http.get(`/agents/${id}`, undefined, { silent: true })
  } catch { agentDetail.value = null }
  applyDefaults()
}

// ------------------------------------------------------------ 新会话
const newModel = ref<string>('')
const newEnv = ref<string>('')
const creating = ref(false)
function defaultModelRef() {
  const p = agentDetail.value?.config?.model?.primary
  if (p) return p
  for (const g of models.value) for (const m of g.models) if (m.isDefault) return m.ref
  return models.value[0]?.models[0]?.ref || ''
}
function applyDefaults() {
  newModel.value = defaultModelRef()
  const env = agentDetail.value?.config?.runtime?.env
  newEnv.value = env || envs.value.find((e) => e.isDefault)?.name || envs.value[0]?.name || ''
}
const envOptions = computed(() => envs.value.map((e) => ({ label: e.name, value: e.name, sub: e.description, tag: e.isSystem ? '系统' : undefined })))
const runtimeKind = computed(() => agentDetail.value?.config?.runtime?.kind || 'claudecode')

async function startNew(text: string, fileIds: string[]) {
  if (!agentId.value) return toast.warning('请先选择智能体')
  creating.value = true
  try {
    const s = await sessionApi.create({ agentId: agentId.value, modelRef: newModel.value, runtimeEnv: newEnv.value })
    await sessionApi.send(s.id, text, fileIds)
    await router.push(`/sessions/${s.id}`)
    loadList()
  } finally {
    creating.value = false
  }
}

// ------------------------------------------------------------ 会话详情
const tab = ref<'chat' | 'artifacts' | 'trace' | 'info'>('chat')
const { events, session, connected, reconnect } = useSessionStream(sessionId, sessionApi.stream)
const running = computed(() => session.value?.status === 'running' || !!session.value?.running && session.value?.status === 'running')
const curModel = ref('')
watch(() => session.value?.modelRef, (v) => { if (v) curModel.value = v })
const focusArtifact = ref<string | null>(null)
const refreshKey = ref(0)
watch(() => session.value?.status, (s, old) => {
  if (old === 'running' && s !== 'running') { refreshKey.value++; loadList() }
})
watch(sessionId, () => { tab.value = 'chat'; focusArtifact.value = null })

const sessionTitle = computed(() => session.value?.title || list.value.find((s) => s.id === sessionId.value)?.title || '')
const displayId = computed(() => {
  const s = session.value
  if (!s) return ''
  if (s.source === 'feishu' && s.sourceRef) return `feishu:chat:${s.sourceRef}`
  return s.title
})

async function sendInSession(text: string, fileIds: string[]) {
  if (!sessionId.value) return
  await sessionApi.send(sessionId.value, text, fileIds, curModel.value !== session.value?.modelRef ? curModel.value : undefined)
}
async function stop() {
  if (sessionId.value) {
    await sessionApi.stop(sessionId.value)
    toast.info('已发送停止指令')
  }
}
async function removeSession() {
  if (!sessionId.value) return
  if (!(await confirm({ title: '删除会话', message: '删除后会话事件记录不可恢复（用量统计保留）。', danger: true, okText: '删除' }))) return
  await sessionApi.remove(sessionId.value)
  toast.success('已删除')
  router.push('/sessions')
  loadList()
}
function openArtifact(fid: string) {
  focusArtifact.value = fid
  tab.value = 'artifacts'
}
function copyId() {
  copyText(sessionId.value || '')
  toast.success('会话 ID 已复制')
}
function share() {
  copyText(`${location.origin}/sessions/${sessionId.value}`)
  toast.success('会话链接已复制')
}
const moreItems = computed(() => [
  ...(running.value ? [{ label: '停止执行', icon: Square, onClick: stop }] : []),
  { label: '复制会话 ID', icon: Copy, onClick: copyId },
  { divider: true, label: '' },
  { label: '删除会话', icon: Trash2, danger: true, onClick: removeSession },
])

// ------------------------------------------------------------ 初始化
let timer: any
onMounted(async () => {
  const [a, m, e] = await Promise.all([optionApi.agents(), optionApi.models(), optionApi.envs()])
  agents.value = a
  models.value = m
  envs.value = e
  let id = (route.query.agentId as string) || ''
  if (!id && sessionId.value) {
    try { id = (await sessionApi.get(sessionId.value)).agentId } catch { /* ignore */ }
  }
  if (!id) id = localStorage.getItem(agentKey.value) || ''
  if (!agents.value.find((x) => x.id === id)) id = agents.value[0]?.id || ''
  if (id) await pickAgent(id, true)
  // 列表里的执行状态定时刷新
  timer = setInterval(() => { if (list.value.some((s) => s.status === 'running')) loadList() }, 8000)
})
onBeforeUnmount(() => clearInterval(timer))

watch(sessionId, async (id) => {
  if (!id) return
  const inList = list.value.find((s) => s.id === id)
  if (!inList) {
    try {
      const s = await sessionApi.get(id)
      if (s.agentId !== agentId.value && agents.value.find((a) => a.id === s.agentId)) pickAgent(s.agentId, true)
    } catch { /* 404 已提示 */ }
  }
})
</script>

<template>
  <div class="sess">
    <!-- ============ 左侧：会话列表 ============ -->
    <aside class="side">
      <div class="side-label">智能体</div>
      <button ref="agBtn" class="agent-pick" @click="agOpen = !agOpen">
        <AgentAvatar v-if="agent" :name="agent.name" :avatar="agent.avatar" :size="26" :radius="8" />
        <span class="ellipsis ap-name">{{ agent?.name || '选择智能体' }}</span>
        <ChevronDown class="ap-arrow" />
      </button>
      <Popover :open="agOpen" :anchor="agBtn" :min-width="280" @close="agOpen = false">
        <div class="pop-search"><Search /><input v-model="agQ" placeholder="搜索智能体…" /></div>
        <div class="pop-list">
          <div v-for="a in agFiltered" :key="a.id" class="pop-item" :class="{ active: a.id === agentId }" @click="pickAgent(a.id)">
            <AgentAvatar :name="a.name" :avatar="a.avatar" :size="22" :radius="6" />
            <div class="pi-main">
              <div class="ellipsis">{{ a.name }}</div>
              <div class="pi-sub ellipsis">{{ a.identifier }} · v{{ a.version }}</div>
            </div>
            <Check v-if="a.id === agentId" class="pi-check" />
          </div>
          <div v-if="!agFiltered.length" class="pop-empty">暂无智能体，<RouterLink to="/agents">去创建</RouterLink></div>
        </div>
      </Popover>

      <div class="list-head">
        <span class="lh-title"><MessagesSquare /> 会话</span>
        <div class="segmented mini blue">
          <button :class="{ active: mine === 0 }" @click="mine = 0">全部</button>
          <button :class="{ active: mine === 1 }" @click="mine = 1">我的</button>
        </div>
        <button class="icon-btn sm" title="刷新" @click="loadList"><RefreshCw :class="{ spin: listLoading }" /></button>
      </div>
      <button class="new-btn" @click="router.push('/sessions')"><Plus /> 新建会话</button>
      <div class="side-search"><Search /><input v-model="q" placeholder="搜索会话 ID、飞书群聊 ID…" /></div>
      <div class="filters">
        <Select v-model="fVersion" :options="versionOptions" size="sm" bare placeholder="版本" :min-width="120">
          <template #trigger="{ current }"><span class="f-txt">{{ fVersion ? current?.label : '版本' }}</span></template>
        </Select>
        <Select v-model="fSource" :options="sourceOptions" size="sm" bare :min-width="120">
          <template #trigger="{ current }"><span class="f-txt">{{ fSource ? current?.label : '来源' }}</span></template>
        </Select>
        <Select v-model="fStatus" :options="statusOptions" size="sm" bare :min-width="120">
          <template #trigger="{ current }"><span class="f-txt">{{ fStatus ? current?.label : '状态' }}</span></template>
        </Select>
      </div>
      <div class="s-list">
        <RouterLink
          v-for="s in list"
          :key="s.id"
          :to="`/sessions/${s.id}`"
          class="s-item"
          :class="{ active: s.id === sessionId }"
          :title="s.title"
        >
          <span class="s-dot" :class="s.status" />
          <component :is="sourceIcon(s.source)" class="s-src" :class="s.source" />
          <span class="s-title ellipsis">{{ s.source === 'feishu' && s.sourceRef ? `飞书会话 · ${s.sourceRef}` : s.title }}</span>
          <span class="s-time">{{ ago(s.updatedAt) }}</span>
        </RouterLink>
        <div v-if="!list.length && !listLoading" class="s-empty">暂无会话</div>
      </div>
      <div class="side-foot">
        <button class="link-btn" :disabled="page <= 1" @click="page--; loadList()">上一页</button>
        <span class="muted small">{{ page }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
        <button class="link-btn" :disabled="page * size >= total" @click="page++; loadList()">下一页</button>
      </div>
    </aside>

    <!-- ============ 右侧：新会话 / 会话详情 ============ -->
    <section class="main">
      <div v-if="!sessionId" class="hero">
        <div class="hero-inner">
          <h1 class="hero-title">我们接下来做点什么？</h1>
          <div v-if="agent" class="agent-chip">
            <AtSign class="ac-at" />
            <AgentAvatar :name="agent.name" :avatar="agent.avatar" :size="22" :radius="6" />
            <div class="ac-main">
              <div class="ac-name ellipsis">{{ agent.name }}</div>
              <div class="ac-sub">{{ agent.identifier }} · v{{ agent.version }}</div>
            </div>
            <button class="icon-btn sm" @click="agOpen = true"><ChevronDown /></button>
          </div>
          <Empty v-else title="还没有智能体" desc="先创建一个 Agent，再开始会话">
            <RouterLink to="/agents" class="btn btn-primary" style="margin-top: 8px">去创建智能体</RouterLink>
          </Empty>
          <template v-if="agent">
            <Composer v-model:model-ref="newModel" :models="models" :disabled="creating" :running="false" @send="startNew" />
            <div class="hero-tools">
              <span class="ht-item"><Folder /> 不使用工作区</span>
              <span class="ht-item"><span class="cc-dot" /> {{ runtimeKind }}</span>
              <Select v-model="newEnv" :options="envOptions" size="sm" bare :min-width="220">
                <template #trigger="{ current }">
                  <span class="ht-item"><Box /> {{ current?.label || '运行环境' }} <span v-if="current?.tag" class="tag tag-purple">{{ current.tag }}</span></span>
                </template>
              </Select>
            </div>
          </template>
        </div>
      </div>

      <template v-else>
        <header class="d-head">
          <component :is="sourceIcon(session?.source || 'web')" class="d-src" :class="session?.source" />
          <span class="d-title ellipsis">{{ displayId || sessionTitle }}</span>
          <button class="icon-btn sm" title="复制 ID" @click="copyId"><Copy /></button>
          <button class="icon-btn sm" title="分享" @click="share"><Share2 /></button>
          <div class="spacer" />
          <div class="segmented">
            <button :class="{ active: tab === 'chat' }" @click="tab = 'chat'"><MessageSquare /> 对话</button>
            <button :class="{ active: tab === 'artifacts' }" @click="tab = 'artifacts'"><Package /> 产物</button>
            <button :class="{ active: tab === 'trace' }" @click="tab = 'trace'"><Activity /> 轨迹</button>
            <button :class="{ active: tab === 'info' }" @click="tab = 'info'"><Info /> 信息</button>
          </div>
          <div class="spacer" />
          <span v-if="connected" class="tag tag-green tag-round"><span class="dot" /> 实时</span>
          <span v-else class="tag tag-round">离线</span>
          <button class="icon-btn" title="刷新" @click="reconnect(); refreshKey++"><RefreshCw /></button>
          <DropdownMenu :items="moreItems"><button class="icon-btn"><MoreHorizontal /></button></DropdownMenu>
        </header>

        <div class="d-body">
          <div v-show="tab === 'chat'" class="chat">
            <MessageList :events="events" :running="running" :agent-name="agent?.name" :agent-avatar="agent?.avatar" @stop="stop" @open-artifact="openArtifact">
              <template #empty><div class="chat-empty">发送第一条消息开始对话</div></template>
            </MessageList>
            <div class="chat-input">
              <Composer v-model:model-ref="curModel" :models="models" :running="running" :session-id="sessionId" :min-rows="1" placeholder="继续对话…（Enter 发送，Shift+Enter 换行）" @send="sendInSession" @stop="stop" />
            </div>
          </div>
          <ArtifactsPanel v-if="tab === 'artifacts'" :session-id="sessionId" :focus-id="focusArtifact" :refresh-key="refreshKey" />
          <div v-if="tab === 'trace'" class="scroll"><TraceView :events="events" /></div>
          <div v-if="tab === 'info'" class="scroll">
            <SessionInfo :session-id="sessionId" :title="displayId || sessionTitle" :refresh-key="refreshKey" @renamed="(t) => { if (session) session.title = t; loadList() }" />
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.sess { display: flex; height: 100%; min-height: 0; }
.side { width: 232px; flex-shrink: 0; border-right: 1px solid var(--border); background: var(--bg-elev); display: flex; flex-direction: column; padding: 10px 8px 6px; }
.side-label { font-size: 11.5px; color: var(--text-3); padding: 0 4px 6px; }
.agent-pick { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 10px; border: 1px solid var(--primary-soft-2); background: var(--primary-soft); cursor: pointer; text-align: left; }
.agent-pick:hover { filter: brightness(.98); }
.ap-name { flex: 1; font-weight: 600; font-size: 13px; }
.ap-arrow { width: 14px; height: 14px; color: var(--text-3); }
.pop-search { display: flex; align-items: center; gap: 6px; padding: 6px 8px 8px; border-bottom: 1px solid var(--divider); margin-bottom: 4px; }
.pop-search svg { width: 14px; height: 14px; color: var(--text-4); }
.pop-search input { flex: 1; border: none; outline: none; background: transparent; font-size: 13px; }
.pop-list { max-height: 360px; overflow: auto; }
.pop-item { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; }
.pop-item:hover { background: var(--bg-hover); }
.pop-item.active { background: var(--primary-soft); }
.pi-main { flex: 1; min-width: 0; font-size: 13px; }
.pi-sub { font-size: 11px; color: var(--text-3); font-family: var(--mono); }
.pi-check { width: 14px; height: 14px; color: var(--primary); }
.pop-empty { padding: 14px; text-align: center; color: var(--text-4); font-size: 12.5px; }
.list-head { display: flex; align-items: center; gap: 6px; margin: 12px 0 4px; padding: 0 2px; }
.lh-title { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; color: var(--text-3); flex: 1; }
.lh-title svg { width: 13px; height: 13px; }
.segmented.mini { padding: 2px; }
.segmented.mini button { height: 20px; padding: 0 7px; font-size: 11.5px; }
.new-btn { display: flex; align-items: center; gap: 6px; padding: 6px 6px; border: none; background: transparent; cursor: pointer; font-size: 12.5px; color: var(--text-2); border-radius: 8px; }
.new-btn:hover { background: var(--bg-hover); color: var(--text); }
.new-btn svg { width: 14px; height: 14px; }
.side-search { display: flex; align-items: center; gap: 6px; height: 28px; padding: 0 8px; margin: 4px 0 6px; border-radius: 8px; background: var(--bg-muted); }
.side-search svg { width: 13px; height: 13px; color: var(--text-4); }
.side-search input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 12px; }
.filters { display: flex; justify-content: space-between; margin-bottom: 6px; }
.f-txt { font-size: 12px; color: var(--text-2); }
.s-list { flex: 1; overflow-y: auto; margin: 0 -4px; padding: 0 4px; }
.s-item { display: flex; align-items: center; gap: 7px; height: 30px; padding: 0 6px; border-radius: 8px; color: var(--text-2); font-size: 12.5px; text-decoration: none !important; }
.s-item:hover { background: var(--bg-hover); color: var(--text); }
.s-item.active { background: var(--bg-active); color: var(--text); font-weight: 600; }
.s-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--primary); flex-shrink: 0; opacity: .85; }
.s-dot.running { animation: pulse 1.2s infinite; }
.s-dot.failed { background: var(--red); }
.s-dot.stopped { background: var(--orange); }
.s-dot.idle { background: var(--text-4); }
.s-src { width: 13px; height: 13px; color: var(--text-3); flex-shrink: 0; }
.s-src.feishu { color: #3370ff; }
.s-src.schedule { color: var(--text-3); }
.s-title { flex: 1; }
.s-time { font-size: 11px; color: var(--text-4); flex-shrink: 0; }
.s-empty { padding: 24px 8px; text-align: center; color: var(--text-4); font-size: 12px; }
.side-foot { display: flex; align-items: center; justify-content: space-between; padding: 8px 4px 2px; border-top: 1px solid var(--divider); margin-top: 6px; font-size: 12px; }
.side-foot .link-btn { color: var(--text-2); font-size: 12px; }
.side-foot .link-btn:disabled { color: var(--text-4); cursor: not-allowed; text-decoration: none; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; background: var(--bg); }
.hero { flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px; background: radial-gradient(800px 360px at 50% 0%, var(--primary-soft) 0%, transparent 70%); }
.hero-inner { width: 100%; max-width: 640px; display: flex; flex-direction: column; align-items: center; gap: 16px; margin-top: -60px; }
.hero-title { font-size: 26px; font-weight: 700; letter-spacing: -.02em; margin-bottom: 6px; }
.agent-chip { align-self: flex-start; display: inline-flex; align-items: center; gap: 8px; padding: 5px 6px 5px 10px; border-radius: 12px; border: 1px solid var(--border); background: var(--bg-elev); box-shadow: var(--shadow-sm); max-width: 100%; }
.ac-at { width: 15px; height: 15px; color: var(--primary); }
.ac-main { min-width: 0; line-height: 1.3; }
.ac-name { font-weight: 600; font-size: 13px; }
.ac-sub { font-size: 11px; color: var(--text-3); font-family: var(--mono); }
.hero-inner :deep(.composer) { width: 100%; }
.hero-tools { align-self: stretch; display: flex; align-items: center; gap: 4px; padding: 6px 10px; margin: -10px 12px 0; border: 1px solid var(--border); border-top: none; border-radius: 0 0 12px 12px; background: var(--bg-muted); }
.ht-item { display: inline-flex; align-items: center; gap: 5px; padding: 3px 8px; border-radius: 7px; font-size: 12px; color: var(--text-2); font-weight: 500; }
.ht-item svg { width: 13px; height: 13px; color: var(--text-3); }
.cc-dot { width: 10px; height: 10px; border-radius: 50%; background: radial-gradient(circle, #f97316 30%, #ea580c 70%); }

.d-head { height: 46px; flex-shrink: 0; display: flex; align-items: center; gap: 6px; padding: 0 12px 0 16px; border-bottom: 1px solid var(--border); background: var(--bg-elev); }
.d-src { width: 15px; height: 15px; color: var(--text-3); }
.d-src.feishu { color: #3370ff; }
.d-title { font-family: var(--mono); font-weight: 600; font-size: 13px; max-width: 360px; }
.d-body { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.chat { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.chat-input { padding: 8px 24px 16px; max-width: 908px; width: 100%; margin: 0 auto; }
.chat-empty { text-align: center; color: var(--text-4); padding: 60px 0; }
.scroll { flex: 1; overflow: auto; }
</style>
