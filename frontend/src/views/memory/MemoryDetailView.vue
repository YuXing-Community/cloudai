<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Search, Download, Plus, Folder, FolderOpen, FileText, ChevronRight, Pencil, Share2, Trash2, Save, X, Upload, Hash, RefreshCw, EyeOff, Eye, Loader2 } from 'lucide-vue-next'
import { memoryApi } from '@/api/memory'
import { useAuth } from '@/stores/auth'
import Switch from '@/components/ui/Switch.vue'
import Modal from '@/components/ui/Modal.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import Empty from '@/components/ui/Empty.vue'
import { confirm, toast } from '@/components/ui/toast'
import { bytes, copyText, debounce } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('memory:edit'))
const id = computed(() => route.params.id as string)

const store = ref<any>(null)
const docs = ref<any[]>([])
const q = ref('')
const showHidden = ref(false)
const collapsed = ref<Set<string>>(new Set())
const selected = ref<string>('')
const cur = ref<any>(null)
const editing = ref(false)
const draft = ref('')
const keywordsView = ref(false)
const keywords = ref('')

async function loadTree() {
  docs.value = await memoryApi.tree(id.value, showHidden.value, q.value)
}
onMounted(async () => {
  store.value = await memoryApi.get(id.value)
  await loadTree()
  const want = (route.query.path as string) || docs.value.find((d) => d.path.endsWith('overview.md'))?.path || docs.value[0]?.path
  if (want) open(want)
})
watch(q, debounce(loadTree, 300))
watch(showHidden, loadTree)

/** 由文档路径列表构建目录树（扁平渲染：目录在前，按名称排序） */
interface Node { path: string; name: string; dir: boolean; depth: number; doc?: any }
const nodes = computed<Node[]>(() => {
  const dirs = new Set<string>()
  for (const d of docs.value) {
    const parts = d.path.split('/').filter(Boolean)
    for (let i = 1; i < parts.length; i++) dirs.add('/' + parts.slice(0, i).join('/'))
  }
  const all: Node[] = [
    ...[...dirs].map((p) => ({ path: p, name: p.split('/').pop()!, dir: true, depth: p.split('/').length - 2 })),
    ...docs.value.map((d) => ({ path: d.path, name: d.path.split('/').pop()!, dir: false, depth: d.path.split('/').length - 2, doc: d })),
  ]
  const key = (n: Node) => {
    const parts = n.path.split('/').filter(Boolean)
    return parts.map((p, i) => (i === parts.length - 1 && !n.dir ? '1' : '0') + p).join('/')
  }
  all.sort((a, b) => key(a).localeCompare(key(b), 'zh'))
  return all.filter((n) => {
    const parts = n.path.split('/').filter(Boolean)
    for (let i = 1; i < parts.length; i++) if (collapsed.value.has('/' + parts.slice(0, i).join('/'))) return false
    return true
  })
})
function clickNode(n: Node) {
  if (n.dir) {
    const s = new Set(collapsed.value)
    s.has(n.path) ? s.delete(n.path) : s.add(n.path)
    collapsed.value = s
  } else open(n.path)
}
async function open(path: string) {
  if (editing.value && draft.value !== cur.value?.content && !(await confirm({ title: '放弃未保存的修改？', danger: true }))) return
  editing.value = false
  keywordsView.value = false
  selected.value = path
  cur.value = await memoryApi.doc(id.value, path)
  router.replace({ query: { path } })
}
function startEdit() {
  draft.value = cur.value?.content || ''
  editing.value = true
}
async function saveEdit() {
  await memoryApi.saveDoc(id.value, selected.value, draft.value)
  toast.success('已保存，正在重新向量化')
  editing.value = false
  cur.value = await memoryApi.doc(id.value, selected.value)
  loadTree()
}
async function remove() {
  if (!(await confirm({ title: `删除 ${selected.value}？`, message: '会同时删除向量索引。', danger: true, okText: '删除' }))) return
  await memoryApi.removeDoc(id.value, selected.value)
  cur.value = null
  selected.value = ''
  loadTree()
}
async function toggleHidden() {
  await memoryApi.hide(id.value, selected.value, !cur.value.doc.hidden)
  cur.value.doc.hidden = !cur.value.doc.hidden
  loadTree()
}
async function showKeywords() {
  keywords.value = (await memoryApi.keywords(id.value)).content
  keywordsView.value = true
  selected.value = ''
}
async function reindex() {
  await memoryApi.reindex(id.value)
  toast.success('已提交重建索引')
  setTimeout(loadTree, 1500)
}

