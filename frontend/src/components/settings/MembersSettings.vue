<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { LogOut, UserPlus, Shield, Plus, Pencil, Trash2, Info, Check, Search, X } from 'lucide-vue-next'
import { settingsApi, ROLE_TONE } from '@/api/settings'
import { useAuth } from '@/stores/auth'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import Modal from '@/components/ui/Modal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce, fmtDatePad } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const canManage = computed(() => auth.can('member:manage'))

const view = ref<'members' | 'roles'>('members')
const q = ref('')
const role = ref('')
const rows = ref<any[]>([])
const counts = ref<Record<string, number>>({})
const roles = ref<any[]>([])
const catalog = ref<{ code: string; name: string }[]>([])

const roleName = (code: string) => roles.value.find((r) => r.code === code)?.name || code
const roleOptions = computed(() => roles.value.filter((r) => r.code !== 'owner').map((r) => ({ label: r.name, value: r.code, sub: r.isSystem ? '系统角色' : '自定义角色' })))

async function load() {
  const r = await settingsApi.members(q.value, role.value)
  rows.value = r.records
  counts.value = r.counts
}
async function loadRoles() {
  const r = await settingsApi.roles()
  roles.value = r.roles
  catalog.value = r.permissions
}
onMounted(() => { load(); loadRoles() })
watch(q, debounce(load, 250))
watch(role, load)

async function changeRole(m: any, code: string) {
  await settingsApi.changeRole(m.id, code)
  toast.success(`已将 ${m.displayName} 设为${roleName(code)}`)
  load()
}
async function remove(m: any) {
  if (!(await confirm({ title: `移除成员 ${m.displayName}？`, message: '移除后该用户将无法访问本空间的资源。', danger: true, okText: '移除' }))) return
  await settingsApi.removeMember(m.id)
  load()
}
async function leave() {
  if (!(await confirm({ title: '退出空间？', message: `退出后你将无法访问「${auth.space?.name}」的任何资源。`, danger: true, okText: '退出' }))) return
  await settingsApi.leave()
  await auth.leaveCurrentSpace()
  router.replace('/sessions')
}

// ------------- 添加成员 -------------
const addOpen = ref(false)
const uq = ref('')
const found = ref<any[]>([])
const picked = ref<any[]>([])
const addRole = ref('member')
watch(uq, debounce(async () => { found.value = uq.value.trim() ? await settingsApi.searchUsers(uq.value.trim()) : [] }, 250))
function openAdd() {
  picked.value = []
  found.value = []
  uq.value = ''
  addRole.value = 'member'
  addOpen.value = true
}
function togglePick(u: any) {
  const i = picked.value.findIndex((x) => x.id === u.id)
  i >= 0 ? picked.value.splice(i, 1) : picked.value.push(u)
}
async function addMembers() {
  if (!picked.value.length) return toast.warning('请选择用户')
  await settingsApi.addMembers(picked.value.map((u) => u.id), addRole.value)
  toast.success(`已添加 ${picked.value.length} 名成员`)
  addOpen.value = false
  picked.value = []
  uq.value = ''
  load()
}

// ------------- 自定义角色 -------------
const roleOpen = ref(false)
const roleForm = ref<any>({ id: null, code: '', name: '', description: '', permissions: [] as string[] })
function editRole(r?: any) {
  roleForm.value = r ? { id: r.id, code: r.code, name: r.name, description: r.description || '', permissions: [...(r.permissions || [])] }
    : { id: null, code: '', name: '', description: '', permissions: ['agent:view', 'session:use'] }
  roleOpen.value = true
}
function togglePerm(p: string) {
  const a: string[] = roleForm.value.permissions
  const i = a.indexOf(p)
  i >= 0 ? a.splice(i, 1) : a.push(p)
}
async function saveRole() {
  const f = roleForm.value
  if (!f.name.trim()) return toast.warning('请填写角色名称')
  if (f.id) await settingsApi.updateRole(f.id, f)
  else await settingsApi.createRole(f)
  toast.success('角色已保存')
  roleOpen.value = false
  loadRoles()
}
async function deleteRole(r: any) {
  if (!(await confirm({ title: `删除角色「${r.name}」？`, danger: true, okText: '删除' }))) return
  await settingsApi.deleteRole(r.id)
  loadRoles()
}
const permGroups = computed(() => {
  const g: Record<string, { code: string; name: string }[]> = {}
  for (const p of catalog.value) {
    const k = p.code.split(':')[0]
    ;(g[k] = g[k] || []).push(p)
  }
  return g
})
const GROUP_LABEL: Record<string, string> = { agent: '智能体', session: '会话', team: '任务看板', schedule: '定时任务', channel: '消息通道', file: '文件中心', memory: '记忆库', usage: '用量', plugin: 'Plugins', sandbox: '沙箱', space: '空间', member: '成员', model: '模型' }
</script>

