<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FolderOpen, Folder, FileText, RefreshCw, Trash2, MessageSquare, ChevronRight } from 'lucide-vue-next'
import { sandboxApi } from '@/api/sandbox'
import { useAuth } from '@/stores/auth'
import Empty from '@/components/ui/Empty.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import { confirm, toast } from '@/components/ui/toast'
import { ago, bytes } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const list = ref<any[]>([])
const cur = ref<any>(null)
const tree = ref<any[]>([])
const collapsed = ref<Set<string>>(new Set())
const file = ref<{ path: string; content?: string; binary?: boolean; size: number } | null>(null)

async function load() {
  list.value = await sandboxApi.workspaces()
  if (!cur.value && list.value.length) pick(list.value[0])
}
async function pick(w: any) {
  cur.value = w
  file.value = null
  tree.value = await sandboxApi.tree(w.dir)
}
async function open(n: any) {
  if (n.dir) {
    const s = new Set(collapsed.value)
    s.has(n.path) ? s.delete(n.path) : s.add(n.path)
    collapsed.value = s
    return
  }
  const r = await sandboxApi.file(cur.value.dir, n.path)
  file.value = { path: n.path, ...r }
}
const visible = computed(() => tree.value.filter((n) => {
  const parts = n.path.split('/')
  for (let i = 1; i < parts.length; i++) if (collapsed.value.has(parts.slice(0, i).join('/'))) return false
  return true
}))
async function clean() {
  if (!(await confirm({ title: '清空工作区？', message: '会删除该工作区内的全部文件（会话记录保留）。', danger: true, okText: '清空' }))) return
  await sandboxApi.cleanWorkspace(cur.value.dir)
  toast.success('已清空')
  cur.value = null
  load()
}
onMounted(load)
</script>

<template>
  <div class="ws">
    <aside class="ws-list">
      <div class="row" style="justify-content: space-between; padding: 16px 14px 10px">
        <div>
          <div class="page-title" style="font-size: 17px">工作区</div>
          <div class="tiny muted">会话沙箱的工作目录与文件</div>
        </div>
        <button class="icon-btn" @click="load"><RefreshCw /></button>
      </div>
      <button v-for="w in list" :key="w.dir" class="ws-item" :class="{ active: cur?.dir === w.dir }" @click="pick(w)">
        <FolderOpen class="wi-ico" />
        <div style="min-width: 0; flex: 1">
          <div class="bold ellipsis">{{ w.title }}</div>
          <div class="tiny muted ellipsis">{{ w.agent_name }} · {{ w.files }} 个文件 · {{ bytes(w.size) }}</div>
        </div>
        <span class="tiny faint">{{ ago(w.updated_at) }}</span>
      </button>
      <Empty v-if="!list.length" :icon="FolderOpen" title="暂无工作区" desc="Agent 执行会话时会在沙箱中创建工作目录" compact />
    </aside>
    <section v-if="cur" class="ws-main">
      <div class="ws-head">
        <span class="mono small ellipsis">{{ cur.dir }}</span>
        <div class="spacer" />
        <button class="btn btn-sm" @click="router.push(`/sessions/${cur.session_id}`)"><MessageSquare /> 打开会话</button>
        <button v-if="auth.can('sandbox:edit')" class="btn btn-sm btn-danger-ghost" @click="clean"><Trash2 /> 清空</button>
      </div>
      <div class="ws-body">
        <div class="tree">
          <div v-for="n in visible" :key="n.path" class="node" :class="{ active: file?.path === n.path }" :style="{ paddingLeft: 10 + (n.path.split('/').length - 1) * 14 + 'px' }" @click="open(n)">
            <ChevronRight v-if="n.dir" class="chev" :class="{ rot: !collapsed.has(n.path) }" />
            <Folder v-if="n.dir" class="n-ico folder" /><FileText v-else class="n-ico" />
            <span class="ellipsis">{{ n.path.split('/').pop() }}</span>
            <span v-if="!n.dir" class="tiny faint" style="margin-left: auto">{{ bytes(n.size) }}</span>
          </div>
          <div v-if="!tree.length" class="empty small">空目录</div>
        </div>
        <div class="preview">
          <template v-if="file">
            <div class="mono small muted" style="margin-bottom: 10px">{{ file.path }} · {{ bytes(file.size) }}</div>
            <div v-if="file.binary" class="empty-box">二进制文件，不支持预览</div>
            <MarkdownView v-else-if="file.path.endsWith('.md')" :source="file.content" />
            <pre v-else class="code-soft">{{ file.content }}</pre>
          </template>
          <Empty v-else title="选择文件查看内容" compact />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ws { display: flex; height: 100%; min-height: 0; }
.ws-list { width: 280px; flex-shrink: 0; border-right: 1px solid var(--border); overflow-y: auto; background: var(--bg-elev); }
.ws-item { width: calc(100% - 16px); margin: 0 8px 4px; display: flex; align-items: center; gap: 10px; padding: 9px 10px; border: none; border-radius: 10px; background: transparent; cursor: pointer; text-align: left; }
.ws-item:hover { background: var(--bg-hover); }
.ws-item.active { background: var(--bg-active); }
.wi-ico { width: 17px; height: 17px; color: var(--orange); flex-shrink: 0; }
.ws-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.ws-head { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid var(--border); }
.ws-body { flex: 1; min-height: 0; display: flex; }
.tree { width: 300px; flex-shrink: 0; border-right: 1px solid var(--border); overflow: auto; padding: 8px 4px; }
.node { display: flex; align-items: center; gap: 6px; height: 28px; padding-right: 10px; border-radius: 7px; cursor: pointer; font-size: 12.5px; }
.node:hover { background: var(--bg-hover); }
.node.active { background: var(--primary-soft); color: var(--primary-text); }
.chev { width: 12px; height: 12px; color: var(--text-4); transition: transform .15s; flex-shrink: 0; }
.chev.rot { transform: rotate(90deg); }
.n-ico { width: 14px; height: 14px; color: var(--text-3); flex-shrink: 0; }
.n-ico.folder { color: var(--orange); }
.preview { flex: 1; min-width: 0; overflow: auto; padding: 18px 24px; }
</style>
