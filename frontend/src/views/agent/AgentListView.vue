<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Home, Store, RefreshCw, Upload, Plus, Star, User, LayoutList, Folder, ArrowDownUp, Copy, MessageSquare, Layers,
  Pencil, CopyPlus, Download, UserCog, Share2, Archive, Trash2, Sparkles, Plug, Database, Bot, KeyRound, Terminal, ArchiveRestore,
} from 'lucide-vue-next'
import { agentApi, type AgentRow } from '@/api/agent'
import { useAuth } from '@/stores/auth'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import Select from '@/components/ui/Select.vue'
import SearchInput from '@/components/ui/SearchInput.vue'
import Switch from '@/components/ui/Switch.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Pagination from '@/components/ui/Pagination.vue'
import Empty from '@/components/ui/Empty.vue'
import CreateAgentModal from '@/components/agent/CreateAgentModal.vue'
import OwnerModal from '@/components/agent/OwnerModal.vue'
import ShareAgentModal from '@/components/agent/ShareAgentModal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { copyText, debounce, fmtTime, shortId } from '@/utils/format'

const router = useRouter()
const auth = useAuth()

const view = ref<'space' | 'market'>('space')
const tab = ref<'favorite' | 'mine' | 'all'>('mine')
const group = ref('')
const sort = ref('updated')
const q = ref('')
const archived = ref(false)
const page = ref(1)
const size = 20
const rows = ref<AgentRow[]>([])
const total = ref(0)
const counts = ref<Record<string, number>>({ favorite: 0, mine: 0, all: 0 })
const groups = ref<string[]>([])
const loading = ref(false)

const createOpen = ref(false)
const ownerAgent = ref<AgentRow | null>(null)
const shareAgent = ref<AgentRow | null>(null)
const importEl = ref<HTMLInputElement | null>(null)

const groupOptions = computed(() => [
  { label: '全部分组', value: '' },
  { label: '未分组', value: '__none__' },
  ...groups.value.map((g) => ({ label: g, value: g })),
])
const sortOptions = [
  { label: '最近更新', value: 'updated' },
  { label: '最近创建', value: 'created' },
  { label: '名称', value: 'name' },
]

async function load() {
  loading.value = true
  try {
    const r = await agentApi.list({ tab: view.value === 'market' ? 'all' : tab.value, group: group.value, q: q.value, archived: archived.value, sort: sort.value, page: page.value, size })
    rows.value = r.page.records
    total.value = r.page.total
    counts.value = r.counts
  } finally {
    loading.value = false
  }
}
async function loadGroups() {
  groups.value = await agentApi.groups()
}
const debounced = debounce(() => { page.value = 1; load() }, 300)
watch(q, debounced)
watch([tab, group, archived, sort, view], () => { page.value = 1; load() })
onMounted(() => { load(); loadGroups() })

async function onImport(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (!f) return
  const r = await agentApi.import(f)
  toast.success(`已导入「${r.name}」`)
  load()
}

function menu(a: AgentRow) {
  const canEdit = auth.can('agent:edit')
  return [
    { label: '编辑', icon: Pencil, onClick: () => router.push(`/agents/${a.id}`) },
    { label: '复制新建', icon: CopyPlus, disabled: !canEdit, onClick: async () => { const r = await agentApi.copy(a.id); toast.success('已复制'); router.push(`/agents/${r.id}`) } },
    { label: '导出 Package', icon: Download, onClick: () => window.open(agentApi.exportUrl(a.id)) },
    { label: '变更负责人', icon: UserCog, disabled: !canEdit, onClick: () => (ownerAgent.value = a) },
    { label: '分享智能体', icon: Share2, onClick: () => (shareAgent.value = a) },
    { divider: true, label: '' },
    { label: a.status === 'archived' ? '取消归档' : '归档', icon: a.status === 'archived' ? ArchiveRestore : Archive, disabled: !canEdit,
      onClick: async () => { await agentApi.archive(a.id, a.status !== 'archived'); toast.success('已更新'); load() } },
    { label: a.favorite ? '取消收藏' : '收藏', icon: Star, onClick: async () => { await agentApi.favorite(a.id); load() } },
    { divider: true, label: '' },
    { label: '删除', icon: Trash2, danger: true, disabled: !canEdit, onClick: () => remove(a) },
  ]
}