<template>
  <div>
    <div class="row" style="justify-content: space-between; align-items: flex-start; margin-bottom: 14px">
      <div>
        <h2 class="h">成员管理</h2>
        <p class="d">当前共有 {{ counts.all || 0 }} 名成员。可为成员配置角色从而隔离功能，支持自定义角色。</p>
      </div>
      <div class="row">
        <div class="segmented">
          <button :class="{ active: view === 'members' }" @click="view = 'members'">成员</button>
          <button :class="{ active: view === 'roles' }" @click="view = 'roles'"><Shield /> 角色</button>
        </div>
        <button v-if="auth.roleCode !== 'owner'" class="btn btn-danger-ghost" @click="leave"><LogOut /> 退出空间</button>
        <button v-if="canManage" class="btn btn-primary" @click="openAdd"><UserPlus /> 添加成员</button>
      </div>
    </div>

    <template v-if="view === 'members'">
      <div class="row" style="justify-content: space-between; margin-bottom: 10px">
        <SearchInput v-model="q" placeholder="搜索成员..." width="260px" />
        <div class="segmented">
          <button :class="{ active: role === '' }" @click="role = ''">全部 <span class="count">{{ counts.all || 0 }}</span></button>
          <button v-for="r in roles" :key="r.code" :class="{ active: role === r.code }" @click="role = r.code">{{ r.name }} <span class="count">{{ counts[r.code] || 0 }}</span></button>
        </div>
      </div>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>成员</th><th style="width: 160px">角色 <Info class="i" title="角色决定成员可访问的功能" /></th><th style="width: 120px">加入时间</th><th v-if="canManage" class="actions" style="width: 60px" /></tr></thead>
          <tbody>
            <tr v-for="m in rows" :key="m.id">
              <td><UserAvatar :name="m.displayName" :avatar="m.avatar" :size="30" show-name :sub="`@${m.username} · ${m.email}`" /></td>
              <td>
                <span v-if="m.roleCode === 'owner' || !canManage" class="tag tag-round" :class="ROLE_TONE[m.roleCode] || 'tag-purple'"><Shield /> {{ roleName(m.roleCode) }}</span>
                <Select v-else :model-value="m.roleCode" :options="roleOptions" size="sm" @change="(v) => changeRole(m, v)" />
              </td>
              <td class="muted">{{ fmtDatePad(m.joinedAt) }}</td>
              <td v-if="canManage" class="actions"><button v-if="m.roleCode !== 'owner'" class="icon-btn danger" @click="remove(m)"><Trash2 /></button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <template v-else>
      <div class="roles">
        <div v-for="r in roles" :key="r.id" class="role card">
          <div class="row" style="gap: 8px">
            <span class="tag tag-round" :class="ROLE_TONE[r.code] || 'tag-purple'"><Shield /> {{ r.name }}</span>
            <span class="tiny muted mono">{{ r.code }}</span>
            <span class="tag">{{ r.isSystem ? '系统' : '自定义' }}</span>
            <div class="spacer" />
            <template v-if="!r.isSystem && canManage">
              <button class="icon-btn sm" @click="editRole(r)"><Pencil /></button>
              <button class="icon-btn sm danger" @click="deleteRole(r)"><Trash2 /></button>
            </template>
          </div>
          <p class="small muted" style="margin: 6px 0 8px">{{ r.description || '—' }}</p>
          <div class="perms">
            <span v-for="p in r.permissions" :key="p" class="tag tag-outline">{{ catalog.find((c) => c.code === p)?.name || p }}</span>
          </div>
        </div>
        <button v-if="canManage" class="role-add" @click="editRole()"><Plus /> 新建自定义角色</button>
      </div>
    </template>

    <Modal :open="addOpen" title="添加成员" subtitle="搜索已注册的用户（用户名、姓名或邮箱）加入当前空间。" width="520px" @close="addOpen = false">
      <div class="search"><Search /><input v-model="uq" placeholder="搜索用户名、姓名或邮箱" /></div>
      <div v-if="found.length" class="found">
        <button v-for="u in found" :key="u.id" class="fu" :class="{ on: picked.some((x) => x.id === u.id) }" @click="togglePick(u)">
          <UserAvatar :name="u.displayName" :avatar="u.avatar" :size="26" show-name :sub="`@${u.username} · ${u.email}`" />
          <Check v-if="picked.some((x) => x.id === u.id)" class="fu-check" />
        </button>
      </div>
      <div v-if="picked.length" class="picked">
        <span v-for="u in picked" :key="u.id" class="tag tag-blue">{{ u.displayName }} <X style="cursor: pointer" @click="togglePick(u)" /></span>
      </div>
      <div class="field" style="margin-top: 14px"><label>角色</label><Select v-model="addRole" :options="roleOptions" /></div>
      <template #footer>
        <button class="btn" @click="addOpen = false">取消</button>
        <button class="btn btn-primary" @click="addMembers">添加 {{ picked.length || '' }}</button>
      </template>
    </Modal>

    <Modal :open="roleOpen" :title="roleForm.id ? '编辑角色' : '新建自定义角色'" subtitle="勾选该角色可以使用的功能，未勾选的功能对该角色隐藏。" width="640px" @close="roleOpen = false">
      <div class="form-row">
        <div class="field"><label>角色名称 <span class="req">*</span></label><input v-model="roleForm.name" class="input" placeholder="例如：数据分析师" /></div>
        <div class="field"><label>角色标识 <span class="req">*</span></label><input v-model="roleForm.code" class="input mono" placeholder="analyst" :disabled="!!roleForm.id" /></div>
      </div>
      <div class="field"><label>描述</label><input v-model="roleForm.description" class="input" placeholder="可选" /></div>
      <div class="label" style="margin-bottom: 8px">权限（{{ roleForm.permissions.length }}）</div>
      <div class="pgrid">
        <div v-for="(ps, g) in permGroups" :key="g" class="pg">
          <div class="pg-title">{{ GROUP_LABEL[g] || g }}</div>
          <label v-for="p in ps" :key="p.code" class="pcheck">
            <input type="checkbox" :checked="roleForm.permissions.includes(p.code)" @change="togglePerm(p.code)" />
            <span>{{ p.name }}</span>
          </label>
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="roleOpen = false">取消</button>
        <button class="btn btn-primary" @click="saveRole">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.h { font-size: 15px; font-weight: 650; }