// 新建 / 上传
const newOpen = ref(false)
const newTab = ref<'manual' | 'upload'>('manual')
const newPath = ref('')
const newContent = ref('')
const upFiles = ref<File[]>([])
const upDir = ref('')
const uploading = ref(false)
const dragging = ref(false)
const fileEl = ref<HTMLInputElement | null>(null)
function openNew(tab: 'manual' | 'upload') {
  newTab.value = tab
  newPath.value = ''
  newContent.value = ''
  upFiles.value = []
  upDir.value = ''
  newOpen.value = true
}
async function createDoc() {
  if (!newPath.value.trim()) return toast.warning('请填写文件路径')
  const p = newPath.value.startsWith('/') ? newPath.value : '/' + newPath.value
  await memoryApi.createDoc(id.value, p, newContent.value)
  toast.success('已创建')
  newOpen.value = false
  await loadTree()
  open(p)
}
async function doUpload() {
  if (!upFiles.value.length) return
  uploading.value = true
  try {
    const r = await memoryApi.upload(id.value, upFiles.value, upDir.value)
    toast.success(`已上传 ${upFiles.value.length} 个文件，正在向量化`)
    newOpen.value = false
    await loadTree()
    const md = r.find((d: any) => d.path.endsWith('.md')) || r[0]
    if (md) open(md.path)
  } finally {
    uploading.value = false
  }
}
const dot = (d: any) => ({ indexed: 'ok', failed: 'bad', pending: 'pend', skipped: 'skip' }[d.indexStatus as string] || 'skip')
const statusText: Record<string, string> = { indexed: '已向量化', failed: '向量化失败', pending: '向量化中', skipped: '不参与检索' }
</script>

