<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Plug, Plus, Search, ChevronRight, Server, Globe, ScrollText, Pencil, RefreshCw, Trash2, Zap, Play, Wrench, User, FileJson, Loader2,
} from 'lucide-vue-next'
import { pluginApi, PLUGIN_TYPE, RISK } from '@/api/plugin'
import { useAuth } from '@/stores/auth'
import Switch from '@/components/ui/Switch.vue'
import Drawer from '@/components/ui/Drawer.vue'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Empty from '@/components/ui/Empty.vue'
import ToolDebugModal from '@/components/plugin/ToolDebugModal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const mcpOnly = computed(() => !!route.meta.mcpOnly)
const canEdit = computed(() => auth.can('plugin:edit'))

const q = ref('')
const list = ref<any[]>([])
const openIds = ref<Set<number>>(new Set())
const cur = ref<any>(null)
const loading = ref(false)

async function load() {
  list.value = await pluginApi.list(q.value, mcpOnly.value ? 'mcp' : undefined)
  const want = Number(route.query.id) || cur.value?.id || list.value[0]?.id
  if (want) select(want)
  else cur.value = null
}
async function select(id: number) {
  loading.value = true
  try {
    cur.value = await pluginApi.get(id)
    if (Number(route.query.id) !== id) router.replace({ query: { id } })
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(q, debounce(load, 300))
watch(mcpOnly, load)

function typeIcon(t: string) { return t === 'mcp' ? Server : t === 'openapi' ? Globe : ScrollText }
function toggleOpen(id: number) {
  const s = new Set(openIds.value)
  s.has(id) ? s.delete(id) : s.add(id)
  openIds.value = s
}

// ---------- 工具操作 ----------
async function toggleTool(t: any, v: boolean) {
  await pluginApi.updateTool(cur.value.id, t.id, { enabled: v })
  t.enabled = v
}
async function setRisk(t: any, r: string) {
  await pluginApi.updateTool(cur.value.id, t.id, { riskLevel: r })
  t.riskLevel = r
}
async function toggleScope(t: any) {
  const s = t.scope === 'internal' ? 'external' : 'internal'
  await pluginApi.updateTool(cur.value.id, t.id, { scope: s })
  t.scope = s
}
function toolMenu(t: any) {
  return [
    { label: '低风险', onClick: () => setRisk(t, 'low') },
    { label: '中风险', onClick: () => setRisk(t, 'medium') },
    { label: '高风险', onClick: () => setRisk(t, 'high') },
    { divider: true, label: '' },
    { label: t.scope === 'internal' ? '标记为外部' : '标记为内部', onClick: () => toggleScope(t) },
    { label: '编辑描述', icon: Pencil, onClick: () => editDesc(t) },
    ...(cur.value.type !== 'mcp' ? [{ label: '删除工具', icon: Trash2, danger: true, onClick: () => removeTool(t) }] : []),
  ]
}
const descTool = ref<any>(null)
const descText = ref('')
function editDesc(t: any) { descTool.value = t; descText.value = t.description || '' }
async function saveDesc() {
  await pluginApi.updateTool(cur.value.id, descTool.value.id, { description: descText.value })
  descTool.value.description = descText.value
  descTool.value = null
}
async function removeTool(t: any) {
  if (!(await confirm({ title: `删除工具 ${t.name}？`, danger: true, okText: '删除' }))) return
  await pluginApi.removeTool(cur.value.id, t.id)
  select(cur.value.id)
}
const debugTool = ref<any>(null)

// ---------- 插件操作 ----------
const discovering = ref(false)
async function rediscover() {
  discovering.value = true
  try {
    const r = await pluginApi.rediscover(cur.value.id)
    toast.success(`发现完成：新增 ${r.added}、更新 ${r.updated}、移除 ${r.removed}`)
    await select(cur.value.id)
    load()
  } finally {
    discovering.value = false
  }
}
async function removePlugin() {
  if (!(await confirm({ title: `删除插件 ${cur.value.name}？`, message: '已引用该插件的 Agent 将无法再调用这些工具。', danger: true, okText: '删除', requireText: cur.value.name }))) return
  await pluginApi.remove(cur.value.id)
  toast.success('已删除')
  cur.value = null
  router.replace({ query: {} })
  load()
}
async function togglePlugin(v: boolean) {
  await pluginApi.update(cur.value.id, { enabled: v })
  cur.value.enabled = v
}

// 编辑抽屉
const editOpen = ref(false)
const ef = ref<any>({})
function openEdit() {
  const c = cur.value
  ef.value = { name: c.name, description: c.description || '', endpoint: c.endpoint, transport: c.transport || 'streamable-http', authMode: c.authMode || 'none',
    bearerToken: c.config?.bearerToken || '', headers: JSON.parse(JSON.stringify(c.headers || [])), config: { ...(c.config || {}) } }
  editOpen.value = true
}
async function saveEdit() {
  await pluginApi.update(cur.value.id, ef.value)
  toast.success('插件已保存')
  editOpen.value = false
  select(cur.value.id)
  load()
}

// 新增 HTTP 接口工具 / 云日志查询工具
const addOpen = ref(false)
const af = ref<any>({})
function openAdd() {
  af.value = cur.value.type === 'cloudlog'
    ? { name: '', description: '', riskLevel: 'low', project: '', logstore: '', query: '*', minutes: 15 }
    : { name: '', description: '', riskLevel: 'low', method: 'GET', path: '/', schema: '{\n  "type": "object",\n  "properties": {}\n}' }
  addOpen.value = true
}
async function saveAdd() {
  const a = af.value
  let data: any
  if (cur.value.type === 'cloudlog') {
    data = { name: a.name, description: a.description || `查询 ${a.project}/${a.logstore} 日志`, riskLevel: a.riskLevel,
      httpConfig: { project: a.project, logstore: a.logstore, query: a.query, minutes: a.minutes },
      inputSchema: { type: 'object', properties: { query: { type: 'string', description: 'SLS 查询语句，默认 ' + a.query }, minutes: { type: 'integer', description: '查询最近多少分钟' }, line: { type: 'integer', description: '返回条数，最大 1000' } } } }
  } else {
    let schema
    try { schema = JSON.parse(a.schema) } catch { return toast.error('参数 Schema 不是合法 JSON') }
    data = { name: a.name, description: a.description, riskLevel: a.riskLevel, httpConfig: { method: a.method, path: a.path }, inputSchema: schema }
  }
  await pluginApi.addTool(cur.value.id, data)
  toast.success('工具已添加')
  addOpen.value = false
  select(cur.value.id)
}

// 导入 OpenAPI
const importOpen = ref(false)
const specText = ref('')
async function onSpecFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) specText.value = await f.text()
}
async function doImport() {
  const r = await pluginApi.importOpenApi(cur.value.id, specText.value)
  toast.success(`导入完成：新增 ${r.added} 个，跳过 ${r.skipped} 个同名工具`)
  importOpen.value = false
  specText.value = ''
  select(cur.value.id)
}
</script>

