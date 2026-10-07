<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Webhook, Plus, Info, Trash2, ChevronRight, Save, Loader2 } from 'lucide-vue-next'
import { hookApi } from '@/api/skill'
import { useAuth } from '@/stores/auth'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import { confirm, toast } from '@/components/ui/toast'
import { ago } from '@/utils/format'

const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('plugin:edit'))

const EVENTS = [
  { key: 'PreCompact', desc: '上下文压缩前执行', tip: '适合记录或备份上下文' },
  { key: 'Stop', desc: '一次 Claude 回复结束时执行', tip: '适合审计、通知或收尾处理' },
  { key: 'PreToolUse', desc: '工具执行前调用', tip: '退出码 2 或返回 {"decision":"block"} 可阻止本次工具调用' },
  { key: 'PostToolUse', desc: '工具执行成功后调用', tip: '适合记录、格式化或校验工具结果' },
  { key: 'PostToolUseFailure', desc: '工具执行失败后调用', tip: '适合失败告警与诊断' },
]

const list = ref<any[]>([])
const cur = ref<any>(null) // {id?, name, description, runtime, config}
const open = ref<Set<string>>(new Set(['Stop']))
const saving = ref(false)

function blank() {
  return { id: null, name: '', description: '', runtime: 'claudecode', config: { allow: [] as string[], deny: [] as string[], hooks: {} as Record<string, any[]> } }
}
function normalize(h: any) {
  const c = JSON.parse(JSON.stringify(h.config || {}))
  c.allow = c.allow || []
  c.deny = c.deny || []
  c.hooks = c.hooks || {}
  return { id: h.id, name: h.name, description: h.description || '', runtime: h.runtime || 'claudecode', config: c, version: h.version }
}
async function load() {
  list.value = await hookApi.list()
}
function select(h: any) { cur.value = normalize(h) }
function create() { cur.value = blank() }
onMounted(async () => {
  await load()
  cur.value = list.value[0] ? normalize(list.value[0]) : blank()
})

function groups(ev: string) { return cur.value.config.hooks[ev] || [] }
function addGroup(ev: string) {
  if (!cur.value.config.hooks[ev]) cur.value.config.hooks[ev] = []
  cur.value.config.hooks[ev].push({ matcher: '', handlers: [newHandler()] })
  const s = new Set(open.value); s.add(ev); open.value = s
}
function newHandler() { return { type: 'command', command: '', shell: 'default', timeout: 0, args: [] as string[], async: false, url: '', method: 'POST', headers: [] as any[] } }
function removeGroup(ev: string, i: number) {
  cur.value.config.hooks[ev].splice(i, 1)
  if (!cur.value.config.hooks[ev].length) delete cur.value.config.hooks[ev]
}
function toggle(ev: string) {
  const s = new Set(open.value)
  s.has(ev) ? s.delete(ev) : s.add(ev)
  open.value = s
}

async function save() {
  const c = cur.value
  if (!c.name.trim()) return toast.warning('请填写名称')
  const data = { name: c.name, description: c.description, runtime: c.runtime, config: { ...c.config, allow: c.config.allow.filter(Boolean), deny: c.config.deny.filter(Boolean) } }
  saving.value = true
  try {
    const r = c.id ? await hookApi.update(c.id, data) : await hookApi.create(data)
    toast.success(c.id ? `已保存为 v${r.version}` : 'Hook 已创建')
    await load()
    select(r)
  } finally {
    saving.value = false
  }
}
async function remove() {
  if (!(await confirm({ title: `删除 Hook「${cur.value.name}」？`, message: '已绑定该 Hook 的 Agent 将不再执行它。', danger: true, okText: '删除' }))) return
  await hookApi.remove(cur.value.id)
  await load()
  cur.value = list.value[0] ? normalize(list.value[0]) : blank()
}
</script>

