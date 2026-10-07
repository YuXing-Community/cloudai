<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { FileText, FileSpreadsheet, FileImage, FileCode, Upload, RefreshCw, Eye, Download, Trash2, MessageSquare, Plus, Loader2 } from 'lucide-vue-next'
import { fileApi, SOURCE_TAG } from '@/api/file'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Modal from '@/components/ui/Modal.vue'
import Pagination from '@/components/ui/Pagination.vue'
import Empty from '@/components/ui/Empty.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import { confirm, toast } from '@/components/ui/toast'
import { bytes, debounce, fmtTime } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const category = ref<'artifact' | 'session'>('session')
const source = ref('all')
const q = ref('')
const page = ref(1)
const size = 20
const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await fileApi.list({ category: category.value, source: source.value, q: q.value, page: page.value, size })
    rows.value = r.records
    total.value = r.total
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(q, debounce(() => { page.value = 1; load() }, 300))
watch(category, () => { source.value = 'all'; page.value = 1; load() })
watch(source, () => { page.value = 1; load() })

const sources = computed(() => category.value === 'session'
  ? [{ k: 'all', l: '全部', i: null }, { k: 'manual', l: '手动上传', i: Upload }, { k: 'chat', l: '聊天附件', i: MessageSquare }]
  : [{ k: 'all', l: '全部', i: null }, { k: 'agent', l: 'Agent 产出', i: FileCode }])

function icon(f: any) {
  const n = (f.fileName || '').toLowerCase()
  if ((f.mimeType || '').startsWith('image/')) return { c: FileImage, t: 'img' }
  if (/\.(xlsx?|xlsm|csv)$/.test(n)) return { c: FileSpreadsheet, t: 'sheet' }
  if (/\.(json|py|js|ts|sql|ya?ml|sh)$/.test(n)) return { c: FileCode, t: 'code' }
  return { c: FileText, t: 'doc' }
}

// 预览
const pv = ref<any>(null)
const pvText = ref('')
const pvLoading = ref(false)
async function preview(f: any) {
  const isImg = (f.mimeType || '').startsWith('image/')
  const isText = /^text\/|json|yaml|javascript|x-python/.test(f.mimeType || '') || /\.(md|txt|csv|json|ya?ml|py|js|ts|sql|log)$/i.test(f.fileName)
  if (!isImg && !isText) return window.open(fileApi.url(f.id, true))
  pv.value = f
  pvText.value = ''
  if (isText) {
    pvLoading.value = true
    try { pvText.value = (await fileApi.text(f.id)).content } finally { pvLoading.value = false }
  }
}
async function remove(f: any) {
  if (!(await confirm({ title: `删除文件 ${f.name}？`, danger: true, okText: '删除' }))) return
  await fileApi.remove(f.id)
  toast.success('已删除')
  load()
}

