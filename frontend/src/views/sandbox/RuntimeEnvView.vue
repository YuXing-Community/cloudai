<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RefreshCw, Copy, Star, Clock, Plus, Eye, Pencil, Trash2, Globe, Cloud } from 'lucide-vue-next'
import { sandboxApi } from '@/api/sandbox'
import { useAuth } from '@/stores/auth'
import Select from '@/components/ui/Select.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Modal from '@/components/ui/Modal.vue'
import Empty from '@/components/ui/Empty.vue'
import { confirm, toast } from '@/components/ui/toast'
import { copyText, fmtTime } from '@/utils/format'

const auth = useAuth()
const usage = ref('')
const rows = ref<any[]>([])
const customEnabled = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await sandboxApi.envs(usage.value)
    rows.value = r.page.records
    customEnabled.value = r.customEnabled
  } finally {
    loading.value = false
  }
}
onMounted(load)

function pkgSummary(e: any) {
  return Object.entries(e.packageSummary || {}).map(([k, v]) => `${k}:${v}`).join(' · ') || '—'
}
async function setDefault(e: any) {
  await sandboxApi.setDefault(e.id)
  toast.success(`已将 ${e.name} 设为默认运行环境`)
  load()
}

// ---------- 查看 / 编辑 ----------
const open = ref(false)
const readonly = ref(true)
const form = ref<any>(null)
function view(e: any, edit = false) {
  readonly.value = !edit || e.isSystem
  form.value = JSON.parse(JSON.stringify({ ...e, packages: e.packages || [], metadata: e.metadata || [] }))
  open.value = true
}
function create() {
  readonly.value = false
  form.value = { id: null, name: '', description: '', usageType: 'agent', resourceGroup: 'local', prewarm: 0, networkPolicy: 'default', packages: [], metadata: [] }
  open.value = true
}
async function save() {
  const f = form.value
  const data = { name: f.name, description: f.description, usageType: f.usageType, resourceGroup: f.resourceGroup, prewarm: f.prewarm, networkPolicy: f.networkPolicy, packages: f.packages.filter((p: any) => p.name), metadata: f.metadata.filter((m: any) => m.key && !m.system) }
  if (f.id) await sandboxApi.updateEnv(f.id, data)
  else await sandboxApi.createEnv(data)
  toast.success('运行环境已保存')
  open.value = false
  load()
}
async function remove(e: any) {
  if (!(await confirm({ title: `删除运行环境 ${e.name}？`, danger: true, okText: '删除' }))) return
  await sandboxApi.deleteEnv(e.id)
  load()
}
function menu(e: any) {
  return [
    { label: '查看配置', icon: Eye, onClick: () => view(e) },
    ...(!e.isSystem ? [{ label: '编辑', icon: Pencil, disabled: !auth.can('sandbox:edit'), onClick: () => view(e, true) },
      { label: '删除', icon: Trash2, danger: true, disabled: !auth.can('sandbox:edit'), onClick: () => remove(e) }] : []),
  ]
}
const MANAGERS = [{ label: 'pip', value: 'pip' }, { label: 'npm', value: 'npm' }, { label: 'apt', value: 'apt' }, { label: '阿里云 CLI', value: 'aliyun-cli' }]
const managerLabel = (m: string) => MANAGERS.find((x) => x.value === m)?.label || m
const sysMeta = computed(() => (form.value?.metadata || []).filter((m: any) => m.system))
const userMeta = computed(() => (form.value?.metadata || []).filter((m: any) => !m.system))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">运行环境</h1>
        <div class="page-sub">{{ auth.space?.name }}</div>
        <div class="page-sub">{{ customEnabled ? '当前空间已开启自定义运行环境。' : '当前空间未开启自定义运行环境，无法创建运行环境。' }}</div>
      </div>
      <div class="page-actions">
        <Select v-model="usage" :options="[{ label: '全部用途', value: '' }, { label: 'Agent 运行', value: 'agent' }, { label: '沙箱', value: 'sandbox' }]" style="width: 130px" @change="load" />
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button v-if="customEnabled && auth.can('sandbox:edit')" class="btn btn-primary" @click="create"><Plus /> 新建运行环境</button>
      </div>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>环境名称</th><th>预热</th><th>依赖包</th><th>版本</th><th>状态</th><th>更新时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="e in rows" :key="e.id" class="clickable" @click="view(e)">
            <td>
              <div class="row" style="gap: 6px">
                <span class="cell-main">{{ e.name }}</span>
                <button class="icon-btn sm" @click.stop="copyText(e.name); toast.success('已复制')"><Copy /></button>
                <span v-if="e.isSystem" class="tag tag-purple tag-round">系统</span>
                <span v-if="e.platformOnly" class="tag tag-purple tag-round">平台专用</span>
                <span v-if="e.isDefault" class="tag tag-blue tag-round">默认</span>
              </div>
              <div class="small muted" style="margin-top: 2px">{{ e.description }}</div>
            </td>
            <td><span class="kbd">{{ e.prewarm }}</span></td>
            <td><span class="tag tag-mono tag-round">{{ pkgSummary(e) }}</span></td>
            <td><span class="tag tag-mono tag-round">v{{ e.version }}</span></td>
            <td><span class="tag tag-green tag-round">{{ e.status === 'available' ? '可用' : e.status }}</span></td>
            <td class="small muted"><Clock style="width: 12px; vertical-align: -2px" /> {{ fmtTime(e.updatedAt) }}</td>
            <td class="actions" @click.stop>
              <button class="btn btn-ghost btn-sm" :disabled="e.isDefault || !auth.can('sandbox:edit')" @click="setDefault(e)"><Star :style="e.isDefault ? 'fill: var(--orange); color: var(--orange)' : ''" /> {{ e.isDefault ? '默认' : '设为默认' }}</button>
              <DropdownMenu :items="menu(e)" />
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="Cloud" title="暂无运行环境" />
    </div>
    <div class="table-foot"><span>共 {{ rows.length }} 条</span></div>

    <Modal :open="open" :title="readonly ? '查看运行环境配置' : form?.id ? '编辑运行环境' : '新建运行环境'" :subtitle="readonly && form?.isSystem ? '系统环境由平台统一维护，当前工作空间仅可查看配置。' : '本地沙箱实现：依赖包在会话工作目录内按需安装。'" width="720px" @close="open = false">
      <template v-if="form">
        <div class="form-row">
          <div class="field"><label>名称</label><input v-model="form.name" class="input" :disabled="readonly || !!form.id" placeholder="my-runtime" /></div>
          <div class="field"><label>系统模板</label><input class="input mono" :value="form.template || 'custom'" disabled /></div>
        </div>
        <div class="form-row">
          <div class="field"><label>资源组</label><input v-model="form.resourceGroup" class="input" :disabled="readonly" /></div>
          <div class="field"><label>预热数量</label><input v-model.number="form.prewarm" type="number" min="0" class="input" :disabled="readonly" /></div>
        </div>
        <div class="field"><label>描述</label><textarea v-model="form.description" class="textarea" :disabled="readonly" /></div>
        <div class="box">
          <div class="row bold" style="gap: 6px; margin-bottom: 10px"><Globe style="width: 15px; color: var(--primary)" /> 网络配置</div>
          <div class="field" style="margin: 0">
            <label>Pod 网络策略</label>
            <Select v-model="form.networkPolicy" :disabled="readonly" :options="[{ label: '默认', value: 'default' }, { label: '仅内网', value: 'internal' }, { label: '禁止外网', value: 'deny-egress' }]" />
          </div>
        </div>
        <div class="box">
          <div class="row" style="justify-content: space-between">
            <div><div class="bold" style="font-size: 15px">依赖包</div><div class="small muted">pip/npm 新增或修改时校验内部源，其他管理器由服务配置提供</div></div>
            <button v-if="!readonly" class="btn btn-sm" @click="form.packages.push({ manager: 'pip', name: '', version: 'latest' })"><Plus /> 添加</button>
          </div>
          <div v-for="(p, i) in form.packages" :key="i" class="pkg">
            <template v-if="readonly">
              <span class="pkg-mgr">{{ managerLabel(p.manager) }}</span><span class="pkg-name">{{ p.name }}</span><span class="spacer" /><span class="muted">@{{ p.version || 'latest' }}</span>
            </template>
            <template v-else>
              <Select v-model="p.manager" :options="MANAGERS" size="sm" style="width: 120px" />
              <input v-model="p.name" class="input input-sm mono" placeholder="包名" style="flex: 1" />
              <input v-model="p.version" class="input input-sm mono" placeholder="latest" style="width: 110px" />
              <button class="icon-btn sm danger" @click="form.packages.splice(i, 1)"><Trash2 /></button>
            </template>
          </div>
          <div v-if="!form.packages.length" class="empty-box" style="margin-top: 10px">暂无依赖包</div>
        </div>
        <div class="box">
          <div class="row" style="justify-content: space-between; margin-bottom: 10px">
            <div class="bold" style="font-size: 15px">Metadata</div>
            <button v-if="!readonly" class="btn btn-sm" @click="form.metadata.push({ key: '', value: '', system: false })"><Plus /> 添加</button>
          </div>
          <div v-for="(m, i) in sysMeta" :key="'s' + i" class="meta"><input class="input mono" :value="m.key" disabled /><input class="input mono" :value="m.value" disabled /></div>
          <div v-if="sysMeta.length" class="small muted" style="margin: 2px 0 8px">以上配置由系统模板同步，不可修改</div>
          <div v-for="(m, i) in userMeta" :key="'u' + i" class="meta">
            <input v-model="m.key" class="input mono" :disabled="readonly" />
            <input v-model="m.value" class="input mono" :disabled="readonly" />
          </div>
        </div>
      </template>
      <template v-if="!readonly" #footer>
        <button class="btn" @click="open = false">取消</button>
        <button class="btn btn-primary" @click="save">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.box { border: 1px solid var(--border); border-radius: 14px; padding: 14px 16px; margin-bottom: 14px; }
.pkg { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border: 1px solid var(--border); border-radius: 12px; margin-top: 8px; }
.pkg-mgr { padding: 3px 10px; border-radius: 99px; background: var(--bg-muted); font-size: 12.5px; }
.pkg-name { font-size: 14px; font-weight: 500; }
.meta { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 8px; }
</style>
