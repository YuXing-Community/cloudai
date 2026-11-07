<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  MessagesSquare, FolderOpen, Bot, Users, LayoutGrid, CalendarClock, Radio, FileText, Database, LineChart,
  Wrench, PlugZap, KeyRound, Sparkles, Webhook, SquareTerminal, Cloud, Settings, PanelLeft, BookOpen, Moon, Sun,
  ChevronsUpDown, Check, Plus, LogOut, ScanSearch, Lock, ClipboardCheck, Tags,
} from 'lucide-vue-next'
import { useAuth } from '@/stores/auth'
import { sidebarCollapsed, theme, toggleTheme } from '@/stores/ui'
import Popover from '@/components/ui/Popover.vue'
import Modal from '@/components/ui/Modal.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { http } from '@/api/http'
import { toast } from '@/components/ui/toast'

const auth = useAuth()
const route = useRoute()
const router = useRouter()

const nav = [
  {
    group: 'Agents',
    items: [
      { to: '/sessions', label: '会话', icon: MessagesSquare, perm: 'agent:view' },
      { to: '/workspace', label: '工作区', icon: FolderOpen, perm: 'sandbox:view' },
      { to: '/agents', label: '智能体', icon: Bot, perm: 'agent:view' },
      { to: '/teams', label: 'Agent小队', icon: Users, perm: 'team:view' },
      { to: '/taskboard', label: '任务看板', icon: LayoutGrid, perm: 'team:view' },
      { to: '/schedules', label: '定时任务', icon: CalendarClock, perm: 'schedule:view' },
      { to: '/channels', label: '消息通道', icon: Radio, perm: 'channel:view' },
      { to: '/files', label: '文件中心', icon: FileText, perm: 'file:view' },
      { to: '/memory', label: '记忆库', icon: Database, perm: 'memory:view' },
      { to: '/vector-search', label: '向量库查询', icon: ScanSearch, perm: 'memory:view' },
      { to: '/usage', label: '用量', icon: LineChart, perm: 'usage:view' },
    ],
  },
  {
    group: '评测',
    items: [
      { to: '/evals', label: 'Agent 评测', icon: ClipboardCheck, perm: 'eval:view' },
      { to: '/annotations', label: '人工打标', icon: Tags, perm: 'eval:view' },
    ],
  },
  {
    group: 'Plugins',
    items: [
      { to: '/plugins/tools', label: '工具集成', icon: Wrench, perm: 'plugin:view' },
      { to: '/plugins/mcp', label: 'MCP 服务', icon: PlugZap, perm: 'plugin:view' },
      { to: '/plugins/credentials', label: '凭据', icon: KeyRound, perm: 'plugin:view' },
      { to: '/plugins/skills', label: 'Skills', icon: Sparkles, perm: 'plugin:view' },
      { to: '/plugins/hooks', label: 'Hooks', icon: Webhook, perm: 'plugin:view' },
    ],
  },
  {
    group: '沙箱',
    items: [
      { to: '/sandbox', label: 'Sandbox', icon: SquareTerminal, perm: 'sandbox:view' },
      { to: '/runtime-envs', label: '运行环境', icon: Cloud, perm: 'sandbox:view' },
    ],
  },
]

function isActive(to: string) {
  if (to === '/plugins/tools') return route.path.startsWith('/plugins/tools')
  return route.path === to || route.path.startsWith(to + '/')
}

const denied = computed(() => !!route.meta.perm && auth.ready && !auth.can(route.meta.perm))

// ---------------- 空间切换 ----------------
const spaceOpen = ref(false)
const spaceBtn = ref<HTMLElement | null>(null)
const userOpen = ref(false)
const userBtn = ref<HTMLElement | null>(null)
const createOpen = ref(false)
const newSpace = ref({ name: '', description: '' })
const viewKey = ref(0)

async function switchSpace(id: string) {
  spaceOpen.value = false
  if (id === auth.spaceId) return
  await auth.switchSpace(id)
  viewKey.value++
  // 详情页切空间后回到列表
  const top = '/' + (route.path.split('/')[1] || 'sessions')
  if (route.params.id) router.push(top)
}

async function createSpace() {
  if (!newSpace.value.name.trim()) return toast.warning('请输入空间名称')
  const s = await http.post<{ id: string }>('/spaces', newSpace.value)
  await auth.loadSpaces()
  createOpen.value = false
  newSpace.value = { name: '', description: '' }
  await switchSpace(s.id)
  toast.success('空间已创建')
}

function logout() {
  auth.logout()
  router.replace('/login')
}

onMounted(async () => {
  if (!auth.ready) {
    try {
      await auth.bootstrap()
    } catch {
      /* 401 已处理 */
    }
  }
})

watch(() => auth.spaceId, () => auth.loadSpaces())
</script>