.d { color: var(--text-3); font-size: 12.5px; margin-top: 4px; }
.i { width: 12px; height: 12px; vertical-align: -2px; }
.roles { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.role { padding: 14px 16px; }
.perms { display: flex; flex-wrap: wrap; gap: 5px; }
.role-add { border: 1.5px dashed var(--border-strong); border-radius: var(--radius-lg); background: transparent; cursor: pointer; color: var(--text-3); display: flex; align-items: center; justify-content: center; gap: 6px; min-height: 120px; font-weight: 600; }
.role-add:hover { border-color: var(--primary); color: var(--primary); }
.role-add svg { width: 16px; height: 16px; }
.search { display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; }
.search svg { width: 15px; height: 15px; color: var(--text-4); }
.search input { flex: 1; border: none; outline: none; background: transparent; }
.found { margin-top: 8px; border: 1px solid var(--border); border-radius: 10px; padding: 4px; max-height: 220px; overflow: auto; }
.fu { width: 100%; display: flex; align-items: center; padding: 6px 8px; border: none; border-radius: 8px; background: transparent; cursor: pointer; }
.fu:hover, .fu.on { background: var(--primary-soft); }
.fu-check { width: 15px; height: 15px; color: var(--primary); margin-left: auto; }
.picked { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.pgrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.pg { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; }
.pg-title { font-weight: 600; font-size: 12.5px; margin-bottom: 6px; }
.pcheck { display: flex; align-items: center; gap: 6px; font-size: 12.5px; padding: 3px 0; cursor: pointer; color: var(--text-2); }
</style>