// 上传
const upOpen = ref(false)
const upFile = ref<File | null>(null)
const upName = ref('')
const uploading = ref(false)
const dragging = ref(false)
const fileEl = ref<HTMLInputElement | null>(null)
function pick(f?: File | null) {
  if (!f) return
  if (f.size > 100 * 1024 * 1024) return toast.error('文件超过 100MB')
  upFile.value = f
}
async function doUpload() {
  if (!upFile.value) return
  uploading.value = true
  try {
    await fileApi.upload(upFile.value, upName.value)
    toast.success('上传成功')
    upOpen.value = false
    upFile.value = null
    upName.value = ''
    category.value = 'session'
    load()
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">文件中心</h1>
        <div class="page-sub">产物文件与会话文件统一管理</div>
      </div>
      <div class="segmented">
        <button :class="{ active: category === 'artifact' }" @click="category = 'artifact'">产物文件</button>
        <button :class="{ active: category === 'session' }" @click="category = 'session'">会话文件</button>
      </div>
    </div>
    <div class="toolbar" style="margin-bottom: 12px">
      <div class="segmented">
        <button v-for="s in sources" :key="s.k" :class="{ active: source === s.k }" @click="source = s.k"><component :is="s.i" v-if="s.i" /> {{ s.l }}</button>
      </div>
      <div class="spacer" />
      <SearchInput v-model="q" placeholder="按名称或文件名搜索" width="280px" />
      <button v-if="auth.can('file:edit')" class="btn btn-primary" @click="upOpen = true"><Plus /> 上传</button>
      <button class="icon-btn bordered" @click="load"><RefreshCw :class="{ spin: loading }" /></button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>名称</th><th>文件名</th><th>来源</th><th>大小</th><th>MIME 类型</th><th>{{ category === 'artifact' ? '产出时间' : '上传时间' }}</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="f in rows" :key="f.id">
            <td>
              <div class="row" style="gap: 8px">
                <component :is="icon(f).c" class="f-ico" :class="icon(f).t" />
                <div style="min-width: 0">
                  <div class="cell-main ellipsis" style="max-width: 260px">{{ f.name }}</div>
                  <button v-if="f.sessionId && category === 'artifact'" class="link-btn tiny" @click="router.push(`/sessions/${f.sessionId}`)">{{ f.sessionTitle || '查看会话' }} · v{{ f.version }}</button>
                </div>
              </div>
            </td>
            <td class="mono small ellipsis" style="max-width: 220px">{{ f.fileName }}</td>
            <td><span class="tag tag-round"><MessageSquare v-if="f.source === 'chat'" />{{ SOURCE_TAG[f.source] || f.source }}</span></td>
            <td class="small">{{ bytes(f.size) }}</td>
            <td class="small muted ellipsis" style="max-width: 200px" :title="f.mimeType">{{ f.mimeType }}</td>
            <td class="small">{{ fmtTime(f.createdAt) }}</td>
            <td class="actions">
              <button class="icon-btn" title="预览" @click="preview(f)"><Eye /></button>
              <a class="icon-btn" title="下载" :href="fileApi.url(f.id)"><Download /></a>
              <button v-if="auth.can('file:edit')" class="icon-btn danger" title="删除" @click="remove(f)"><Trash2 /></button>
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="FileText" :title="category === 'artifact' ? '暂无产物文件' : '暂无会话文件'" :desc="category === 'artifact' ? 'Agent 在会话中调用 artifact_save 保存的文件会出现在这里' : '上传文件或在会话中发送附件'" />
    </div>
    <div class="table-foot"><span>共 {{ total }} 个文件</span><Pagination v-model:page="page" :size="size" :total="total" @change="load" /></div>

    <Modal :open="upOpen" title="上传文件" width="440px" @close="upOpen = false">
      <div class="label" style="margin-bottom: 6px">文件</div>
      <div
        class="drop" :class="{ dragging, has: upFile }"
        @click="fileEl?.click()" @dragover.prevent="dragging = true" @dragleave="dragging = false"
        @drop.prevent="dragging = false; pick($event.dataTransfer?.files?.[0])"
      >
        <template v-if="upFile"><FileText class="d-ico" /><div class="bold">{{ upFile.name }}</div><div class="small muted">{{ bytes(upFile.size) }} · 点击更换</div></template>
        <template v-else><Plus class="d-ico" /><div class="muted">点击选择文件，最大 100MB</div></template>
      </div>
      <input ref="fileEl" type="file" hidden @change="pick(($event.target as HTMLInputElement).files?.[0])" />
      <div class="field" style="margin-top: 14px"><label>显示名称</label><input v-model="upName" class="input" placeholder="可选，留空则使用文件名" /></div>
      <template #footer>
        <button class="btn" @click="upOpen = false">取消</button>
        <button class="btn btn-primary" :disabled="!upFile || uploading" @click="doUpload"><Loader2 v-if="uploading" class="spin" /> 上传</button>
      </template>
    </Modal>

    <Modal :open="!!pv" :title="pv?.name" :subtitle="`${pv?.fileName} · ${bytes(pv?.size)}`" width="860px" @close="pv = null">
      <img v-if="(pv?.mimeType || '').startsWith('image/')" :src="fileApi.url(pv.id, true)" class="pv-img" />
      <div v-else-if="pvLoading" class="skeleton" style="height: 16px; width: 50%" />
      <MarkdownView v-else-if="/\.md$/i.test(pv?.fileName || '')" :source="pvText" />
      <pre v-else class="code-soft" style="max-height: 60vh">{{ pvText }}</pre>
      <template #footer><a class="btn" :href="pv ? fileApi.url(pv.id) : '#'"><Download /> 下载</a></template>
    </Modal>
  </div>
</template>

<style scoped>
.f-ico { width: 16px; height: 16px; color: var(--text-3); flex-shrink: 0; }
.f-ico.sheet { color: var(--green); }
.f-ico.img { color: var(--primary); }
.f-ico.code { color: var(--purple); }
.tag svg { width: 11px; height: 11px; }
.drop { border: 1.5px dashed var(--border-strong); border-radius: 12px; padding: 30px 16px; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; text-align: center; transition: border-color .15s, background .15s; }
.drop:hover, .drop.dragging { border-color: var(--primary); background: var(--primary-soft); }
.d-ico { width: 26px; height: 26px; color: var(--text-3); }
.pv-img { max-width: 100%; border-radius: 10px; }
</style>
