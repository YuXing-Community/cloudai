<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Copy, Timer, Trash2, MessageSquare, SquareTerminal } from 'lucide-vue-next'
import { sandboxApi } from '@/api/sandbox'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import DropdownMenu from '@/components/ui/DropdownMenu.vue'
import Modal from '@/components/ui/Modal.vue'
import Empty from '@/components/ui/Empty.vue'
import { confirm, toast } from '@/components/ui/toast'
import { copyText, debounce, fmtTime, remain } from '@/utils/format'

const auth = useAuth()
const router = useRouter()
const q = ref('')
const env = ref('')
const status = ref('running')
const page = ref(1)
const size = 10
const rows = ref<any[]>([])
const total = ref(0)
const envs = ref<any[]>([])
const loading = ref(false)
const tick = ref(0)

async function load() {
  loading.value = true
  try {
    const r = await sandboxApi.sandboxes({ q: q.value, env: env.value, status: status.value, page: page.value, size })
    rows.value = r.records
    total.value = r.total
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  load()
  envs.value = (await sandboxApi.envs()).page.records
  timer = setInterval(() => tick.value++, 30_000)
})
let timer: any
onBeforeUnmount(() => clearInterval(timer))
watch(q, debounce(() => { page.value = 1; load() }, 300))
watch([env, status], () => { page.value = 1; load() })

const envOptions = computed(() => [{ label: '全部', value: '' }, ...envs.value.map((e) => ({ label: e.name, value: e.name }))])
const statusOptions = [{ label: '运行中', value: 'running' }, { label: '已销毁', value: 'destroyed' }, { label: '全部', value: 'all' }]

const tmo = ref<any>(null)
const tmoSec = ref(1800)
function openTimeout(s: any) {
  tmo.value = s
  tmoSec.value = s.timeoutSeconds || 1800
}
async function saveTimeout() {
  await sandboxApi.setTimeout(tmo.value.id, tmoSec.value)
  toast.success('超时策略已更新')
  tmo.value = null
  load()
}
async function destroy(s: any) {
  if (!(await confirm({ title: `销毁 ${s.name}？`, message: '会立即停止正在执行的任务，工作区文件保留。', danger: true, okText: '销毁' }))) return
  await sandboxApi.destroy(s.id)
  toast.success('已销毁')
  load()
}
function menu(s: any) {
  const can = auth.can('sandbox:edit')
  return [
    { label: '设置超时', icon: Timer, disabled: !can || s.status !== 'running', onClick: () => openTimeout(s) },
    { label: '查看会话', icon: MessageSquare, disabled: !s.sessionId, onClick: () => router.push(`/sessions/${s.sessionId}`) },
    { label: '复制 Pod 名', icon: Copy, onClick: () => { copyText(s.podName); toast.success('已复制') } },
    { divider: true, label: '' },
    { label: '销毁', icon: Trash2, danger: true, disabled: !can || s.status !== 'running', onClick: () => destroy(s) },
  ]
}
const remainText = (s: any) => (tick.value, remain(s.destroyAt))
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h1 class="page-title">{{ auth.space?.name }} 的 Sandbox</h1>
    </div>
    <div class="toolbar" style="margin-bottom: 12px">
      <SearchInput v-model="q" placeholder="搜索名称或 ID" width="240px" />
      <span class="small muted">运行环境</span>
      <Select v-model="env" :options="envOptions" size="sm" style="width: 160px" />
      <span class="small muted">状态</span>
      <Select v-model="status" :options="statusOptions" size="sm" style="width: 110px" />
      <div class="spacer" />
      <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr><th style="width: 30px" /><th>名称</th><th>状态</th><th>运行环境</th><th>资源组</th><th>Owner</th><th>创建时间</th><th>销毁时间</th><th class="actions">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in rows" :key="s.id">
            <td><span class="ring" :class="{ busy: s.running }" /></td>
            <td>
              <div class="row" style="gap: 4px">
                <span class="cell-main ellipsis" style="max-width: 180px">{{ s.name }}</span>
                <button class="icon-btn sm" @click="copyText(s.id); toast.success('ID 已复制')"><Copy /></button>
              </div>
            </td>
            <td>
              <span v-if="s.status === 'running'" class="tag tag-green tag-round">{{ s.running ? '执行中' : '运行中' }}</span>
              <span v-else class="tag tag-round">已销毁</span>
            </td>
            <td>
              <div>{{ s.envName }}</div>
              <div class="cell-sub">{{ s.podName }}</div>
            </td>
            <td>{{ s.resourceGroup }}</td>
            <td class="mono small">{{ s.owner }}</td>
            <td class="small">{{ fmtTime(s.createdAt) }}</td>
            <td class="small">
              <div>{{ fmtTime(s.destroyAt) }}</div>
              <div v-if="s.status === 'running'" class="tiny" style="color: var(--orange-text)">{{ remainText(s) }}后销毁</div>
            </td>
            <td class="actions"><DropdownMenu :items="menu(s)" /></td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="SquareTerminal" title="暂无 Sandbox" desc="Agent 开始执行会话时会自动创建沙箱实例" />
    </div>
    <div class="table-foot">
      <span>共 {{ total }} 个实例</span>
      <div class="row">
        <button class="btn btn-sm" :disabled="page <= 1" @click="page--; load()">上一页</button>
        <span class="small">第 {{ page }} 页</span>
        <button class="btn btn-sm" :disabled="page * size >= total" @click="page++; load()">下一页</button>
      </div>
    </div>

    <Modal :open="!!tmo" title="设置超时" subtitle="更新 sandbox 的自动超时策略，单位为秒。" width="420px" @close="tmo = null">
      <div class="cur">当前实例：{{ tmo?.podName }}</div>
      <div class="field"><label>超时秒数</label><input v-model.number="tmoSec" type="number" min="60" class="input" /></div>
      <template #footer>
        <button class="btn" @click="tmo = null">取消</button>
        <button class="btn btn-primary" @click="saveTimeout">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.ring { display: inline-block; width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--primary); }
.ring.busy { background: var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); animation: pulse 1.4s infinite; }
.cur { padding: 8px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-muted); font-size: 12.5px; margin-bottom: 14px; word-break: break-all; }
</style>
