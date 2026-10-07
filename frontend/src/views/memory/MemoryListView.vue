<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Database, Plus, Pencil, Archive, ArchiveRestore, Trash2, ArrowRight } from 'lucide-vue-next'
import { memoryApi } from '@/api/memory'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import Modal from '@/components/ui/Modal.vue'
import Empty from '@/components/ui/Empty.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce, fmtDate } from '@/utils/format'

const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('memory:edit'))
const mode = ref<'compat' | 'native'>('compat')
const namespace = ref('default')
const namespaces = ref<string[]>(['default'])
const q = ref('')
const archived = ref(false)
const rows = ref<any[]>([])
const active = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await memoryApi.list({ mode: mode.value, namespace: namespace.value, q: q.value, archived: archived.value })
    rows.value = r.records
    active.value = r.active
    namespaces.value = r.namespaces
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(q, debounce(load, 300))
watch([mode, namespace, archived], load)

const open = ref(false)
const f = ref<any>({})
function edit(s?: any) {
  f.value = s ? { id: s.id, name: s.name, description: s.description || '', isolation: s.isolation, namespace: s.namespace, mode: s.mode }
    : { id: null, name: '', description: '', isolation: 'shared', namespace: namespace.value, mode: mode.value }
  open.value = true
}
async function save() {
  if (!f.value.name.trim()) return toast.warning('请填写名称')
  if (f.value.id) await memoryApi.update(f.value.id, f.value)
  else await memoryApi.create(f.value)
  toast.success('已保存')
  open.value = false
  load()
}
async function archive(s: any) {
  await memoryApi.archive(s.id, s.status !== 'archived')
  toast.success(s.status === 'archived' ? '已恢复' : '已归档')
  load()
}
async function remove(s: any) {
  if (!(await confirm({ title: `删除记忆库「${s.name}」？`, message: '会删除全部文档及向量索引，已关联的 Agent 将无法检索。', danger: true, okText: '删除', requireText: s.name }))) return
  await memoryApi.remove(s.id)
  load()
}
</script>

