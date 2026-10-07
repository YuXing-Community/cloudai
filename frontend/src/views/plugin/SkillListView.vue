<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Upload, Terminal, Activity, Pencil, Share2, Download, Trash2, Store, Tag, Plus, X } from 'lucide-vue-next'
import { skillApi, SKILL_CATEGORIES, SKILL_MARKET_URL } from '@/api/skill'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Switch from '@/components/ui/Switch.vue'
import Select from '@/components/ui/Select.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Pagination from '@/components/ui/Pagination.vue'
import Modal from '@/components/ui/Modal.vue'
import Drawer from '@/components/ui/Drawer.vue'
import Empty from '@/components/ui/Empty.vue'
import SkillDetailModal from '@/components/plugin/SkillDetailModal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce } from '@/utils/format'

const router = useRouter()
const auth = useAuth()
const canEdit = computed(() => auth.can('plugin:edit'))

const q = ref('')
const tag = ref('')
const tags = ref<string[]>([])
const showInvalid = ref(false)
const page = ref(1)
const size = 12
const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const detailId = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    const r = await skillApi.list({ q: q.value, tag: tag.value, showInvalid: showInvalid.value, page: page.value, size })
    rows.value = r.records
    total.value = r.total
  } finally {
    loading.value = false
  }
}
onMounted(async () => { load(); tags.value = await skillApi.tags() })
watch(q, debounce(() => { page.value = 1; load() }, 300))
watch([tag, showInvalid], () => { page.value = 1; load() })

function menu(s: any) {
  const own = !s.shared && canEdit.value
  return [
    { label: '编辑', icon: Pencil, disabled: !own, onClick: () => openEdit(s) },
    { label: '共享设置', icon: Share2, disabled: !own, onClick: () => openShare(s) },
    { label: '下载 Zip 包', icon: Download, onClick: () => window.open(skillApi.downloadUrl(s.id)) },
    { divider: true, label: '' },
    { label: '删除', icon: Trash2, danger: true, disabled: !own, onClick: () => remove(s) },
  ]
}
async function remove(s: any) {
  if (!(await confirm({ title: `删除技能 ${s.name}？`, message: '会删除全部版本文件，引用该技能的 Agent 将无法加载。', danger: true, okText: '删除', requireText: s.name }))) return
  await skillApi.remove(s.id)
  toast.success('已删除')
  load()
}

// 编辑元数据
const editing = ref<any>(null)
const ef = ref<any>({})
function openEdit(s: any) {
  editing.value = s
  ef.value = { description: s.description || '', category: s.category || '', tags: (s.tags || []).join(', '), status: s.status }
}
async function saveEdit() {
  await skillApi.update(editing.value.id, { ...ef.value, tags: ef.value.tags.split(/[,，]/).map((x: string) => x.trim()).filter(Boolean) })
  toast.success('已保存')
  editing.value = null
  load()
  tags.value = await skillApi.tags()
}

