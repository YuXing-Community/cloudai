<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ChevronRight, Server, Globe, ScrollText, FileJson, Plus, Trash2, Zap, CheckCircle2, Loader2, KeyRound, Info } from 'lucide-vue-next'
import { pluginApi, credentialApi, CLOUD_VENDORS } from '@/api/plugin'
import Select from '@/components/ui/Select.vue'
import Modal from '@/components/ui/Modal.vue'
import { toast } from '@/components/ui/toast'

const router = useRouter()
const step = ref<1 | 2>(1)
const type = ref<'mcp' | 'openapi' | 'cloudlog'>('mcp')
const TYPES = [
  { key: 'mcp', title: 'MCP Server', desc: '连接外部 MCP Server，自动发现并导入工具能力', badge: '推荐', tone: 'tag-purple', icon: Server },
  { key: 'openapi', title: 'OpenAPI', desc: '配置 HTTP 服务，再手动新增接口或导入 OpenAPI 生成工具', badge: 'OpenAPI', tone: 'tag-blue', icon: Globe },
  { key: 'cloudlog', title: '云日志', desc: '接入阿里云 SLS、火山云 TLS 或华为云 LTS，再为每个日志源新增查询工具', badge: 'SLS/TLS/LTS', tone: 'tag-cyan', icon: ScrollText },
] as const

const f = ref<any>({ name: '', description: '', endpoint: '', transport: 'streamable-http', authMode: 'none', bearerToken: '', headers: [] as any[],
  pluginKey: '', basePath: '', timeoutMs: 30000, vendor: 'aliyun_sls', credentialId: null })
const tools = ref<any[] | null>(null)
const testing = ref(false)
const saving = ref(false)
const creds = ref<any[]>([])

function pick(t: any) {
  type.value = t
  step.value = 2
  tools.value = null
  if (t === 'cloudlog') loadCreds()
}
async function loadCreds() {
  creds.value = await credentialApi.options('cloudlog')
  if (!f.value.credentialId && creds.value.length) f.value.credentialId = creds.value[0].id
}
const vendorEndpoint = computed(() => CLOUD_VENDORS.find((v) => v.value === f.value.vendor)?.endpoint)

// 导入 MCP JSON：{"mcpServers": {"name": {"url": "...", "headers": {...}}}} 或 {"url": ...}
const jsonOpen = ref(false)
const jsonText = ref('')
function applyJson() {
  try {
    const j = JSON.parse(jsonText.value)
    let name = ''
    let cfg = j
    if (j.mcpServers) {
      name = Object.keys(j.mcpServers)[0]
      cfg = j.mcpServers[name]
    }
    f.value.name = f.value.name || name
    f.value.endpoint = cfg.url || cfg.endpoint || cfg.serverUrl || ''
    f.value.transport = (cfg.type || cfg.transport || '').includes('sse') ? 'sse' : 'streamable-http'
    f.value.headers = Object.entries(cfg.headers || {}).map(([name, value]) => ({ name, value }))
    const auth = f.value.headers.find((h: any) => h.name.toLowerCase() === 'authorization')
    if (auth && /^Bearer /i.test(auth.value)) {
      f.value.authMode = 'bearer'
      f.value.bearerToken = auth.value.replace(/^Bearer /i, '')
      f.value.headers = f.value.headers.filter((h: any) => h !== auth)
    }
    jsonOpen.value = false
    toast.success('已导入配置')
  } catch {
    toast.error('JSON 格式不正确')
  }
}