<template>
  <div class="page tools-page">
    <div class="page-head">
      <h1 class="page-title"><Plug style="width: 20px; color: var(--primary)" /> {{ mcpOnly ? 'MCP 服务' : '工具集成' }}</h1>
      <div class="page-actions">
        <button v-if="canEdit" class="btn btn-primary" @click="router.push('/plugins/tools/new')"><Plus /> 新增插件</button>
      </div>
    </div>
    <div class="split card">
      <aside class="left">
        <div class="l-search">
          <div class="ls-box"><Search /><input v-model="q" placeholder="搜索插件或工具…" /></div>
          <button class="icon-btn" title="我创建的"><User /></button>
        </div>
        <div class="tree">
          <div v-for="p in list" :key="p.id">
            <div class="t-row" :class="{ active: cur?.id === p.id }" @click="select(p.id)">
              <ChevronRight class="t-chev" :class="{ rot: openIds.has(p.id) }" @click.stop="toggleOpen(p.id)" />
              <component :is="typeIcon(p.type)" class="t-ico" :class="p.type" />
              <span class="t-name ellipsis">{{ p.name }}</span>
              <span v-if="!p.enabled" class="tag">停用</span>
            </div>
            <div v-if="openIds.has(p.id)" class="t-tools">
              <div v-for="t in p.tools" :key="t.id" class="t-tool" :class="{ off: !t.enabled }"><Zap /> {{ t.name }}</div>
              <div v-if="!p.tools.length" class="t-tool off">暂无工具</div>
            </div>
          </div>
          <Empty v-if="!list.length" :icon="Plug" title="暂无插件" desc="新增 MCP Server / OpenAPI / 云日志插件" compact />
        </div>
      </aside>
      <section class="right">
        <template v-if="cur">
          <div class="r-head">
            <div class="r-ico"><component :is="typeIcon(cur.type)" /></div>
            <div style="min-width: 0">
              <div class="row" style="gap: 8px">
                <span class="r-name">{{ cur.name }}</span>
                <span class="tag">{{ PLUGIN_TYPE[cur.type] }}</span>
                <span class="tag" :class="cur.enabled ? 'tag-green' : ''">{{ cur.enabled ? '启用' : '停用' }}</span>
              </div>
              <div class="small muted" style="margin-top: 3px">{{ cur.description || '暂无插件描述' }}</div>
              <div class="mono tiny faint ellipsis" style="margin-top: 2px; max-width: 560px">{{ cur.endpoint }}</div>
            </div>
            <div class="spacer" />
            <template v-if="canEdit">
              <Switch :model-value="cur.enabled" size="sm" @change="togglePlugin" />
              <button class="btn" @click="openEdit"><Pencil /> 编辑插件</button>
              <button v-if="cur.type === 'mcp'" class="btn" :disabled="discovering" @click="rediscover"><Loader2 v-if="discovering" class="spin" /><RefreshCw v-else /> 发现工具</button>
              <template v-else>
                <button class="btn" @click="openAdd"><Plus /> {{ cur.type === 'cloudlog' ? '新增查询工具' : '新增接口' }}</button>
                <button v-if="cur.type === 'openapi'" class="btn" @click="specText = ''; importOpen = true"><FileJson /> 导入 OpenAPI</button>
              </template>
              <button class="btn btn-danger" @click="removePlugin">删除</button>
            </template>
          </div>
          <div class="r-body">
            <div v-for="t in cur.tools" :key="t.id" class="tool card">
              <div class="tl-ico"><Zap /></div>
              <div style="flex: 1; min-width: 0">
                <div class="row" style="gap: 6px">
                  <span class="mono bold">{{ t.name }}</span>
                  <span class="tag tag-round" :class="RISK[t.riskLevel]?.tone"><span class="dot" /> {{ RISK[t.riskLevel]?.label }}</span>
                  <span class="tag tag-round tag-orange">{{ t.scope === 'internal' ? '内部' : '外部' }}</span>
                  <span v-if="t.httpConfig?.method" class="tag tag-mono">{{ t.httpConfig.method }} {{ t.httpConfig.path }}</span>
                </div>
                <div class="small muted clamp-2" style="margin-top: 4px">{{ t.description || '无描述' }}</div>
              </div>
              <Switch :model-value="t.enabled" :disabled="!canEdit" @change="(v) => toggleTool(t, v)" />
              <button class="btn btn-sm" :disabled="!canEdit" @click="debugTool = t"><Play /> 调试</button>
              <DropdownMenu v-if="canEdit" :items="toolMenu(t)" />
            </div>
            <Empty v-if="!cur.tools.length" :icon="Wrench" title="暂无工具" :desc="cur.type === 'mcp' ? '点击「发现工具」从 MCP Server 同步工具列表' : '该插件还没有可用的步骤或工具。'" />
          </div>
        </template>
        <Empty v-else :icon="Plug" title="选择左侧插件查看工具" />
      </section>
    </div>

    <Drawer :open="editOpen" title="编辑插件" :subtitle="cur?.endpoint" width="520px" @close="editOpen = false">
      <div class="field"><label>名称</label><input v-model="ef.name" class="input" /></div>
      <div class="field"><label>描述</label><textarea v-model="ef.description" class="textarea" /></div>
      <template v-if="cur?.type !== 'cloudlog'">
        <div class="field"><label>Endpoint URL</label><input v-model="ef.endpoint" class="input mono" /></div>
        <div v-if="cur?.type === 'mcp'" class="field"><label>传输协议</label><Select v-model="ef.transport" :options="[{ label: 'Streamable HTTP', value: 'streamable-http' }, { label: 'SSE（旧版）', value: 'sse' }]" /></div>
        <div v-else class="form-row">
          <div class="field"><label>Base Path</label><input v-model="ef.config.basePath" class="input mono" /></div>
          <div class="field"><label>默认超时（毫秒）</label><input v-model.number="ef.config.timeoutMs" type="number" class="input" /></div>
        </div>
        <div class="field"><label>鉴权模式</label><Select v-model="ef.authMode" :options="[{ label: '无需鉴权', value: 'none' }, { label: 'Bearer Token', value: 'bearer' }, { label: '自定义 Header', value: 'header' }]" /></div>
        <div v-if="ef.authMode === 'bearer'" class="field"><label>Bearer Token</label><input v-model="ef.bearerToken" class="input mono" type="password" placeholder="留空保持不变" /></div>
        <div class="field">
          <label>请求 Header <button class="btn btn-sm" style="margin-left: auto" @click="ef.headers.push({ name: '', value: '' })"><Plus /> 添加</button></label>
          <div v-for="(h, i) in ef.headers" :key="i" class="hrow">
            <input v-model="h.name" class="input input-sm mono" placeholder="Header 名" />
            <input v-model="h.value" class="input input-sm mono" placeholder="值" />
            <button class="icon-btn sm danger" @click="ef.headers.splice(i, 1)"><Trash2 /></button>
          </div>
          <div v-if="!ef.headers.length" class="empty-box" style="padding: 10px">未配置请求 Header</div>
        </div>
      </template>
      <template v-else>
        <div class="field"><label>默认超时（毫秒）</label><input v-model.number="ef.config.timeoutMs" type="number" class="input" /></div>
        <div class="field"><label>Endpoint</label><input v-model="ef.config.endpoint" class="input mono" /></div>
      </template>
      <template #footer>
        <button class="btn" @click="editOpen = false">取消</button>
        <button class="btn btn-primary" @click="saveEdit">保存</button>
      </template>
    </Drawer>

    <Modal :open="addOpen" :title="cur?.type === 'cloudlog' ? '新增日志查询工具' : '新增 HTTP 接口工具'" width="580px" @close="addOpen = false">
      <div class="form-row">
        <div class="field"><label>工具名 <span class="req">*</span></label><input v-model="af.name" class="input mono" placeholder="query_order" /></div>
        <div class="field"><label>风险等级</label><Select v-model="af.riskLevel" :options="[{ label: '低风险', value: 'low' }, { label: '中风险', value: 'medium' }, { label: '高风险', value: 'high' }]" /></div>
      </div>
      <div class="field"><label>描述</label><input v-model="af.description" class="input" placeholder="告诉模型这个工具做什么、什么时候用" /></div>
      <template v-if="cur?.type === 'cloudlog'">
        <div class="form-row">
          <div class="field"><label>Project <span class="req">*</span></label><input v-model="af.project" class="input mono" /></div>
          <div class="field"><label>Logstore <span class="req">*</span></label><input v-model="af.logstore" class="input mono" /></div>
        </div>
        <div class="form-row">
          <div class="field"><label>默认查询语句</label><input v-model="af.query" class="input mono" /></div>
          <div class="field"><label>默认时间范围（分钟）</label><input v-model.number="af.minutes" type="number" class="input" /></div>
        </div>
      </template>
      <template v-else>
        <div class="form-row">
          <div class="field"><label>Method</label><Select v-model="af.method" :options="['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map((m) => ({ label: m, value: m }))" /></div>
          <div class="field"><label>Path</label><input v-model="af.path" class="input mono" placeholder="/orders/{id}" /></div>
        </div>
        <div class="field"><label>参数 JSON Schema</label><textarea v-model="af.schema" class="textarea mono" rows="7" /><span class="hint">GET/DELETE 参数作为 query，其余作为 JSON body；路径中的 {id} 会被同名参数替换</span></div>
      </template>
      <template #footer>
        <button class="btn" @click="addOpen = false">取消</button>
        <button class="btn btn-primary" @click="saveAdd">添加</button>
      </template>
    </Modal>

    <Modal :open="importOpen" title="导入 OpenAPI" subtitle="粘贴或上传 OpenAPI 3 JSON，每个接口（operation）生成一个工具。" width="640px" @close="importOpen = false">
      <input :key="String(importOpen)" type="file" accept=".json" @change="onSpecFile" />
      <textarea v-model="specText" class="textarea mono" rows="14" style="margin-top: 10px" placeholder='{"openapi": "3.0.0", "paths": { ... }}' />
      <template #footer>
        <button class="btn" @click="importOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="!specText.trim()" @click="doImport">导入</button>
      </template>
    </Modal>

    <Modal :open="!!descTool" title="编辑工具描述" width="520px" @close="descTool = null">
      <textarea v-model="descText" class="textarea" rows="5" />
      <template #footer><button class="btn" @click="descTool = null">取消</button><button class="btn btn-primary" @click="saveDesc">保存</button></template>
    </Modal>

    <ToolDebugModal :plugin="cur" :tool="debugTool" @close="debugTool = null" />
  </div>