<template>
  <div class="md-page">
    <div class="crumb">
      <button class="link-btn" style="color: var(--text-2)" @click="router.push('/memory')"><ArrowLeft style="width: 14px" /> 记忆库</button>
      <span class="faint">/</span><span class="bold">{{ store?.name }}</span>
    </div>
    <div class="body">
      <aside class="tree-pane">
        <div class="tp-search"><Search /><input v-model="q" placeholder="搜索文档内容..." /></div>
        <div class="tp-tools">
          <button v-if="canEdit" class="link-btn small" @click="openNew('upload')"><Download style="width: 13px" /> 导入仓库</button>
          <Switch v-model="showHidden" size="sm" label="显示隐藏文件" />
          <div class="spacer" />
          <button v-if="canEdit" class="link-btn small" @click="openNew('manual')"><Plus style="width: 13px" /> 新建</button>
        </div>
        <div class="nodes">
          <div v-for="n in nodes" :key="n.path" class="node" :class="{ active: n.path === selected, hidden: n.doc?.hidden }" :style="{ paddingLeft: 8 + n.depth * 14 + 'px' }" @click="clickNode(n)">
            <ChevronRight v-if="n.dir" class="chev" :class="{ rot: !collapsed.has(n.path) }" /><span v-else class="chev-sp" />
            <component :is="n.dir ? (collapsed.has(n.path) ? Folder : FolderOpen) : FileText" class="n-ico" :class="{ folder: n.dir }" />
            <span class="ellipsis">{{ n.name }}</span>
            <span v-if="!n.dir" class="idx" :class="dot(n.doc)" :title="statusText[n.doc.indexStatus] + (n.doc.indexError ? '：' + n.doc.indexError : '')" />
          </div>
          <Empty v-if="!nodes.length" title="暂无文档" desc="新建文档或上传文件，上传后自动向量化" compact />
        </div>
        <div class="tp-foot">
          <button class="btn btn-ghost btn-sm" @click="showKeywords"><Hash /> 关键词索引</button>
          <button v-if="canEdit" class="btn btn-ghost btn-sm" @click="reindex"><RefreshCw /> 重建索引</button>
        </div>
      </aside>

      <section class="doc-pane">
        <template v-if="keywordsView">
          <div class="dp-head"><Hash class="n-ico" /><span class="mono bold">/_index/keywords.md</span><span class="tag">自动生成</span></div>
          <div class="dp-body"><MarkdownView v-if="keywords" :source="keywords" /><Empty v-else title="暂无关键词索引" desc="文档向量化成功后会自动生成" /></div>
        </template>
        <template v-else-if="cur">
          <div class="dp-head">
            <FileText class="n-ico" />
            <div style="min-width: 0">
              <div class="row" style="gap: 6px"><span class="mono bold ellipsis">{{ selected }}</span><span class="tag tag-blue">{{ selected.split('.').pop()?.toUpperCase() }}</span>
                <span class="tag" :class="{ 'tag-green': cur.doc.indexStatus === 'indexed', 'tag-red': cur.doc.indexStatus === 'failed', 'tag-blue': cur.doc.indexStatus === 'pending' }">{{ statusText[cur.doc.indexStatus] }}{{ cur.doc.chunkCount ? ` · ${cur.doc.chunkCount} 块` : '' }}</span>
              </div>
              <div class="tiny muted">更新者: {{ cur.doc.updatedBy || 'system' }} · {{ bytes(cur.doc.size) }}</div>
            </div>
            <div class="spacer" />
            <template v-if="editing">
              <button class="btn btn-sm" @click="editing = false"><X /> 取消</button>
              <button class="btn btn-primary btn-sm" @click="saveEdit"><Save /> 保存</button>
            </template>
            <template v-else>
              <button v-if="canEdit && !cur.binary" class="btn btn-sm" @click="startEdit"><Pencil /> 编辑文档</button>
              <button class="icon-btn" title="复制路径" @click="copyText(selected); toast.success('路径已复制')"><Share2 /></button>
              <button v-if="canEdit" class="icon-btn" :title="cur.doc.hidden ? '取消隐藏' : '隐藏'" @click="toggleHidden"><Eye v-if="cur.doc.hidden" /><EyeOff v-else /></button>
              <button v-if="canEdit" class="icon-btn danger" title="删除" @click="remove"><Trash2 /></button>
            </template>
          </div>
          <div v-if="cur.doc.indexError" class="notice notice-danger" style="margin: 10px 20px 0">{{ cur.doc.indexError }}</div>
          <div class="dp-body">
            <textarea v-if="editing" v-model="draft" class="editor" spellcheck="false" />
            <div v-else-if="cur.binary" class="empty-box">二进制文件（如 Excel 原件），已自动转换为同名 .md 用于检索与浏览</div>
            <MarkdownView v-else-if="selected.endsWith('.md')" :source="cur.content" />
            <pre v-else class="code-soft">{{ cur.content }}</pre>
          </div>
        </template>
        <Empty v-else title="选择左侧文档查看" />
      </section>
    </div>

    <Modal :open="newOpen" title="新建记忆文档" width="620px" @close="newOpen = false">
      <div class="segmented block" style="margin-bottom: 16px">
        <button :class="{ active: newTab === 'manual' }" @click="newTab = 'manual'">手动创建</button>
        <button :class="{ active: newTab === 'upload' }" @click="newTab = 'upload'">上传文件</button>
      </div>
      <template v-if="newTab === 'manual'">
        <div class="field">
          <label>文件路径 <span class="req">*</span></label>
          <input v-model="newPath" class="input" placeholder="例如：/team/style.md 或 /rules.txt" />
          <span class="hint">路径中斜杠起到了层级划分作用；支持 .md/.txt/.csv/.json/.yaml/.py/.js/.ts 等文本格式。</span>
        </div>
        <div class="field"><label>内容</label><textarea v-model="newContent" class="textarea" rows="8" placeholder="在此编写初始内容（可选）..." /></div>
      </template>
      <template v-else>
        <div class="drop" :class="{ dragging }" @click="fileEl?.click()" @dragover.prevent="dragging = true" @dragleave="dragging = false"
          @drop.prevent="dragging = false; upFiles = Array.from($event.dataTransfer?.files || [])">
          <Upload class="d-ico" />
          <div class="bold">{{ upFiles.length ? `已选择 ${upFiles.length} 个文件` : '点击或拖拽文件到此处' }}</div>
          <div class="small muted">{{ upFiles.length ? upFiles.map((f) => f.name).slice(0, 3).join('、') : '支持文本文件及 Excel（.xlsx .xls .xlsm）' }}</div>
        </div>
        <input ref="fileEl" type="file" multiple hidden accept=".md,.txt,.csv,.json,.yaml,.yml,.py,.js,.ts,.sql,.xlsx,.xls,.xlsm" @change="upFiles = Array.from(($event.target as HTMLInputElement).files || [])" />
        <div class="field" style="margin-top: 14px">
          <label>存放目录（可选）</label>
          <input v-model="upDir" class="input" placeholder="例如：/team 或 /images" />
          <span class="hint">文件将保存到 &lt;目录&gt;/&lt;文件名&gt;，留空则放在根路径。</span>
        </div>
      </template>
      <template #footer>
        <button class="btn" @click="newOpen = false">取消</button>
        <button v-if="newTab === 'manual'" class="btn btn-primary" :disabled="!newPath.trim()" @click="createDoc">创建</button>
        <button v-else class="btn btn-primary" :disabled="!upFiles.length || uploading" @click="doUpload"><Loader2 v-if="uploading" class="spin" /><Upload v-else /> 上传</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.md-page { display: flex; flex-direction: column; height: 100%; }
