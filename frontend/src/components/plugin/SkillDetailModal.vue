<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Folder, FolderOpen, FileText, ChevronRight, Code2, Eye, Download } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { skillApi } from '@/api/skill'
import { bytes, fmtTime } from '@/utils/format'

const props = defineProps<{ skillId: string | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const skill = ref<any>(null)
const version = ref('')
const tree = ref<any[]>([])
const collapsed = ref<Set<string>>(new Set())
const path = ref('SKILL.md')
const file = ref<any>(null)
const view = ref<'preview' | 'source'>('preview')

watch(() => props.skillId, async (id) => {
  skill.value = null
  tree.value = []
  file.value = null
  collapsed.value = new Set()
  path.value = 'SKILL.md'
  version.value = ''
  if (!id) return
  skill.value = await skillApi.get(id)
  version.value = skill.value.currentVersion
})
watch(version, async (v) => {
  if (!v || !props.skillId) return
  tree.value = await skillApi.tree(props.skillId, v)
  if (!tree.value.find((n) => n.path === path.value)) path.value = tree.value.find((n) => n.path === 'SKILL.md') ? 'SKILL.md' : tree.value.find((n) => !n.dir)?.path || ''
  await loadFile()
})
watch(path, () => loadFile())

async function loadFile() {
  if (!path.value || !props.skillId) return (file.value = null)
  file.value = await skillApi.file(props.skillId, path.value, version.value)
}

const visible = computed(() => tree.value.filter((n) => {
  const parts = n.path.split('/')
  for (let i = 1; i < parts.length; i++) if (collapsed.value.has(parts.slice(0, i).join('/'))) return false
  return true
}))
function click(n: any) {
  if (n.dir) {
    const s = new Set(collapsed.value)
    s.has(n.path) ? s.delete(n.path) : s.add(n.path)
    collapsed.value = s
  } else path.value = n.path
}

/** SKILL.md：front matter 作为元数据卡片，正文渲染 Markdown */
const fm = computed(() => {
  const c: string = file.value?.content || ''
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(c)
  if (!m) return { meta: null as any, body: c }
  const meta: Record<string, string> = {}
  let key = ''
  for (const line of m[1].split(/\r?\n/)) {
    if (/^\S[^:]*:/.test(line)) {
      key = line.slice(0, line.indexOf(':')).trim()
      meta[key] = line.slice(line.indexOf(':') + 1).trim()
    } else if (key) meta[key] += (meta[key] ? (key === 'metadata' ? '\n' : ' ') : '') + line.trim()
  }
  if (meta.metadata) {
    const obj: Record<string, string> = {}
    meta.metadata.split('\n').forEach((l) => { const i = l.indexOf(':'); if (i > 0) obj[l.slice(0, i).trim()] = l.slice(i + 1).trim() })
    if (Object.keys(obj).length) meta.metadata = JSON.stringify(obj)
  }
  return { meta, body: c.slice(m[0].length) }
})
const isMd = computed(() => path.value.toLowerCase().endsWith('.md'))
</script>

<template>
  <Modal :open="!!skillId" width="1180px" flush @close="emit('close')">
    <div v-if="skill" class="sd">
      <aside class="sd-left">
        <div class="card info">
          <h3 class="sd-name">{{ skill.name }}</h3>
          <p class="sd-desc">{{ skill.description }}</p>
          <div class="kv"><span>当前版本</span><span class="mono">v{{ skill.currentVersion }}</span></div>
          <div class="kv"><span>创建人</span><UserAvatar :name="skill.author?.displayName" :avatar="skill.author?.avatar" :size="20" show-name /></div>
          <div class="kv"><span>更新时间</span><span class="mono">{{ fmtTime(skill.updatedAt) }}</span></div>
          <div v-if="skill.tags?.length" class="tags"><span v-for="t in skill.tags" :key="t" class="tag tag-blue">{{ t }}</span></div>
        </div>
        <div class="card files">
          <div class="row" style="justify-content: space-between; margin-bottom: 8px">
            <span class="row bold" style="gap: 6px"><Folder class="fi blue" /> 文件目录</span>
            <Select v-model="version" :options="(skill.versions || []).map((v: string) => ({ label: `v${v}`, value: v }))" size="sm" style="width: 110px" />
          </div>
          <div v-for="n in visible" :key="n.path" class="node" :class="{ active: n.path === path }" :style="{ paddingLeft: 6 + (n.path.split('/').length - 1) * 16 + 'px' }" @click="click(n)">
            <ChevronRight v-if="n.dir" class="chev" :class="{ rot: !collapsed.has(n.path) }" />
            <FolderOpen v-if="n.dir" class="fi blue" /><FileText v-else class="fi" />
            <span class="ellipsis">{{ n.path.split('/').pop() }}</span>
            <span v-if="!n.dir" class="nsize">{{ bytes(n.size) }}</span>
          </div>
        </div>
      </aside>
      <section class="card sd-right">
        <div class="sr-head">
          <Code2 class="fi blue" /><span class="mono bold">{{ path }}</span>
          <div class="spacer" />
          <div v-if="isMd" class="segmented">
            <button :class="{ active: view === 'preview' }" @click="view = 'preview'"><Eye /> 预览</button>
            <button :class="{ active: view === 'source' }" @click="view = 'source'"><Code2 /> 源码</button>
          </div>
          <a class="icon-btn" :href="skillApi.downloadUrl(skill.id, version)" title="下载 Zip"><Download /></a>
        </div>
        <div class="sr-body">
          <div v-if="!file" class="empty small">选择文件查看</div>
          <div v-else-if="file.binary || file.tooLarge" class="empty-box">{{ file.binary ? '二进制文件' : '文件过大' }}，不支持预览（{{ bytes(file.size) }}）</div>
          <template v-else-if="isMd && view === 'preview'">
            <div v-if="fm.meta" class="meta">
              <span class="meta-tag">SKILL METADATA</span>
              <template v-for="(v, k) in fm.meta" :key="k">
                <div class="meta-k">{{ k }}</div>
                <div class="meta-v">{{ v }}</div>
              </template>
            </div>
            <MarkdownView :source="fm.body" />
          </template>
          <pre v-else class="src">{{ file.content }}</pre>
        </div>
      </section>
    </div>
  </Modal>
</template>

<style scoped>
.sd { display: flex; gap: 16px; padding: 16px; height: 82vh; background: var(--bg); border-radius: 16px; }
.sd-left { width: 320px; flex-shrink: 0; display: flex; flex-direction: column; gap: 16px; overflow: auto; }
.info { padding: 18px 20px; }
.sd-name { font-size: 18px; font-weight: 700; padding-bottom: 12px; border-bottom: 1px solid var(--divider); }
.sd-desc { color: var(--text-2); font-size: 13px; line-height: 1.7; padding: 12px 0; border-bottom: 1px solid var(--divider); margin-bottom: 8px; }
.kv { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; font-size: 12.5px; color: var(--text-3); }
.tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.files { padding: 14px 16px; }
.node { display: flex; align-items: center; gap: 6px; height: 34px; padding-right: 8px; border-radius: 9px; cursor: pointer; font-size: 13px; }
.node:hover { background: var(--bg-hover); }
.node.active { background: var(--primary-soft); color: var(--primary-text); font-weight: 600; border: 1px solid var(--primary-soft-2); }
.chev { width: 13px; height: 13px; color: var(--text-4); transition: transform .15s; }
.chev.rot { transform: rotate(90deg); }
.fi { width: 15px; height: 15px; color: var(--text-3); flex-shrink: 0; }
.fi.blue { color: var(--primary); }
.nsize { margin-left: auto; font-size: 11px; color: var(--text-4); font-family: var(--mono); }
.sd-right { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow: hidden; }
.sr-head { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--divider); }
.sr-body { flex: 1; overflow: auto; padding: 20px 24px; }
.meta { background: var(--primary-soft); border: 1px solid var(--primary-soft-2); border-radius: 14px; padding: 16px 18px; margin-bottom: 20px; }
.meta-tag { display: inline-block; font-size: 11px; font-weight: 700; color: var(--primary-text); background: color-mix(in srgb, var(--primary) 14%, transparent); padding: 3px 8px; border-radius: 6px; margin-bottom: 8px; }
.meta-k { font-size: 12.5px; color: var(--text-3); margin-top: 10px; }
.meta-v { font-size: 14px; color: var(--text); line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.src { margin: 0; font-family: var(--mono); font-size: 12.5px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; color: var(--text); }
</style>