<template>
  <div class="page hooks">
    <div class="page-head">
      <div class="row" style="gap: 10px; align-items: flex-start">
        <button class="icon-btn" @click="router.back()"><ArrowLeft /></button>
        <div>
          <h1 class="page-title"><Webhook style="width: 20px; color: var(--primary)" /> Hooks</h1>
          <div class="page-sub">按运行时创建可被多个 Agent 复用的 Hook 配置，更新会生成新版本。</div>
        </div>
      </div>
      <button v-if="canEdit" class="btn" @click="create"><Plus /> 新建 Hook</button>
    </div>

    <div class="layout">
      <aside class="card list">
        <div class="row" style="justify-content: space-between"><span class="bold" style="font-size: 15px">Hook 列表</span><span class="kbd">{{ list.length }}</span></div>
        <div class="small muted" style="margin: 4px 0 12px">运行时、版本与配置摘要</div>
        <button v-for="h in list" :key="h.id" class="h-item" :class="{ active: cur?.id === h.id }" @click="select(h)">
          <div class="row" style="justify-content: space-between"><span class="bold ellipsis">{{ h.name }}</span><span class="tag tag-mono">v{{ h.version }}</span></div>
          <div class="tiny muted ellipsis">Claude Code · {{ Object.keys(h.config?.hooks || {}).join(' / ') || '仅权限规则' }}</div>
          <div class="tiny faint">更新于 {{ ago(h.updatedAt, false) }}</div>
        </button>
        <div v-if="!list.length" class="small muted">暂无 Hook，点击"新建 Hook"开始配置。</div>
      </aside>

      <section v-if="cur" class="card editor">
        <span class="tag">{{ cur.id ? `编辑 Hook · v${cur.version}` : '新建 Hook' }}</span>
        <h2 class="ed-title">{{ cur.id ? cur.name : '创建可复用 Hook' }}</h2>
        <p class="small muted" style="margin-bottom: 14px">填写名称和配置，创建后可绑定到 Claude Code Agent。</p>
        <div class="row" style="gap: 14px; align-items: flex-end">
          <div class="field" style="flex: 1"><label>名称</label><input v-model="cur.name" class="input" placeholder="例如：审计回调" :disabled="!canEdit" /></div>
          <div class="field" style="width: 220px"><label>运行时</label><Select v-model="cur.runtime" :options="[{ label: 'Claude Code', value: 'claudecode' }]" /></div>
        </div>
        <div class="field"><label>描述</label><input v-model="cur.description" class="input" placeholder="说明该 Hook 的用途" :disabled="!canEdit" /></div>

        <div class="cfg">
          <div class="bold" style="margin-bottom: 10px">Claude Code Hook 配置</div>
          <div class="notice notice-info" style="margin-bottom: 14px"><Info /> 在需要的事件下添加匹配组，可点击 Matcher 示例限定触发范围，再配置命令或 HTTP 处理器。冒烟测试建议从 Stop 或 Bash 的 PostToolUse 开始。</div>
          <div class="rules">
            <div v-for="kind in ['allow', 'deny']" :key="kind" class="rule-col">
              <div class="row" style="justify-content: space-between">
                <span class="small bold">{{ kind === 'allow' ? 'Allow 规则' : 'Deny 规则' }}</span>
                <button class="btn btn-sm" :disabled="!canEdit" @click="cur.config[kind].push('')"><Plus /> 添加规则</button>
              </div>
              <div class="tiny muted" style="margin: 6px 0 8px">{{ kind === 'allow' ? '允许项示例：Bash(git status:*)、Read(./**)。' : '拒绝项优先用于限制敏感路径或危险命令，例如 Read(/etc/**)。' }}</div>
              <div v-for="(r, i) in cur.config[kind]" :key="i" class="row" style="margin-bottom: 6px">
                <input v-model="cur.config[kind][i]" class="input input-sm mono" :placeholder="kind === 'allow' ? 'Read(./**)' : 'Bash(rm:*)'" />
                <button class="icon-btn sm danger" @click="cur.config[kind].splice(i, 1)"><Trash2 /></button>
              </div>
            </div>
          </div>

          <div class="bold small" style="margin: 16px 0 2px">Hooks</div>
          <div class="tiny muted" style="margin-bottom: 10px">HTTP 处理器可选携带 CloudAI 上下文；命令脚本必须存在于会话工作区中（事件 JSON 通过 stdin 传入）。</div>
          <div class="events">
            <div v-for="ev in EVENTS" :key="ev.key" class="ev" :class="{ wide: open.has(ev.key) && groups(ev.key).length }">
              <div class="ev-head" @click="toggle(ev.key)">
                <ChevronRight class="ev-chev" :class="{ rot: open.has(ev.key) }" />
                <div style="flex: 1">
                  <div class="row" style="gap: 6px"><span class="mono bold">{{ ev.key }}</span><span class="kbd">{{ groups(ev.key).length }} 组</span></div>
                  <div class="tiny muted">{{ ev.desc }}</div>
                </div>
                <button class="btn btn-sm" :disabled="!canEdit" @click.stop="addGroup(ev.key)"><Plus /> 添加匹配组</button>
              </div>
              <div v-if="open.has(ev.key) && groups(ev.key).length" class="ev-body">
                <div class="tiny muted" style="margin-bottom: 8px">{{ ev.tip }}</div>
                <div v-for="(g, gi) in groups(ev.key)" :key="gi" class="grp">
                  <div class="row" style="gap: 8px; margin-bottom: 10px">
                    <span class="bold">匹配组 {{ gi + 1 }}</span>
                    <span v-if="!g.matcher" class="tag">全部触发</span>
                    <span class="small muted">{{ g.handlers.length }} 个处理器</span>
                    <div class="spacer" />
                    <button class="btn btn-ghost btn-sm" @click="removeGroup(ev.key, gi)"><Trash2 /> 删除匹配组</button>
                  </div>
                  <div v-if="ev.key.includes('Tool')" class="field">
                    <label>Matcher（工具名，支持正则；留空匹配全部）</label>
                    <input v-model="g.matcher" class="input input-sm mono" placeholder="Bash|Write" />
                    <div class="row" style="gap: 4px; flex-wrap: wrap">
                      <button v-for="m in ['Bash', 'Write|Edit', 'Read', 'mcp__.*']" :key="m" class="tag tag-outline" style="cursor: pointer" @click="g.matcher = m">{{ m }}</button>
                    </div>
                  </div>
                  <div v-for="(h, hi) in g.handlers" :key="hi" class="handler">
                    <div class="row" style="gap: 8px; margin-bottom: 10px">
                      <span class="bold small">处理器 {{ hi + 1 }}</span><span class="tag">{{ h.type === 'http' ? 'HTTP' : '命令' }}</span>
                      <div class="spacer" />
                      <button class="btn btn-ghost btn-sm" :disabled="g.handlers.length === 1" @click="g.handlers.splice(hi, 1)"><Trash2 /> 删除处理器</button>
                    </div>
                    <div class="field"><label>处理器类型</label><Select v-model="h.type" :options="[{ label: '命令', value: 'command' }, { label: 'HTTP', value: 'http' }]" /></div>
                    <div class="form-row">
                      <div>
                        <div class="field"><label>超时（秒）</label><input v-model.number="h.timeout" type="number" min="0" class="input" /><span class="hint">新建 HTTP 处理器默认为 5 秒；0 表示使用默认值。</span></div>
                        <div v-if="h.type === 'command'" class="field"><label>Shell</label><Select v-model="h.shell" :options="[{ label: '默认', value: 'default' }, { label: 'bash', value: 'bash' }, { label: 'sh', value: 'sh' }, { label: 'zsh', value: 'zsh' }]" /></div>
                        <div v-else class="field"><label>Method</label><Select v-model="h.method" :options="[{ label: 'POST', value: 'POST' }, { label: 'PUT', value: 'PUT' }]" /></div>
                      </div>
                      <div>
                        <div v-if="h.type === 'command'" class="field"><label>命令</label><textarea v-model="h.command" class="textarea mono" rows="3" placeholder="bash .claude/hooks/audit.sh" /><span class="hint">从会话工作区执行，Hook 事件 JSON 通过 stdin 传入。</span></div>
                        <div v-else class="field"><label>URL</label><input v-model="h.url" class="input mono" placeholder="https://hooks.example.com/audit" /></div>
                      </div>
                    </div>
                    <div v-if="h.type === 'command'" class="field">
                      <label>参数 <button class="btn btn-sm" style="margin-left: auto" @click="h.args.push('')"><Plus /> 添加参数</button></label>
                      <div v-for="(a, ai) in h.args" :key="ai" class="row" style="margin-bottom: 4px"><input v-model="h.args[ai]" class="input input-sm mono" /><button class="icon-btn sm danger" @click="h.args.splice(ai, 1)"><Trash2 /></button></div>
                    </div>
                    <div v-else class="field">
                      <label>Headers <button class="btn btn-sm" style="margin-left: auto" @click="h.headers.push({ name: '', value: '' })"><Plus /> 添加</button></label>
                      <div v-for="(hd, hdi) in h.headers" :key="hdi" class="row" style="margin-bottom: 4px"><input v-model="hd.name" class="input input-sm mono" placeholder="名称" /><input v-model="hd.value" class="input input-sm mono" placeholder="值" /><button class="icon-btn sm danger" @click="h.headers.splice(hdi, 1)"><Trash2 /></button></div>
                    </div>
                    <div class="async">
                      <div><div class="bold small">异步执行</div><div class="tiny muted">仅适合审计/通知；需要影响当前流程时请保持关闭。</div></div>
                      <Switch v-model="h.async" />
                    </div>
                  </div>
                  <button class="btn btn-sm" @click="g.handlers.push(newHandler())"><Plus /> 添加处理器</button>
                  <div class="tiny muted" style="margin-top: 6px">每组至少保留一个处理器；不需要此组时，请删除匹配组。</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="canEdit" class="row" style="justify-content: flex-end; gap: 8px; margin-top: 16px">
          <button v-if="cur.id" class="btn btn-danger-ghost" @click="remove"><Trash2 /> 删除</button>
          <button class="btn btn-primary" :disabled="saving" @click="save"><Loader2 v-if="saving" class="spin" /><Save v-else /> {{ cur.id ? '保存（生成新版本）' : '创建 Hook' }}</button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hooks { max-width: 1260px; margin: 0 auto; }
.layout { display: grid; grid-template-columns: 300px 1fr; gap: 20px; align-items: start; }
.list { padding: 16px; }
.h-item { width: 100%; text-align: left; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elev); cursor: pointer; margin-bottom: 8px; display: flex; flex-direction: column; gap: 2px; }
.h-item.active { border-color: var(--primary); background: var(--primary-soft); }
.editor { padding: 18px 20px; }
.ed-title { font-size: 16px; margin-top: 10px; }
.cfg { border: 1px solid var(--border); border-radius: 14px; padding: 14px 16px; background: var(--bg-muted); }
.rules { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; padding-bottom: 14px; border-bottom: 1px solid var(--border); }
.events { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.ev { border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elev); }
.ev.wide { grid-column: 1 / -1; }
.ev-head { display: flex; align-items: center; gap: 10px; padding: 10px 12px; cursor: pointer; }
.ev-chev { width: 14px; height: 14px; color: var(--text-3); transition: transform .15s; }
.ev-chev.rot { transform: rotate(90deg); }
.ev-body { padding: 4px 12px 12px; border-top: 1px solid var(--divider); }
.grp { border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; margin-top: 8px; }
.handler { border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; margin-bottom: 10px; background: var(--bg-muted); }
.async { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elev); }
</style>