.crumb { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--border); font-size: 13px; }
.body { flex: 1; min-height: 0; display: flex; }
.tree-pane { width: 300px; flex-shrink: 0; border-right: 1px solid var(--border); display: flex; flex-direction: column; background: var(--bg-elev); }
.tp-search { display: flex; align-items: center; gap: 6px; margin: 10px; height: 30px; padding: 0 10px; border-radius: 8px; background: var(--bg-muted); }
.tp-search svg { width: 13px; height: 13px; color: var(--text-4); }
.tp-search input { flex: 1; min-width: 0; border: none; outline: none; background: transparent; font-size: 12.5px; }
.tp-tools { display: flex; align-items: center; gap: 10px; padding: 0 12px 8px; }
.tp-tools .link-btn { color: var(--text-2); }
.nodes { flex: 1; overflow: auto; padding: 0 6px 10px; }
.node { display: flex; align-items: center; gap: 5px; height: 28px; padding-right: 8px; border-radius: 7px; cursor: pointer; font-size: 12.5px; color: var(--text-2); }
.node:hover { background: var(--bg-hover); }
.node.active { background: var(--primary-soft); color: var(--primary-text); font-weight: 600; box-shadow: inset 2px 0 0 var(--primary); }
.node.hidden { opacity: .55; }
.chev { width: 12px; height: 12px; color: var(--text-4); transition: transform .15s; flex-shrink: 0; }
.chev.rot { transform: rotate(90deg); }
.chev-sp { width: 12px; flex-shrink: 0; }
.n-ico { width: 14px; height: 14px; color: var(--text-3); flex-shrink: 0; }
.n-ico.folder { color: #e0a43a; }
.idx { width: 6px; height: 6px; border-radius: 50%; margin-left: auto; flex-shrink: 0; }
.idx.ok { background: var(--green); }
.idx.bad { background: var(--red); }
.idx.pend { background: var(--primary); animation: pulse 1.2s infinite; }
.idx.skip { background: transparent; }
.tp-foot { display: flex; gap: 4px; padding: 8px; border-top: 1px solid var(--divider); }
.doc-pane { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.dp-head { display: flex; align-items: center; gap: 10px; padding: 10px 20px; border-bottom: 1px solid var(--border); background: var(--bg-elev); }
.dp-body { flex: 1; overflow: auto; padding: 24px 32px 40px; }
.editor { width: 100%; height: 100%; min-height: 60vh; border: 1px solid var(--border); border-radius: 10px; padding: 14px; font-family: var(--mono); font-size: 13px; line-height: 1.7; outline: none; resize: none; background: var(--bg-elev); color: var(--text); }
.drop { border: 1.5px dashed var(--border-strong); border-radius: 14px; padding: 36px 16px; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; text-align: center; }
.drop:hover, .drop.dragging { border-color: var(--primary); background: var(--primary-soft); }
.d-ico { width: 26px; height: 26px; color: var(--text-3); margin-bottom: 4px; }
</style>