// 共享
const sharing = ref<any>(null)
const shares = ref<any[]>([])
const targets = ref<any[]>([])
const shareTarget = ref('')
const addingShare = ref(false)
async function openShare(s: any) {
  sharing.value = s
  addingShare.value = false
  shareTarget.value = ''
  shares.value = await skillApi.shares(s.id)
  targets.value = await skillApi.shareTargets()
}
const targetOptions = computed(() => targets.value.filter((t) => !shares.value.some((x) => x.id === t.id)).map((t) => ({ label: t.name, value: t.id })))
async function addShare() {
  if (!shareTarget.value) return
  await skillApi.share(sharing.value.id, shareTarget.value)
  shares.value = await skillApi.shares(sharing.value.id)
  shareTarget.value = ''
  addingShare.value = false
  toast.success('已授权')
}
async function removeShare(sp: any) {
  await skillApi.unshare(sharing.value.id, sp.id)
  shares.value = await skillApi.shares(sharing.value.id)
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div class="row" style="gap: 12px">
        <h1 class="page-title" style="font-size: 16px"><span class="muted" style="font-weight: 500">{{ auth.space?.name }} /</span> 技能</h1>
        <div class="segmented"><button class="active">空间技能</button></div>
      </div>
      <a class="btn btn-soft" :href="SKILL_MARKET_URL" target="_blank"><Store /> Skill 市场</a>
    </div>
    <div class="toolbar" style="margin-bottom: 16px">
      <SearchInput v-model="q" placeholder="搜索技能名称或描述" width="280px" />
      <div class="tag-filter">
        <Tag class="tf-ico" />
        <button class="tf" :class="{ on: !tag }" @click="tag = ''">全部</button>
        <button v-for="t in tags" :key="t" class="tf" :class="{ on: tag === t }" @click="tag = tag === t ? '' : t">{{ t }}</button>
      </div>
      <div class="spacer" />
      <button class="icon-btn bordered" title="刷新" @click="load"><RefreshCw :class="{ spin: loading }" /></button>
      <Switch v-model="showInvalid" size="sm" label="显示已失效" />
      <button v-if="canEdit" class="btn btn-primary" @click="router.push('/plugins/skills/upload')"><Upload /> 上传 Skill</button>
    </div>

    <div class="grid">
      <div v-for="s in rows" :key="s.id" class="sk card" :class="{ invalid: s.status === 'invalid' }" @click="detailId = s.id">
        <div class="row" style="gap: 12px; align-items: flex-start">
          <div class="sk-ico"><Terminal /></div>
          <div style="flex: 1; min-width: 0">
            <div class="sk-name ellipsis">{{ s.name }}</div>
            <div class="tiny muted ellipsis">{{ s.author?.username || s.author?.displayName }}</div>
          </div>
          <span @click.stop><DropdownMenu :items="menu(s)" /></span>
        </div>
        <p class="sk-desc clamp-3">{{ s.description || '暂无描述' }}</p>
        <div class="sk-foot">
          <span class="tag tag-blue tag-mono">v{{ s.currentVersion }}</span>
          <span v-if="s.callCount" class="tag"><Activity /> 调用 {{ s.callCount }} 次</span>
          <span class="tag">{{ s.fileCount }} 个文件</span>
          <span v-if="s.shared" class="tag tag-purple">共享</span>
          <span v-if="s.status === 'invalid'" class="tag tag-red">已失效</span>
          <span v-for="t in (s.tags || []).slice(0, 2)" :key="t" class="tag tag-outline">{{ t }}</span>
        </div>
      </div>
    </div>
    <Empty v-if="!rows.length && !loading" :icon="Terminal" title="还没有技能" desc="上传包含 SKILL.md 的技能文件夹或 zip 包，Agent 即可按需加载">
      <button v-if="canEdit" class="btn btn-primary" style="margin-top: 8px" @click="router.push('/plugins/skills/upload')"><Upload /> 上传 Skill</button>
    </Empty>
    <div class="table-foot">
      <span>共 {{ total }} 条</span>
      <Pagination v-model:page="page" :size="size" :total="total" @change="load" />
    </div>

    <SkillDetailModal :skill-id="detailId" @close="detailId = null" />

    <Modal :open="!!editing" :title="`编辑 ${editing?.name || ''}`" width="520px" @close="editing = null">
      <div class="field"><label>描述</label><textarea v-model="ef.description" class="textarea" rows="4" /></div>
      <div class="form-row">
        <div class="field"><label>类别</label><Select v-model="ef.category" :options="SKILL_CATEGORIES.map((c) => ({ label: c, value: c }))" placeholder="请选择类别" /></div>
        <div class="field"><label>状态</label><Select v-model="ef.status" :options="[{ label: '有效', value: 'active' }, { label: '已失效', value: 'invalid' }]" /></div>
      </div>
      <div class="field"><label>标签</label><input v-model="ef.tags" class="input" placeholder="例如：分析, SQL, 自动化" /></div>
      <template #footer><button class="btn" @click="editing = null">取消</button><button class="btn btn-primary" @click="saveEdit">保存</button></template>
    </Modal>

    <Drawer :open="!!sharing" width="440px" @close="sharing = null">
      <template #header>
        <div style="flex: 1">
          <h3 class="row" style="gap: 8px; font-size: 16px"><Share2 style="width: 16px" /> 共享技能</h3>
          <p class="small muted" style="margin-top: 4px">向其他工作空间授权使用 <b>{{ sharing?.name }}</b> 后，该空间的 Agent 可直接引用此技能</p>
        </div>
      </template>
      <div class="row" style="justify-content: space-between; margin-bottom: 14px">
        <span class="bold">已授权工作空间 <span class="tag">{{ shares.length }}</span></span>
        <button class="btn btn-primary" @click="addingShare = true"><Plus /> 新增授权</button>
      </div>
      <div v-if="addingShare" class="row" style="margin-bottom: 14px">
        <Select v-model="shareTarget" :options="targetOptions" placeholder="选择你所在的其他工作空间" style="flex: 1" />
        <button class="btn btn-primary" @click="addShare">授权</button>
      </div>
      <div v-for="sp in shares" :key="sp.id" class="share-row">
        <span class="space-dot">{{ sp.name.slice(0, 1) }}</span><span style="flex: 1">{{ sp.name }}</span>
        <button class="icon-btn sm danger" @click="removeShare(sp)"><X /></button>
      </div>
      <Empty v-if="!shares.length && !addingShare" :icon="Share2" title="尚未共享给任何工作空间" desc="点击「新增授权」开始共享给其他工作空间">
        <button class="btn" style="margin-top: 8px" @click="addingShare = true"><Plus /> 新增授权</button>
      </Empty>
      <template #footer><button class="btn btn-block" @click="sharing = null">关闭</button></template>
    </Drawer>
  </div>
</template>

<style scoped>
.tag-filter { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.tf-ico { width: 14px; height: 14px; color: var(--text-3); margin-right: 2px; }
.tf { height: 26px; padding: 0 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; font-size: 12px; color: var(--text-2); }
.tf.on { background: var(--primary-soft); border-color: var(--primary-soft-2); color: var(--primary-text); font-weight: 600; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.sk { padding: 18px; cursor: pointer; display: flex; flex-direction: column; gap: 12px; transition: box-shadow .15s, border-color .15s; min-height: 200px; }
.sk:hover { box-shadow: var(--shadow-md); border-color: var(--primary-soft-2); }
.sk.invalid { opacity: .6; }
.sk-ico { width: 40px; height: 40px; border-radius: 11px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.sk-ico svg { width: 19px; height: 19px; }
.sk-name { font-weight: 700; font-size: 14.5px; }
.sk-desc { font-size: 12.5px; color: var(--text-2); line-height: 1.65; flex: 1; }
.sk-foot { display: flex; flex-wrap: wrap; gap: 6px; }
.share-row { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; margin-bottom: 8px; }
.space-dot { width: 24px; height: 24px; border-radius: 7px; background: var(--purple-soft); color: var(--purple-text); display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 12px; }
@media (max-width: 1100px) { .grid { grid-template-columns: repeat(2, 1fr); } }
</style>
