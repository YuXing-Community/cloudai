<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw, Archive, LayoutGrid } from 'lucide-vue-next'
import { teamApi, TS_STATUS, PATTERN } from '@/api/team'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import Empty from '@/components/ui/Empty.vue'
import { toast } from '@/components/ui/toast'
import { useAuth } from '@/stores/auth'
import { debounce, fmtTime } from '@/utils/format'

const router = useRouter()
const auth = useAuth()
const status = ref('all')
const q = ref('')
const teamId = ref('')
const teams = ref<any[]>([])
const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try { rows.value = await teamApi.sessions({ q: q.value, status: status.value, teamId: teamId.value }) } finally { loading.value = false }
}
onMounted(async () => { load(); teams.value = await teamApi.list() })
watch(q, debounce(load, 300))
watch([status, teamId], load)
const teamOptions = computed(() => [{ label: '全部', value: '' }, ...teams.value.map((t) => ({ label: t.name, value: t.id }))])
async function archive(r: any) {
  await teamApi.archive(r.id)
  toast.success('已归档')
  load()
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title" style="font-size: 22px">Task Board</h1>
        <div class="page-sub">Agent Team 运行任务列表</div>
      </div>
      <div class="page-actions">
        <div class="segmented">
          <button :class="{ active: status === 'all' }" @click="status = 'all'">全部</button>
          <button :class="{ active: status === 'running' }" @click="status = 'running'">执行中</button>
          <button :class="{ active: status === 'waiting_input' }" @click="status = 'waiting_input'">等待输入</button>
        </div>
        <SearchInput v-model="q" placeholder="搜索任务名称或任务目标" width="240px" />
        <span class="small muted">筛选 Team：</span>
        <Select v-model="teamId" :options="teamOptions" style="width: 160px" />
        <button class="icon-btn bordered" @click="load"><RefreshCw :class="{ spin: loading }" /></button>
      </div>
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>任务名称</th><th>任务目标</th><th>任务提交人</th><th>所属 Team</th><th>架构</th><th>状态</th><th>创建时间</th><th>结束时间</th><th class="actions" /></tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id">
            <td><div class="cell-main">{{ r.name }}</div><div class="cell-sub" style="max-width: 200px; white-space: normal; word-break: break-all">{{ r.id }}</div></td>
            <td class="small ellipsis" style="max-width: 320px" :title="r.goal">{{ r.goal || '—' }}</td>
            <td class="small">{{ r.submitter }}</td>
            <td class="small">{{ r.teamName }}</td>
            <td class="small">{{ PATTERN[r.pattern] || r.pattern }}</td>
            <td><span class="tag tag-round" :class="TS_STATUS[r.status]?.t">{{ TS_STATUS[r.status]?.l || r.status }}</span></td>
            <td class="small">{{ fmtTime(r.createdAt) }}</td>
            <td class="small">{{ r.finishedAt ? fmtTime(r.finishedAt) : '—' }}</td>
            <td class="actions">
              <button class="btn btn-sm" @click="router.push(`/taskboard/${r.id}`)">看板</button>
              <button v-if="auth.can('team:edit')" class="icon-btn" title="归档" @click="archive(r)"><Archive /></button>
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length && !loading" :icon="LayoutGrid" title="暂无任务" desc="在「Agent小队」中发起任务后，执行过程会出现在这里">
        <button class="btn" style="margin-top: 8px" @click="router.push('/teams')">前往 Agent 小队</button>
      </Empty>
    </div>
  </div>
</template>