<template>
  <div class="shell" :class="{ collapsed: sidebarCollapsed }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-logo">
          <svg viewBox="0 0 32 32" fill="none">
            <path d="M10.5 22h11a4.5 4.5 0 0 0 .6-8.96A6 6 0 0 0 10.4 14.6 3.7 3.7 0 0 0 10.5 22Z" stroke="url(#bg)" stroke-width="2.2" stroke-linejoin="round" />
            <circle cx="23.5" cy="8.5" r="1.6" fill="#2563eb" /><circle cx="26.6" cy="11.6" r="1" fill="#38bdf8" /><circle cx="26.5" cy="6" r=".8" fill="#60a5fa" />
            <defs><linearGradient id="bg" x1="8" y1="10" x2="26" y2="24"><stop stop-color="#38bdf8" /><stop offset="1" stop-color="#2563eb" /></linearGradient></defs>
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-name">CloudAI</div>
          <div class="brand-sub">云原生智能体</div>
        </div>
      </div>

      <button ref="spaceBtn" class="space-switch" @click="spaceOpen = !spaceOpen">
        <span class="space-badge">{{ (auth.space?.name || '空').slice(0, 1) }}</span>
        <span class="ellipsis" style="flex: 1; text-align: left">{{ auth.space?.name || '选择空间' }}</span>
        <ChevronsUpDown class="ss-arrow" />
      </button>
      <Popover :open="spaceOpen" :anchor="spaceBtn" match-width :min-width="200" @close="spaceOpen = false">
        <div class="pop-label">切换空间</div>
        <div v-for="s in auth.spaces" :key="s.id" class="pop-item" @click="switchSpace(s.id)">
          <span class="space-badge">{{ s.name.slice(0, 1) }}</span>
          <span class="ellipsis" style="flex: 1">{{ s.name }}</span>
          <span class="tiny faint">{{ s.memberCount }} 人</span>
          <Check v-if="s.id === auth.spaceId" class="pop-check" />
        </div>
        <div class="pop-divider" />
        <div class="pop-item" @click="spaceOpen = false; createOpen = true"><Plus class="pop-ico" /> 新建空间</div>
      </Popover>

      <nav class="nav">
        <div v-for="g in nav" :key="g.group" class="nav-group">
          <div class="nav-label">{{ g.group }}</div>
          <RouterLink
            v-for="it in g.items"
            :key="it.to"
            :to="it.to"
            class="nav-item"
            :class="{ active: isActive(it.to), dim: auth.ready && !auth.can(it.perm) }"
          >
            <component :is="it.icon" class="nav-ico" />
            <span>{{ it.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="side-foot">
        <RouterLink to="/settings/basic" class="nav-item" :class="{ active: route.path.startsWith('/settings') }">
          <Settings class="nav-ico" /><span>空间配置</span>
        </RouterLink>
        <button ref="userBtn" class="user-row" @click="userOpen = !userOpen">
          <UserAvatar :name="auth.user?.displayName" :avatar="auth.user?.avatar" :size="24" />
          <span class="ellipsis" style="flex: 1; text-align: left">{{ auth.user?.displayName }}</span>
          <ChevronsUpDown class="ss-arrow" />
        </button>
        <Popover :open="userOpen" :anchor="userBtn" :min-width="200" @close="userOpen = false">
          <div class="user-card">
            <UserAvatar :name="auth.user?.displayName" :avatar="auth.user?.avatar" :size="32" show-name :sub="auth.user?.email" />
          </div>
          <div class="pop-divider" />
          <div class="pop-item" @click="toggleTheme(); userOpen = false">
            <Moon v-if="theme === 'light'" class="pop-ico" /><Sun v-else class="pop-ico" /> {{ theme === 'light' ? '深色模式' : '浅色模式' }}
          </div>
          <div class="pop-item danger" @click="logout"><LogOut class="pop-ico" /> 退出登录</div>
        </Popover>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <button class="icon-btn" title="折叠侧栏" @click="sidebarCollapsed = !sidebarCollapsed"><PanelLeft /></button>
        <span class="v-divider" />
        <span class="crumb">{{ auth.space?.name }}</span>
        <div class="spacer" />
        <a class="icon-btn" href="https://github.com/" target="_blank" title="文档" @click.prevent="router.push('/agents')"><BookOpen /></a>
        <button class="icon-btn" :title="theme === 'light' ? '深色模式' : '浅色模式'" @click="toggleTheme">
          <Moon v-if="theme === 'light'" /><Sun v-else />
        </button>
      </header>
      <section class="content">
        <div v-if="!auth.ready" class="boot"><div class="skeleton" style="width: 220px; height: 14px" /></div>
        <div v-else-if="!auth.spaceId" class="boot">请先创建或加入一个空间</div>
        <div v-else-if="denied" class="boot">
          <div class="empty">
            <div class="empty-icon"><Lock /></div>
            <div class="empty-title">没有访问权限</div>
            <div class="small">当前角色没有「{{ route.meta.title }}」的访问权限，请联系空间管理员</div>
          </div>
        </div>
        <RouterView v-else :key="viewKey + ':' + auth.spaceId" />
      </section>
    </main>

    <Modal :open="createOpen" title="新建空间" subtitle="空间之间相互隔离，空间内的 Agent 不能调用其他空间的 Agent。" width="440px" @close="createOpen = false">
      <div class="field"><label>空间名称 <span class="req">*</span></label><input v-model="newSpace.name" class="input" placeholder="例如：风控数据" maxlength="64" /></div>
      <div class="field"><label>描述</label><textarea v-model="newSpace.description" class="textarea" placeholder="可选" /></div>
      <template #footer>
        <button class="btn" @click="createOpen = false">取消</button>
        <button class="btn btn-primary" @click="createSpace">创建</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100vh; overflow: hidden; }
.sidebar {
  width: var(--sidebar-w); flex-shrink: 0; background: var(--bg-sidebar); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; padding: 8px 8px 8px; transition: margin-left .2s ease;
}
.collapsed .sidebar { margin-left: calc(-1 * var(--sidebar-w)); }
.brand {
  display: flex; align-items: center; gap: 10px; padding: 9px 10px; border-radius: 12px;
  background: linear-gradient(135deg, #eef7ff 0%, #f3f8ff 60%, #eaf4fb 100%); border: 1px solid #dfeaf6;
}
[data-theme="dark"] .brand { background: linear-gradient(135deg, #142235, #161f2c); border-color: #22324a; }
.brand-logo { width: 32px; height: 32px; border-radius: 9px; background: #fff; display: flex; align-items: center; justify-content: center; box-shadow: 0 1px 2px rgba(37, 99, 235, .15); }
[data-theme="dark"] .brand-logo { background: #0f1a29; }
.brand-logo svg { width: 26px; height: 26px; }
.brand-name { font-weight: 700; font-size: 14px; letter-spacing: -.01em; }
.brand-sub { font-size: 11px; color: var(--text-3); }
.space-switch {
  margin-top: 8px; display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border-radius: 9px;
  border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; font-size: 13px; font-weight: 500;
}
.space-switch:hover { border-color: var(--border-strong); }
.space-badge { width: 18px; height: 18px; border-radius: 5px; background: var(--purple-soft); color: var(--purple-text); font-size: 10.5px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ss-arrow { width: 13px; height: 13px; color: var(--text-4); flex-shrink: 0; }
.nav { flex: 1; overflow-y: auto; margin-top: 8px; padding-right: 2px; }
.nav-group { margin-bottom: 8px; }
.nav-label { font-size: 11.5px; color: var(--text-3); padding: 10px 8px 4px; }
.nav-item {
  display: flex; align-items: center; gap: 9px; height: 30px; padding: 0 9px; border-radius: 8px;
  color: var(--text-2); font-size: 13px; text-decoration: none !important; transition: background .12s, color .12s; margin: 1px 0;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text); }
.nav-item.active { background: var(--bg-active); color: var(--primary-text); font-weight: 600; }
.nav-item.dim { opacity: .5; }
.nav-ico { width: 15px; height: 15px; flex-shrink: 0; }
.side-foot { border-top: 1px solid var(--divider); padding-top: 6px; display: flex; flex-direction: column; gap: 2px; }
.user-row { display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 8px; border-radius: 8px; border: none; background: transparent; cursor: pointer; font-size: 13px; font-weight: 500; }
.user-row:hover { background: var(--bg-hover); }
.user-card { padding: 8px 8px 6px; }
.pop-label { font-size: 11.5px; color: var(--text-3); padding: 6px 8px 4px; }
.pop-item { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.pop-item:hover { background: var(--bg-hover); }
.pop-item.danger { color: var(--red-text); }
.pop-ico { width: 15px; height: 15px; color: var(--text-3); }
.pop-item.danger .pop-ico { color: var(--red-text); }
.pop-check { width: 14px; height: 14px; color: var(--primary); }
.pop-divider { height: 1px; background: var(--divider); margin: 4px 2px; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar { height: var(--topbar-h); flex-shrink: 0; display: flex; align-items: center; gap: 10px; padding: 0 12px; border-bottom: 1px solid var(--border); background: var(--bg); }
.crumb { font-size: 13px; color: var(--text-2); font-weight: 500; }
.content { flex: 1; min-height: 0; overflow: auto; position: relative; }
.boot { height: 100%; display: flex; align-items: center; justify-content: center; color: var(--text-3); }
</style>