<template>
  <div class="page mem">
    <div class="page-head">
      <div class="row" style="gap: 12px; align-items: flex-start">
        <div class="h-ico"><Database /></div>
        <div>
          <h1 class="page-title">记忆库</h1>
          <div class="page-sub">持久化记忆文档仓库，Agent 在运行时可进行上下文检索与写入 · <b style="color: var(--text)">{{ active }}</b> 活跃</div>
        </div>
      </div>
      <button v-if="canEdit" class="btn btn-primary" @click="edit()"><Plus /> 新建记忆库</button>
    </div>

    <div class="filter card">
      <div class="segmented">
        <button :class="{ active: mode === 'compat' }" style="min-width: 110px; justify-content: center" @click="mode = 'compat'">兼容模式</button>
        <button :class="{ active: mode === 'native' }" style="min-width: 110px; justify-content: center" @click="mode = 'native'">OpenViking原生认证体系</button>
      </div>
      <div class="spacer" />
      <Select v-model="namespace" :options="namespaces.map((n) => ({ label: n, value: n }))" style="width: 200px" />
    </div>
    <div class="row" style="justify-content: space-between; margin: 14px 0">
      <SearchInput v-model="q" placeholder="搜索名称或描述..." width="300px" />
      <Switch v-model="archived" size="sm" label="显示已归档" />
    </div>

    <div class="grid">
      <div v-for="s in rows" :key="s.id" class="ms card" :class="{ archived: s.status === 'archived' }">
        <div class="bar" />
        <div class="ms-body">
          <div class="row" style="gap: 10px; align-items: flex-start">
            <div class="ms-ico"><Database /></div>
            <div style="flex: 1; min-width: 0">
              <div class="bold ellipsis">{{ s.name }}</div>
              <div class="small ms-desc" :class="{ none: !s.description }">{{ s.description || '暂无描述' }}</div>
            </div>
            <span class="tag" :class="s.status === 'active' ? 'tag-green' : ''">{{ s.status === 'active' ? '活跃' : '已归档' }}</span>
          </div>
          <div class="kvs">
            <div class="kv"><span>Provider</span><span class="mono">{{ s.provider }} / {{ s.namespace }}</span></div>
            <div class="kv"><span>隔离级别</span><span class="tag" :class="s.isolation === 'user' ? 'tag-blue' : ''">{{ s.isolation === 'user' ? '用户级' : '共享' }}</span></div>
            <div class="kv"><span>文档 / 向量块</span><span class="mono">{{ s.docCount }} / {{ s.chunkCount }}<span v-if="s.failedCount" class="danger-text"> · {{ s.failedCount }} 失败</span></span></div>
            <div class="kv"><span>创建于</span><span class="mono">{{ fmtDate(s.createdAt) }}</span></div>
          </div>
        </div>
        <div class="ms-foot">
          <template v-if="canEdit">
            <button class="icon-btn sm" title="编辑" @click="edit(s)"><Pencil /></button>
            <button class="icon-btn sm" :title="s.status === 'archived' ? '恢复' : '归档'" @click="archive(s)"><ArchiveRestore v-if="s.status === 'archived'" /><Archive v-else /></button>
            <button class="icon-btn sm danger" title="删除" @click="remove(s)"><Trash2 /></button>
          </template>
          <div class="spacer" />
          <button class="link-btn small" @click="router.push(`/memory/${s.id}`)">进入 <ArrowRight style="width: 13px" /></button>
        </div>
      </div>
    </div>
    <Empty v-if="!rows.length && !loading" :icon="Database" title="还没有记忆库" desc="创建记忆库并上传知识文档，上传后自动向量化写入 Milvus，Agent 关联后即可检索" />

    <Modal :open="open" :title="f.id ? '编辑记忆库' : '新建记忆库'" width="500px" @close="open = false">
      <div class="field"><label>名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="例如：风控应急止血知识库" /></div>
      <div class="field"><label>描述</label><textarea v-model="f.description" class="textarea" placeholder="说明知识库的内容范围，Agent 会据此判断何时检索" /></div>
      <div class="form-row">
        <div class="field"><label>隔离级别</label><Select v-model="f.isolation" :options="[{ label: '共享（空间内共享）', value: 'shared' }, { label: '用户级', value: 'user' }]" /></div>
        <div class="field"><label>Namespace</label><input v-model="f.namespace" class="input mono" /></div>
      </div>
      <template #footer><button class="btn" @click="open = false">取消</button><button class="btn btn-primary" @click="save">保存</button></template>
    </Modal>
  </div>
</template>

<style scoped>
.mem { max-width: 1400px; margin: 0 auto; }
.h-ico { width: 38px; height: 38px; border-radius: 10px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.h-ico svg { width: 19px; height: 19px; }
.filter { display: flex; align-items: center; padding: 10px 12px; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.ms { overflow: hidden; display: flex; flex-direction: column; transition: box-shadow .15s; }
.ms:hover { box-shadow: var(--shadow-md); }
.ms.archived { opacity: .65; }
.bar { height: 4px; background: linear-gradient(90deg, #3b82f6, #8b5cf6, #06b6d4); }
.ms-body { padding: 14px 16px; flex: 1; }
.ms-ico { width: 30px; height: 30px; border-radius: 8px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ms-ico svg { width: 15px; height: 15px; }
.ms-desc { color: var(--text-2); margin-top: 3px; }
.ms-desc.none { color: var(--text-4); font-style: italic; }
.kvs { margin-top: 14px; padding-top: 10px; border-top: 1px solid var(--divider); }
.kv { display: flex; justify-content: space-between; align-items: center; font-size: 11.5px; color: var(--text-3); padding: 3px 0; }
.kv .mono { color: var(--text-2); }
.ms-foot { display: flex; align-items: center; gap: 4px; padding: 8px 12px; border-top: 1px solid var(--divider); }
@media (max-width: 1100px) { .grid { grid-template-columns: repeat(2, 1fr); } }
</style>