async function test() {
  if (!/^https?:\/\//.test(f.value.endpoint)) return toast.warning('请填写正确的 Endpoint URL')
  testing.value = true
  tools.value = null
  try {
    const r = await pluginApi.discover({ endpoint: f.value.endpoint, transport: f.value.transport, authMode: f.value.authMode, bearerToken: f.value.bearerToken, headers: f.value.headers })
    tools.value = r.tools
    toast.success(`连接成功，发现 ${r.tools.length} 个工具`)
  } finally {
    testing.value = false
  }
}

async function save() {
  const v = f.value
  if (!v.name.trim()) return toast.warning('请填写显示名称')
  saving.value = true
  try {
    let data: any
    if (type.value === 'mcp') data = { type: 'mcp', name: v.name, description: v.description, endpoint: v.endpoint, transport: v.transport, authMode: v.authMode, bearerToken: v.bearerToken, headers: v.headers, tools: tools.value }
    else if (type.value === 'openapi') data = { type: 'openapi', pluginKey: v.pluginKey, name: v.name, description: v.description, endpoint: v.endpoint, authMode: v.authMode, bearerToken: v.bearerToken, headers: v.headers, config: { basePath: v.basePath, timeoutMs: v.timeoutMs } }
    else {
      if (!v.credentialId) return toast.warning('请选择或新建 AK/SK 凭证')
      data = { type: 'cloudlog', name: v.name, description: v.description, config: { vendor: v.vendor, endpoint: vendorEndpoint.value, timeoutMs: v.timeoutMs, credentialId: v.credentialId } }
    }
    const r = await pluginApi.create(data)
    toast.success('插件已创建')
    router.push({ path: '/plugins/tools', query: { id: r.id } })
  } finally {
    saving.value = false
  }
}

// 新建云日志凭证
const credOpen = ref(false)
const nc = ref({ name: '', ak: '', sk: '' })
async function createCred() {
  if (!nc.value.name || !nc.value.ak || !nc.value.sk) return toast.warning('请完整填写')
  const c = await credentialApi.create({ name: nc.value.name, type: 'cloudlog', fields: [
    { name: 'access_key_id', envName: 'ACCESS_KEY_ID', value: nc.value.ak }, { name: 'access_key_secret', envName: 'ACCESS_KEY_SECRET', value: nc.value.sk }] })
  credOpen.value = false
  nc.value = { name: '', ak: '', sk: '' }
  await loadCreds()
  f.value.credentialId = c.id
}
onMounted(() => {})
</script>

<template>
  <div class="page page-narrow create">
    <div class="row" style="gap: 12px; margin-bottom: 14px">
      <button class="icon-btn" @click="step === 2 ? (step = 1) : router.push('/plugins/tools')"><ArrowLeft /></button>
      <div>
        <h1 class="page-title">新增插件</h1>
        <div class="page-sub">{{ step === 1 ? '选择插件类型' : `配置 ${TYPES.find((t) => t.key === type)?.title} 插件` }}</div>
      </div>
    </div>
    <div class="steps">
      <span class="st" :class="{ on: step === 1 }">1. 选择类型</span>
      <ChevronRight class="st-sep" />
      <span class="st" :class="{ on: step === 2 }">2. 填写配置</span>
    </div>

    <div v-if="step === 1" class="types">
      <button v-for="t in TYPES" :key="t.key" class="type card" @click="pick(t.key)">
        <div class="row" style="justify-content: space-between; align-items: flex-start">
          <div class="ty-ico" :class="t.key"><component :is="t.icon" /></div>
          <span class="tag tag-round" :class="t.tone">{{ t.badge }}</span>
        </div>
        <div class="ty-title">{{ t.title }}</div>
        <div class="small muted">{{ t.desc }}</div>
        <ChevronRight class="ty-go" />
      </button>
    </div>

    <template v-else>
      <section class="card form-card">
        <div v-if="type === 'openapi'" class="form-row">
          <div class="field"><label>插件 Key <span class="req">*</span></label><input v-model="f.pluginKey" class="input mono" placeholder="例如：order_service" /><span class="hint">用于生成工具别名前缀，仅保留字母、数字、下划线和短横线。</span></div>
          <div class="field"><label>显示名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="插件显示名称" /></div>
        </div>
        <div v-else class="field" style="max-width: 360px"><label>显示名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="插件显示名称" /></div>
        <div class="field" style="margin-bottom: 0"><label>描述</label><input v-model="f.description" class="input" placeholder="插件功能描述（可选）" /></div>
      </section>

      <!-- MCP -->
      <section v-if="type === 'mcp'" class="card form-card">
        <div class="row" style="justify-content: space-between; align-items: flex-start; margin-bottom: 14px">
          <div><div class="section-title" style="font-size: 15px">连接配置</div><div class="section-sub">配置 MCP Server 连接参数，测试通过后保存</div></div>
          <button class="btn" @click="jsonOpen = true"><FileJson /> 导入 MCP JSON 配置</button>
        </div>
        <div class="field"><label>Endpoint URL <span class="req">*</span></label><input v-model="f.endpoint" class="input mono" placeholder="https://mcp.example.com/mcp" /></div>
        <div class="form-row">
          <div class="field"><label>传输协议</label><Select v-model="f.transport" :options="[{ label: 'Streamable HTTP', value: 'streamable-http' }, { label: 'SSE（旧版）', value: 'sse' }]" /></div>
          <div class="field"><label>鉴权模式</label><Select v-model="f.authMode" :options="[{ label: '无需鉴权', value: 'none' }, { label: 'Bearer Token', value: 'bearer' }, { label: '自定义 Header', value: 'header' }]" /></div>
        </div>
        <div v-if="f.authMode === 'bearer'" class="field"><label>Bearer Token</label><input v-model="f.bearerToken" class="input mono" type="password" /></div>
        <div class="field">
          <label>自定义请求头 <button class="link-btn small" style="margin-left: auto; color: var(--text-2)" @click="f.headers.push({ name: '', value: '' })"><Plus style="width: 13px" /> 添加</button></label>
          <div v-for="(h, i) in f.headers" :key="i" class="hrow">
            <input v-model="h.name" class="input mono" placeholder="Header 名" /><input v-model="h.value" class="input mono" placeholder="值" />
            <button class="icon-btn danger" @click="f.headers.splice(i, 1)"><Trash2 /></button>
          </div>
          <span v-if="!f.headers.length" class="small muted">无自定义请求头</span>
        </div>
        <button class="btn btn-primary btn-block btn-lg" :disabled="testing || !f.endpoint" @click="test"><Loader2 v-if="testing" class="spin" /><Zap v-else /> 测试连接 & 获取工具列表</button>
        <div v-if="tools" class="found">
          <div class="row small bold" style="gap: 6px; margin-bottom: 8px"><CheckCircle2 style="width: 15px; color: var(--green)" /> 发现 {{ tools.length }} 个工具</div>
          <div v-for="t in tools" :key="t.name" class="ft"><Zap /><span class="mono bold">{{ t.name }}</span><span class="small muted ellipsis">{{ t.description }}</span></div>
        </div>
      </section>

      <!-- OpenAPI -->
      <section v-if="type === 'openapi'" class="card form-card">
        <div class="section-title" style="font-size: 15px; margin-bottom: 14px">连接配置</div>
        <div class="field"><label>Endpoint URL <span class="req">*</span></label><input v-model="f.endpoint" class="input mono" placeholder="https://api.example.com" /></div>
        <div class="form-row-3">
          <div class="field"><label>鉴权方式</label><Select v-model="f.authMode" :options="[{ label: '无鉴权', value: 'none' }, { label: 'Bearer Token', value: 'bearer' }, { label: '自定义 Header', value: 'header' }]" /></div>
          <div class="field"><label>Base Path</label><input v-model="f.basePath" class="input mono" placeholder="/api/v1" /></div>
          <div class="field"><label>默认超时（毫秒）</label><input v-model.number="f.timeoutMs" type="number" class="input mono" /></div>
        </div>
        <div v-if="f.authMode === 'bearer'" class="field"><label>Bearer Token</label><input v-model="f.bearerToken" class="input mono" type="password" /></div>
        <div v-if="f.authMode === 'header'" class="field">
          <label>Header <button class="link-btn small" style="margin-left: auto" @click="f.headers.push({ name: '', value: '' })">+ 添加</button></label>
          <div v-for="(h, i) in f.headers" :key="i" class="hrow"><input v-model="h.name" class="input mono" placeholder="X-API-Key" /><input v-model="h.value" class="input mono" /><button class="icon-btn danger" @click="f.headers.splice(i, 1)"><Trash2 /></button></div>
        </div>
        <div class="notice notice-info"><Info /> 创建插件后，进入插件工具页新增 HTTP 接口工具，或导入 OpenAPI 批量生成接口。</div>
      </section>

      <!-- 云日志 -->
      <section v-if="type === 'cloudlog'" class="card form-card">
        <div class="section-title" style="font-size: 15px">凭证与基础配置</div>
        <div class="section-sub" style="margin-bottom: 14px">选择 AK/SK 凭证并配置默认超时。</div>
        <div class="form-row">
          <div class="field"><label>云厂商</label><Select v-model="f.vendor" :options="CLOUD_VENDORS" /></div>
          <div class="field"><label>Endpoint</label><input class="input mono" :value="vendorEndpoint" disabled /></div>
        </div>
        <div class="field" style="max-width: 50%"><label>默认超时（毫秒）</label><input v-model.number="f.timeoutMs" type="number" class="input mono" /></div>
        <div class="field">
          <label>凭证 <span class="req">*</span></label>
          <Select v-if="creds.length" v-model="f.credentialId" :options="creds.map((c) => ({ label: c.name, value: c.id, sub: (c.fields || []).map((x: any) => x.name).join(', ') }))" />
          <div v-else class="empty-box" style="text-align: left">还没有可用的云日志 AK/SK 凭证。</div>
        </div>
        <div class="row">
          <button class="btn" @click="credOpen = true"><Plus /> 新建凭证</button>
          <RouterLink to="/plugins/credentials" class="btn btn-ghost"><KeyRound /> 前往凭证管理</RouterLink>
        </div>
        <div v-if="f.vendor !== 'aliyun_sls'" class="notice notice-warn" style="margin-top: 12px"><Info /> 当前版本查询工具已实现阿里云 SLS；火山云 TLS / 华为云 LTS 可先创建插件，查询能力将在后续版本支持。</div>
      </section>

      <div class="row">
        <button class="btn btn-primary" :disabled="saving || (type === 'mcp' && !tools)" @click="save">
          <CheckCircle2 v-if="type === 'mcp'" /> {{ type === 'mcp' ? '保存并注册工具' : '创建插件' }}
        </button>
        <button class="btn" @click="router.push('/plugins/tools')">取消</button>
      </div>
    </template>

    <Modal :open="jsonOpen" title="导入 MCP JSON 配置" subtitle="支持 Claude Desktop / Cursor 的 mcpServers 格式" width="560px" @close="jsonOpen = false">
      <textarea v-model="jsonText" class="textarea mono" rows="10" placeholder='{"mcpServers": {"my-server": {"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer xxx"}}}}' />
      <template #footer><button class="btn" @click="jsonOpen = false">取消</button><button class="btn btn-primary" @click="applyJson">导入</button></template>
    </Modal>
    <Modal :open="credOpen" title="新建云日志凭证" width="460px" @close="credOpen = false">
      <div class="notice notice-warn" style="margin-bottom: 14px"><KeyRound /> 密钥值保存后安全加密存储，页面不会再次显示原值。</div>
      <div class="field"><label>凭证名称</label><input v-model="nc.name" class="input" placeholder="例：生产 SLS 只读账号" /></div>
      <div class="field"><label>AccessKey ID</label><input v-model="nc.ak" class="input mono" /></div>
      <div class="field"><label>AccessKey Secret</label><input v-model="nc.sk" class="input mono" type="password" /></div>
      <template #footer><button class="btn" @click="credOpen = false">取消</button><button class="btn btn-primary" @click="createCred">创建</button></template>
    </Modal>
  </div>
</template>

<style scoped>
.create { max-width: 980px; }
.steps { display: flex; align-items: center; gap: 8px; margin-bottom: 18px; }
.st { font-family: var(--mono); font-size: 12px; padding: 3px 9px; border-radius: 6px; background: var(--bg-muted); color: var(--text-3); }
.st.on { background: var(--primary); color: #fff; font-weight: 600; }
.st-sep { width: 14px; height: 14px; color: var(--text-4); }
.types { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.type { position: relative; text-align: left; padding: 18px; cursor: pointer; display: flex; flex-direction: column; gap: 8px; min-height: 160px; transition: box-shadow .15s, border-color .15s; }
.type:hover { box-shadow: var(--shadow-md); border-color: var(--primary-soft-2); }
.ty-ico { width: 42px; height: 42px; border-radius: 11px; background: var(--bg-muted); display: flex; align-items: center; justify-content: center; }
.ty-ico svg { width: 21px; height: 21px; }
.ty-ico.mcp { color: var(--purple); }
.ty-ico.openapi { color: var(--primary); }
.ty-ico.cloudlog { color: var(--cyan); }
.ty-title { font-family: var(--mono); font-weight: 700; font-size: 14px; margin-top: 6px; }
.ty-go { position: absolute; right: 16px; bottom: 16px; width: 16px; height: 16px; color: var(--text-3); }
.form-card { padding: 20px 22px; margin-bottom: 18px; }
.hrow { display: grid; grid-template-columns: 1fr 1fr 32px; gap: 8px; margin-bottom: 6px; }
.found { margin-top: 14px; padding: 12px; border: 1px solid var(--border); border-radius: 12px; max-height: 280px; overflow: auto; }
.ft { display: flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 12.5px; }
.ft svg { width: 12px; height: 12px; color: var(--primary); flex-shrink: 0; }
</style>