async function remove(a: AgentRow) {
  const ok = await confirm({ title: `删除「${a.name}」`, message: '删除后 Agent 定义与全部版本将被移除，历史会话记录保留。此操作不可撤销。', danger: true, okText: '删除', requireText: a.identifier })
  if (!ok) return
  await agentApi.remove(a.id)
  toast.success('已删除')
  load()
}

function copyId(id: string) {
  copyText(id)
  toast.success('ID 已复制')
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="row" style="gap: 12px">
        <h1 class="page-title">智能体</h1>
        <div class="segmented">
          <button :class="{ active: view === 'space' }" @click="view = 'space'"><Home /> 空间</button>
          <button :class="{ active: view === 'market' }" @click="view = 'market'"><Store /> 广场</button>
        </div>
      </div>
      <div class="page-actions">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button class="btn" :disabled="!auth.can('agent:edit')" @click="importEl?.click()"><Upload /> 导入 Package</button>
        <input ref="importEl" type="file" accept=".json" hidden @change="onImport" />
        <button class="btn btn-primary" :disabled="!auth.can('agent:edit')" @click="createOpen = true"><Plus /> 创建 Agent</button>
      </div>
    </div>

    <!-- 空间视图：表格 -->
    <div v-if="view === 'space'" class="table-wrap">
      <div class="filter-bar">
        <div class="segmented blue">
          <button :class="{ active: tab === 'favorite' }" @click="tab = 'favorite'"><Star /> 我收藏的</button>
          <button :class="{ active: tab === 'mine' }" @click="tab = 'mine'"><User /> 我负责的</button>
          <button :class="{ active: tab === 'all' }" @click="tab = 'all'">全部 Agent <span class="count">({{ counts.all }})</span></button>
        </div>
        <span class="v-divider" />
        <Folder class="fb-ico" />
        <Select v-model="group" :options="groupOptions" size="sm" style="width: 140px" />
        <Select v-model="sort" :options="sortOptions" size="sm" bare :min-width="120">
          <template #trigger><ArrowDownUp class="fb-ico" /></template>
        </Select>
        <div class="spacer" />
        <SearchInput v-model="q" placeholder="搜索名称或描述…" width="220px" size="sm" />
        <Switch v-model="archived" size="sm" label="显示归档" />
      </div>
      <table class="table">
        <thead>
          <tr>
            <th style="width: 44%">名称</th>
            <th>模型</th>
            <th>能力</th>
            <th style="text-align: right">版本</th>
            <th>负责人</th>
            <th>更新时间</th>
            <th class="actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in rows" :key="a.id" class="clickable" @click="router.push(`/agents/${a.id}`)">
            <td>
              <div class="name-cell">
                <AgentAvatar :name="a.name" :avatar="a.avatar" :size="28" :radius="8" />
                <div style="min-width: 0">
                  <div class="row" style="gap: 6px">
                    <span class="cell-main ellipsis">{{ a.name }}</span>
                    <span v-if="a.status === 'archived'" class="tag">已归档</span>
                    <Star v-if="a.favorite" class="fav" />
                    <button class="icon-btn sm" title="复制 ID" @click.stop="copyId(a.id)"><Copy /></button>
                    <button class="icon-btn sm chat" title="会话" @click.stop="router.push(`/sessions?agentId=${a.id}`)"><MessageSquare /></button>
                  </div>
                  <div class="row" style="gap: 6px; margin-top: 3px">
                    <span class="tag"><Layers /> {{ a.groupName || '未分组' }}</span>
                    <span class="cell-sub" style="margin: 0">{{ shortId(a.id, 24) }}…</span>
                  </div>
                </div>
              </div>
            </td>
            <td>
              <div class="mono model">{{ a.model?.modelId || '—' }}</div>
              <div class="small muted">{{ a.model?.providerKey }}</div>
            </td>
            <td>
              <div class="caps">
                <span v-if="a.capabilities.skills" class="cap" title="Skills"><Sparkles />{{ a.capabilities.skills }}</span>
                <span v-if="a.capabilities.mcp" class="cap" title="MCP"><Plug />{{ a.capabilities.mcp }}</span>
                <span v-if="a.capabilities.cli" class="cap" title="CLI 插件"><Terminal />{{ a.capabilities.cli }}</span>
                <span v-if="a.capabilities.memory" class="cap" title="记忆库"><Database />{{ a.capabilities.memory }}</span>
                <span v-if="a.capabilities.subAgents" class="cap" title="SubAgents"><Bot />{{ a.capabilities.subAgents }}</span>
                <span v-if="a.capabilities.envVars" class="cap" title="环境变量"><KeyRound />{{ a.capabilities.envVars }}</span>
              </div>
            </td>
            <td style="text-align: right" class="mono">v{{ a.version }}</td>
            <td><UserAvatar :name="a.owner?.displayName" :avatar="a.owner?.avatar" :size="22" show-name /></td>
            <td class="nowrap">{{ fmtTime(a.updatedAt) }}</td>
            <td class="actions" @click.stop><DropdownMenu :items="menu(a)" /></td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="Bot" :title="tab === 'favorite' ? '还没有收藏的 Agent' : '还没有 Agent'" desc="点击右上角「创建 Agent」开始">
      </Empty>
    </div>

    <!-- 广场视图：卡片 -->
    <div v-else>
      <div class="toolbar" style="margin-bottom: 14px">
        <SearchInput v-model="q" placeholder="搜索名称或描述…" width="280px" />
      </div>
      <div class="market">
        <div v-for="a in rows" :key="a.id" class="m-card card" @click="router.push(`/sessions?agentId=${a.id}`)">
          <div class="row" style="gap: 10px">
            <AgentAvatar :name="a.name" :avatar="a.avatar" :size="36" :radius="10" />
            <div style="min-width: 0">
              <div class="bold ellipsis">{{ a.name }}</div>
              <div class="tiny muted mono">{{ a.identifier }} · v{{ a.version }}</div>
            </div>
          </div>
          <p class="m-desc clamp-2">{{ a.description || '暂无描述' }}</p>
          <div class="row" style="justify-content: space-between">
            <UserAvatar :name="a.owner?.displayName" :size="18" show-name />
            <span class="link-btn small">开始对话 →</span>
          </div>
        </div>
      </div>
      <Empty v-if="!rows.length && !loading" :icon="Store" title="广场暂无 Agent" />
    </div>

    <div class="table-foot">
      <span>共 {{ total }} 条</span>
      <Pagination v-model:page="page" :size="size" :total="total" @change="load" />
    </div>

    <CreateAgentModal :open="createOpen" :groups="groups" @close="createOpen = false" />
    <OwnerModal :agent="ownerAgent" @close="ownerAgent = null" @done="load" />
    <ShareAgentModal :agent="shareAgent" @close="shareAgent = null" />
  </div>
</template>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-bottom: 1px solid var(--border); background: var(--bg-muted); }
.fb-ico { width: 15px; height: 15px; color: var(--text-3); }
.name-cell { display: flex; align-items: center; gap: 10px; min-width: 0; }
.name-cell .icon-btn { opacity: .55; }
.name-cell .icon-btn.chat { color: var(--primary); opacity: .8; }
tr:hover .name-cell .icon-btn { opacity: 1; }
.fav { width: 13px; height: 13px; color: var(--orange); fill: var(--orange); }
.model { font-size: 12.5px; font-weight: 600; }
.caps { display: flex; gap: 6px; flex-wrap: wrap; }
.cap { display: inline-flex; align-items: center; gap: 3px; font-size: 11.5px; color: var(--text-3); font-family: var(--mono); }
.cap svg { width: 12px; height: 12px; }
.nowrap { white-space: nowrap; }
.market { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; }
.m-card { padding: 16px; display: flex; flex-direction: column; gap: 12px; cursor: pointer; transition: box-shadow .15s, border-color .15s; }
.m-card:hover { box-shadow: var(--shadow-md); border-color: var(--primary-soft-2); }
.m-desc { font-size: 12.5px; color: var(--text-3); min-height: 38px; }
</style>
