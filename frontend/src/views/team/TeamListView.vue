<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Users, Plus, Pencil, Trash2, Play, Route, Bot, Info } from 'lucide-vue-next'
import { teamApi, PATTERN } from '@/api/team'
import { optionApi } from '@/api/session'
import { useAuth } from '@/stores/auth'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import Empty from '@/components/ui/Empty.vue'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import { confirm, toast } from '@/components/ui/toast'

const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('team:edit'))
const teams = ref<any[]>([])
const agents = ref<any[]>([])

async function load() { teams.value = await teamApi.list() }
onMounted(async () => { load(); agents.value = await optionApi.agents() })

const open = ref(false)
const f = ref<any>({})
function edit(t?: any) {
  f.value = t
    ? { id: t.id, name: t.name, description: t.description || '', pattern: t.pattern, roles: t.roles.map((r: any) => ({ role: r.role, label: r.label, description: r.description, agentId: r.agentId })) }
    : { id: null, name: '', description: '', pattern: 'expert_pool', roles: [
      { role: 'router', label: '路由', description: '理解目标并分派给最合适的专家', agentId: '' },
      { role: 'expert1', label: '专家', description: '', agentId: '' }] }
  open.value = true
}
async function save() {
  const v = f.value
  if (!v.name.trim()) return toast.warning('请填写名称')
  if (v.roles.some((r: any) => !r.role || !r.agentId)) return toast.warning('每个角色都需要标识并绑定 Agent')
  if (v.id) await teamApi.update(v.id, v)
  else await teamApi.create(v)
  toast.success('已保存')
  open.value = false
  load()
}
async function remove(t: any) {
  if (!(await confirm({ title: `删除小队「${t.name}」？`, danger: true, okText: '删除' }))) return
  await teamApi.remove(t.id)
  load()
}

function nextRole() {
  let i = 1
  while (f.value.roles.some((r: any) => r.role === `expert${i}`)) i++
  return `expert${i}`
}

const runTeam = ref<any>(null)
const goal = ref('')
async function run() {
  if (!goal.value.trim()) return toast.warning('请填写任务目标')
  const r = await teamApi.run(runTeam.value.id, goal.value)
  runTeam.value = null
  goal.value = ''
  router.push(`/taskboard/${r.id}`)
}
const agentOptions = computed(() => agents.value.map((a) => ({ label: a.name, value: a.id, sub: a.identifier })))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Agent 小队</h1>
        <div class="page-sub">把多个 Agent 编排为一个团队：router 理解目标并分派给专家，执行过程在任务看板中可追踪。</div>
      </div>
      <button v-if="canEdit" class="btn btn-primary" @click="edit()"><Plus /> 新建小队</button>
    </div>
    <div class="grid">
      <div v-for="t in teams" :key="t.id" class="team card">
        <div class="row" style="gap: 10px">
          <div class="t-ico"><Users /></div>
          <div style="flex: 1; min-width: 0">
            <div class="bold ellipsis">{{ t.name }}</div>
            <div class="tiny muted">{{ PATTERN[t.pattern] }} · {{ t.roles.length }} 个角色 · 已运行 {{ t.runs }} 次</div>
          </div>
          <template v-if="canEdit">
            <button class="icon-btn sm" @click="edit(t)"><Pencil /></button>
            <button class="icon-btn sm danger" @click="remove(t)"><Trash2 /></button>
          </template>
        </div>
        <p class="small muted clamp-2" style="margin: 10px 0">{{ t.description || '暂无描述' }}</p>
        <div class="roles">
          <div v-for="(r, i) in t.roles" :key="r.role" class="role">
            <span class="r-tag" :class="{ router: i === 0 }"><Route v-if="i === 0" /><Bot v-else />{{ r.role }}</span>
            <AgentAvatar :name="r.agentName" :avatar="r.agentAvatar" :size="18" :radius="5" />
            <span class="small ellipsis">{{ r.agentName }}</span>
          </div>
        </div>
        <button v-if="canEdit" class="btn btn-soft btn-block" style="margin-top: 12px" @click="goal = ''; runTeam = t"><Play /> 发起任务</button>
      </div>
    </div>
    <Empty v-if="!teams.length" :icon="Users" title="还没有 Agent 小队" desc="至少需要 2 个 Agent：一个作为 router，其他作为专家" />

    <Modal :open="open" :title="f.id ? '编辑小队' : '新建小队'" width="720px" @close="open = false">
      <div class="form-row">
        <div class="field"><label>名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="例如：risk-data-ops-team" /></div>
        <div class="field"><label>协作模式</label><Select v-model="f.pattern" :options="[{ label: '专家池（router 选择专家）', value: 'expert_pool' }]" /></div>
      </div>
      <div class="field"><label>描述</label><input v-model="f.description" class="input" /></div>
      <div class="row" style="justify-content: space-between; margin-bottom: 8px">
        <span class="label">角色与 Agent</span>
        <button class="btn btn-sm" @click="f.roles.push({ role: nextRole(), label: '', description: '', agentId: '' })"><Plus /> 添加专家</button>
      </div>
      <div class="notice notice-info" style="margin-bottom: 10px"><Info /> 第一个角色为 router，负责理解目标并选择专家（必要时向用户追问）。所有 Agent 必须属于当前空间。</div>
      <div v-for="(r, i) in f.roles" :key="i" class="role-edit">
        <span class="r-tag" :class="{ router: i === 0 }">{{ i === 0 ? 'ROUTER' : 'EXPERT' }}</span>
        <input v-model="r.role" class="input input-sm mono" placeholder="role" style="width: 120px" :disabled="i === 0" />
        <input v-model="r.label" class="input input-sm" placeholder="中文名" style="width: 110px" />
        <Select v-model="r.agentId" :options="agentOptions" size="sm" searchable placeholder="绑定 Agent" style="width: 180px" />
        <input v-model="r.description" class="input input-sm" placeholder="擅长处理什么问题（router 据此分派）" style="flex: 1" />
        <button class="icon-btn sm danger" :disabled="i === 0 || f.roles.length <= 2" @click="f.roles.splice(i, 1)"><Trash2 /></button>
      </div>
      <template #footer><button class="btn" @click="open = false">取消</button><button class="btn btn-primary" @click="save">保存</button></template>
    </Modal>

    <Modal :open="!!runTeam" :title="`发起任务 · ${runTeam?.name || ''}`" subtitle="描述你的目标，router 会选择合适的专家执行；信息不足时会在任务看板向你追问。" width="560px" @close="runTeam = null">
      <textarea v-model="goal" class="textarea" rows="5" placeholder="例如：查看杭州地域 risk 空间的资源组 Quota 水位，并给出扩容建议" />
      <template #footer><button class="btn" @click="runTeam = null">取消</button><button class="btn btn-primary" @click="run"><Play /> 开始</button></template>
    </Modal>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
.team { padding: 16px; }
.t-ico { width: 36px; height: 36px; border-radius: 10px; background: var(--purple-soft); color: var(--purple-text); display: flex; align-items: center; justify-content: center; }
.t-ico svg { width: 18px; height: 18px; }
.roles { display: flex; flex-direction: column; gap: 6px; }
.role { display: flex; align-items: center; gap: 8px; }
.r-tag { display: inline-flex; align-items: center; gap: 4px; font-family: var(--mono); font-size: 11px; padding: 2px 7px; border-radius: 5px; background: var(--bg-muted); color: var(--text-2); min-width: 72px; }
.r-tag svg { width: 11px; height: 11px; }
.r-tag.router { background: var(--primary-soft); color: var(--primary-text); }
.role-edit { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
</style>