</template>

<style scoped>
.tools-page { display: flex; flex-direction: column; height: 100%; }
.split { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.left { width: 280px; flex-shrink: 0; border-right: 1px solid var(--border); display: flex; flex-direction: column; }
.l-search { display: flex; gap: 6px; padding: 10px; border-bottom: 1px solid var(--divider); }
.ls-box { flex: 1; display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 10px; border: 1px solid var(--border); border-radius: 9px; }
.ls-box svg { width: 14px; height: 14px; color: var(--text-4); }
.ls-box input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 12.5px; }
.tree { flex: 1; overflow: auto; padding: 6px; }
.t-row { display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 8px; border-radius: 8px; cursor: pointer; font-size: 12.5px; }
.t-row:hover { background: var(--bg-hover); }
.t-row.active { background: var(--bg-active); }
.t-chev { width: 13px; height: 13px; color: var(--text-4); transition: transform .15s; flex-shrink: 0; }
.t-chev.rot { transform: rotate(90deg); }
.t-ico { width: 14px; height: 14px; flex-shrink: 0; color: var(--purple); }
.t-ico.openapi { color: var(--primary); }
.t-ico.cloudlog { color: var(--cyan); }
.t-name { font-family: var(--mono); font-weight: 600; }
.t-tools { padding-left: 34px; }
.t-tool { display: flex; align-items: center; gap: 6px; height: 26px; font-size: 12px; font-family: var(--mono); color: var(--text-2); }
.t-tool svg { width: 11px; height: 11px; color: var(--primary); }
.t-tool.off { color: var(--text-4); }
.right { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.r-head { display: flex; align-items: flex-start; gap: 12px; padding: 14px 16px; border-bottom: 1px solid var(--divider); flex-wrap: wrap; }
.r-ico { width: 36px; height: 36px; border-radius: 10px; background: var(--purple-soft); color: var(--purple-text); display: flex; align-items: center; justify-content: center; }
.r-ico svg { width: 18px; height: 18px; }
.r-name { font-family: var(--mono); font-weight: 700; font-size: 15px; }
.r-body { flex: 1; overflow: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 10px; }
.tool { display: flex; align-items: center; gap: 12px; padding: 14px; }
.tl-ico { width: 32px; height: 32px; border-radius: 9px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.tl-ico svg { width: 15px; height: 15px; }
.hrow { display: grid; grid-template-columns: 1fr 1fr 28px; gap: 6px; margin-bottom: 6px; }
</style>
