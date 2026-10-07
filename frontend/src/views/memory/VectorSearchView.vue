<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Database, Sparkles, Network, Loader2, FileText } from 'lucide-vue-next'
import { memoryApi } from '@/api/memory'
import { http } from '@/api/http'
import Select from '@/components/ui/Select.vue'
import Empty from '@/components/ui/Empty.vue'

const router = useRouter()
const q = ref('')
const scope = ref('')
const topK = ref(8)
const stores = ref<any[]>([])
const status = ref<any>(null)
const results = ref<any[] | null>(null)
const took = ref(0)
const loading = ref(false)
const error = ref('')

onMounted(async () => {
  const [s, st] = await Promise.all([http.get<any[]>('/memory-stores/options').catch(() => []), memoryApi.status().catch(() => null)])
  stores.value = s
  status.value = st
})
const scopeOptions = computed(() => [{ label: '全部记忆库', value: '' }, ...stores.value.map((s) => ({ label: s.name, value: s.id, sub: s.description || '' }))])

async function run() {
  if (!q.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    const r = await memoryApi.search(q.value.trim(), scope.value ? [scope.value] : [], topK.value)
    results.value = r.results
    took.value = r.tookMs
  } catch (e: any) {
    error.value = e.message
    results.value = []
  } finally {
    loading.value = false
  }
}

/** 关键词高亮（按空格拆词，转义 HTML） */
function highlight(text: string) {
  const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!))
  let html = esc(text)
  for (const t of q.value.trim().split(/\s+/).filter((x) => x.length > 0)) {
    const re = new RegExp(esc(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
    html = html.replace(re, (m) => `<mark>${m}</mark>`)
  }
  return html
}
const fileName = (p: string) => p.split('/').pop()
</script>

<template>
  <div class="page vs">
    <div class="page-head">
      <div>
        <div class="eyebrow">VECTOR RETRIEVAL</div>
        <h1 class="page-title" style="font-size: 26px">向量库查询</h1>
        <div class="page-sub">使用 BGE 语义向量检索当前工作空间的知识，并查看召回结果。</div>
      </div>
      <span class="pill" :class="{ bad: status && !status.ok }">
        <span class="status-dot" :style="{ background: status?.ok ? 'var(--green)' : 'var(--red)' }" />
        {{ status ? (status.ok ? `${status.model} · ${status.dim}d` : '执行器未连接') : '检测中…' }}
      </span>
    </div>

    <section class="card q-card">
      <div class="q-input">
        <Search class="q-ico" />
        <input v-model="q" placeholder="输入问题或关键词，例如：OCR 身份认证流程" @keydown.enter="run" />
      </div>
      <div class="q-row">
        <div class="field" style="margin: 0; width: 240px"><label>检索范围</label><Select v-model="scope" :options="scopeOptions" searchable /></div>
        <div class="field" style="margin: 0; width: 110px"><label>返回条数</label><Select v-model="topK" :options="[4, 8, 16, 32].map((n) => ({ label: `${n} 条`, value: n }))" /></div>
        <div class="spacer" />
        <button class="btn btn-primary btn-lg" :disabled="loading || !q.trim()" @click="run"><Loader2 v-if="loading" class="spin" /><Search v-else /> 开始查询</button>
      </div>
    </section>
    <div class="meta-row">
      <span><Database /> milvus-standalone · {{ status?.milvus || '127.0.0.1:19540' }}</span>
      <span><Sparkles /> BGE 语义模型 · {{ status?.dim || 512 }}d</span>
      <span><Network /> cosine · HNSW</span>
      <span>链路：记忆库 → embedding → Milvus</span>
    </div>

    <section class="card res">
      <div class="row" style="justify-content: space-between; margin-bottom: 14px">
        <div><div class="eyebrow">RESULTS</div><div class="section-title" style="font-size: 18px">召回段落</div></div>
        <span v-if="results" class="tag tag-round">{{ results.length }} 条 · {{ took }} ms</span>
      </div>
      <div v-if="error" class="notice notice-danger">{{ error }}</div>
      <div v-for="(r, i) in results || []" :key="i" class="hit" @click="router.push({ path: `/memory/${r.storeId}`, query: { path: r.path } })">
        <div class="hit-no">{{ String(i + 1).padStart(2, '0') }}</div>
        <div style="flex: 1; min-width: 0">
          <div class="row" style="justify-content: space-between">
            <span class="bold row" style="gap: 6px"><FileText style="width: 14px; color: var(--text-3)" />{{ fileName(r.path) }}</span>
            <span class="score">{{ Math.round(r.score * 100) }}% 相似度</span>
          </div>
          <div class="hit-text" v-html="highlight(r.text)" />
          <div class="row tiny muted" style="gap: 10px; margin-top: 6px">
            <span class="tag">{{ r.storeName }}</span><span class="mono">{{ r.path }}</span><span>#{{ r.chunkIndex }}</span>
          </div>
        </div>
      </div>
      <Empty v-if="results && !results.length && !error" title="没有召回结果" desc="换个问法，或确认文档已完成向量化（记忆库中文档旁的绿色圆点）" />
      <Empty v-if="!results" :icon="Search" title="输入问题开始检索" desc="语义检索不要求关键词完全匹配" />
    </section>
  </div>
</template>

<style scoped>
.vs { max-width: 1000px; margin: 0 auto; }
.eyebrow { font-size: 11px; letter-spacing: .12em; color: var(--text-3); font-weight: 600; margin-bottom: 4px; }
.pill { display: inline-flex; align-items: center; gap: 6px; padding: 5px 12px; border-radius: 999px; border: 1px solid var(--primary-soft-2); background: var(--primary-soft); color: var(--primary-text); font-size: 12px; font-family: var(--mono); }
.pill.bad { border-color: var(--red-soft); background: var(--red-soft); color: var(--red-text); }
.q-card { padding: 18px; }
.q-input { display: flex; align-items: center; gap: 10px; height: 48px; padding: 0 16px; border: 1px solid var(--border); border-radius: 12px; }
.q-input:focus-within { border-color: var(--primary); box-shadow: var(--ring); }
.q-ico { width: 18px; height: 18px; color: var(--primary); }
.q-input input { flex: 1; border: none; outline: none; background: transparent; font-size: 15px; }
.q-row { display: flex; align-items: flex-end; gap: 14px; margin-top: 14px; }
.meta-row { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12px; color: var(--text-3); margin: 14px 4px 18px; }
.meta-row span { display: inline-flex; align-items: center; gap: 5px; }
.meta-row svg { width: 13px; height: 13px; color: var(--primary); }
.res { padding: 18px 20px; }
.hit { display: flex; gap: 14px; padding: 14px; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 10px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.hit:hover { border-color: var(--primary-soft-2); box-shadow: var(--shadow); }
.hit-no { width: 40px; height: 40px; border-radius: 10px; background: var(--primary-soft); color: var(--primary-text); display: flex; align-items: center; justify-content: center; font-family: var(--mono); font-weight: 700; flex-shrink: 0; }
.score { font-size: 12px; font-weight: 700; color: var(--primary-text); font-family: var(--mono); }
.hit-text { font-size: 13px; color: var(--text-2); line-height: 1.7; margin-top: 6px; white-space: pre-wrap; display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
.hit-text :deep(mark) { background: #fff1b8; color: inherit; padding: 0 1px; border-radius: 2px; }
[data-theme="dark"] .hit-text :deep(mark) { background: #5c4a0b; }
</style>
