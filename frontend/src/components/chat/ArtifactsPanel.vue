<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FileText, FileImage, Eye, Code2, Share2, ExternalLink, Download, RefreshCw, Package } from 'lucide-vue-next'
import { http, authedUrl } from '@/api/http'
import { sessionApi } from '@/api/session'
import Select from '@/components/ui/Select.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import Empty from '@/components/ui/Empty.vue'
import { bytes, copyText } from '@/utils/format'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ sessionId: string; focusId?: string | null; refreshKey?: number }>()

const list = ref<any[]>([])
const selectedName = ref('')
const selectedId = ref('')
const view = ref<'preview' | 'source'>('preview')
const content = ref('')
const loading = ref(false)

/** 同名产物合并成一个条目，版本可切换 */
const groups = computed(() => {
  const m = new Map<string, any[]>()
  for (const f of list.value) {
    if (!m.has(f.fileName)) m.set(f.fileName, [])
    m.get(f.fileName)!.push(f)
  }
  return [...m.entries()].map(([name, vs]) => ({ name, versions: vs.sort((a, b) => b.version - a.version) }))
})
const current = computed(() => list.value.find((f) => f.id === selectedId.value))
const versionOptions = computed(() => (groups.value.find((g) => g.name === selectedName.value)?.versions || []).map((v) => ({ label: `v${v.version}`, value: v.id })))
const isImage = computed(() => (current.value?.mimeType || '').startsWith('image/'))
const isMd = computed(() => /markdown/.test(current.value?.mimeType || '') || /\.md$/i.test(current.value?.fileName || ''))

async function load() {
  list.value = await sessionApi.artifacts(props.sessionId)
  if (props.focusId && list.value.find((f) => f.id === props.focusId)) select(props.focusId)
  else if (!current.value && groups.value.length) select(groups.value[0].versions[0].id)
}
function select(id: string) {
  const f = list.value.find((x) => x.id === id)
  if (!f) return
  selectedId.value = id
  selectedName.value = f.fileName
}

watch(selectedId, async (id) => {
  content.value = ''
  if (!id || isImage.value) return
  loading.value = true
  try {
    const r = await http.get<{ content: string }>(`/files/${id}/text`, undefined, { silent: true })
    content.value = r.content ?? ''
  } catch (e: any) {
    content.value = `（无法预览：${e.message}）`
  } finally {
    loading.value = false
  }
})
watch(() => [props.sessionId, props.refreshKey], load, { immediate: true })
watch(() => props.focusId, (id) => id && select(id))

function shareLink() {
  if (!current.value) return
  copyText(`${location.origin}/sessions/${props.sessionId}`)
  toast.success('会话链接已复制（对方需为空间成员）')
}
</script>

<template>
  <div class="arts">
    <aside class="art-list">
      <button
        v-for="g in groups"
        :key="g.name"
        class="art-item"
        :class="{ active: g.name === selectedName }"
        @click="select(g.versions[0].id)"
      >
        <FileImage v-if="(g.versions[0].mimeType || '').startsWith('image/')" class="ai-ico img" />
        <FileText v-else class="ai-ico" />
        <div class="ai-main">
          <div class="ai-name ellipsis">{{ g.name }}</div>
          <div class="ai-meta">v{{ g.versions[0].version }} · {{ bytes(g.versions[0].size) }}</div>
          <div class="ai-mime ellipsis">{{ g.versions[0].mimeType }}</div>
        </div>
      </button>
      <Empty v-if="!groups.length" :icon="Package" title="暂无产物" desc="Agent 调用 artifact_save 保存的文件会出现在这里" compact />
    </aside>
    <section v-if="current" class="art-view">
      <div class="av-head">
        <span class="av-name ellipsis">{{ current.fileName }}</span>
        <Select v-model="selectedId" :options="versionOptions" size="sm" style="width: 84px" />
        <div class="spacer" />
        <div v-if="!isImage" class="segmented">
          <button :class="{ active: view === 'preview' }" @click="view = 'preview'"><Eye /> 预览</button>
          <button :class="{ active: view === 'source' }" @click="view = 'source'"><Code2 /> 源码</button>
        </div>
        <button class="btn btn-ghost btn-sm" @click="shareLink"><Share2 /> 分享</button>
        <a class="btn btn-ghost btn-sm" :href="authedUrl(`/files/${current.id}/download`, { inline: 1 })" target="_blank"><ExternalLink /> 分享页</a>
        <a class="btn btn-ghost btn-sm" :href="authedUrl(`/files/${current.id}/download`)"><Download /> 下载</a>
        <button class="icon-btn" @click="load"><RefreshCw /></button>
      </div>
      <div class="av-body">
        <img v-if="isImage" :src="authedUrl(`/files/${current.id}/download`, { inline: 1 })" class="av-img" />
        <div v-else-if="loading" class="skeleton" style="height: 18px; width: 40%" />
        <MarkdownView v-else-if="view === 'preview' && isMd" :source="content" class="av-md" />
        <pre v-else class="code-soft av-src">{{ content }}</pre>
      </div>
    </section>
  </div>
</template>

<style scoped>
.arts { display: flex; height: 100%; min-height: 0; }
.art-list { width: 200px; flex-shrink: 0; border-right: 1px solid var(--border); padding: 10px 8px; overflow-y: auto; }
.art-item { width: 100%; display: flex; gap: 8px; padding: 9px 10px; border: 1px solid transparent; border-radius: 10px; background: transparent; cursor: pointer; text-align: left; margin-bottom: 4px; }
.art-item:hover { background: var(--bg-hover); }
.art-item.active { background: var(--primary-soft); border-color: var(--primary-soft-2); }
.ai-ico { width: 15px; height: 15px; color: var(--green); flex-shrink: 0; margin-top: 2px; }
.ai-ico.img { color: var(--primary); }
.ai-main { min-width: 0; flex: 1; }
.ai-name { font-size: 12.5px; font-weight: 600; }
.ai-meta, .ai-mime { font-size: 11px; color: var(--text-3); }
.art-view { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.av-head { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
.av-name { font-weight: 600; max-width: 260px; }
.av-body { flex: 1; overflow: auto; padding: 22px 32px 40px; }
.av-md { max-width: 980px; }
.av-src { min-height: 200px; }
.av-img { max-width: 100%; border-radius: 10px; border: 1px solid var(--border); }
</style>
