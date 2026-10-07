<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RefreshCw, Plus, Bot, SlidersHorizontal, Trash2, Zap, Sparkles, Star } from 'lucide-vue-next'
import { settingsApi } from '@/api/settings'
import { useAuth } from '@/stores/auth'
import ProviderModal from './ProviderModal.vue'
import { confirm, toast } from '@/components/ui/toast'
import { ago, kfmt, thousands } from '@/utils/format'

const auth = useAuth()
const canManage = computed(() => auth.can('model:manage'))
const providers = ref<any[]>([])
const stats = ref<any>({})
const loading = ref(false)
const editOpen = ref(false)
const editing = ref<any>(null)
const testing = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const r = await settingsApi.providers()
    providers.value = r.providers
    stats.value = r.stats
  } finally {
    loading.value = false
  }
}
onMounted(load)

function edit(p?: any) {
  editing.value = p || null
  editOpen.value = true
}
async function remove(p: any) {
  if (!(await confirm({ title: `删除 Provider「${p.name}」？`, message: '使用这些模型的 Agent 将无法运行，需要重新选择模型。', danger: true, okText: '删除' }))) return
  await settingsApi.deleteProvider(p.id)
  toast.success('已删除')
  load()
}
async function test(p: any) {
  testing.value = p.id
  try {
    const r = await settingsApi.testProvider({ providerId: p.id })
    r.ok ? toast.success(`${p.name} 连接正常 · ${r.latencyMs} ms`) : toast.error(r.error || '连接失败')
  } finally {
    testing.value = null
  }
}
async function makeDefault(p: any, m: any) {
  await settingsApi.setDefaultModel(`${p.providerKey}/${m.modelId}`)
  toast.success(`默认模型已设为 ${m.modelId}`)
  load()
}
const LEVEL: Record<string, string> = { trial: '体验', standard: '标准', high: 'high' }
</script>

<template>
  <div>
    <div class="row" style="justify-content: space-between; align-items: flex-start; margin-bottom: 16px">
      <div>
        <h2 class="h">模型管理</h2>
        <p class="d">{{ auth.space?.name }} · {{ stats.providers || 0 }} 个 Provider · {{ stats.models || 0 }} 个模型</p>
      </div>
      <div class="row">
        <button class="btn" @click="load"><RefreshCw :class="{ spin: loading }" /> 刷新</button>
        <button v-if="canManage" class="btn btn-primary" @click="edit()"><Plus /> 添加 Provider</button>
      </div>
    </div>

    <div class="stats card">
      <div class="st"><div class="st-k">Provider</div><div class="st-v">{{ stats.providers || 0 }}</div></div>
      <div class="st"><div class="st-k">可用模型</div><div class="st-v">{{ stats.models || 0 }}</div></div>
      <div class="st"><div class="st-k">今日调用</div><div class="st-v">{{ thousands(stats.todayCalls || 0) }}</div></div>
      <div class="st"><div class="st-k">平均延迟</div><div class="st-v">{{ stats.avgLatencyMs ? (stats.avgLatencyMs / 1000).toFixed(1) + 's' : '—' }}</div></div>
    </div>

    <div v-for="p in providers" :key="p.id" class="prov card">
      <div class="pv-left">
        <div class="pv-ico"><Bot /></div>
        <div style="min-width: 0; flex: 1">
          <div class="row" style="gap: 8px; flex-wrap: wrap">
            <span class="pv-name">{{ p.name }}</span>
            <span class="tag tag-mono">{{ p.providerKey }}</span>
            <span class="ok"><span class="status-dot" style="background: var(--green)" /> 可用</span>
            <span class="tiny muted">· 更新于 {{ ago(p.updatedAt, false) }}</span>
            <div class="spacer" />
            <template v-if="canManage">
              <button class="icon-btn sm" title="测试连接" @click="test(p)"><Zap :class="{ pulse: testing === p.id }" /></button>
              <button class="icon-btn sm" title="编辑" @click="edit(p)"><SlidersHorizontal /></button>
              <button class="icon-btn sm danger" title="删除" @click="remove(p)"><Trash2 /></button>
            </template>
          </div>
          <div class="row" style="gap: 8px; margin-top: 6px; flex-wrap: wrap">
            <span class="mono small">{{ p.baseUrl }}</span>
            <span class="tag tag-mono">{{ p.apiType }}</span>
            <span class="tag">{{ p.tokenType === 'personal' ? '个人 token' : '自定义 token' }} <span class="mono" style="font-weight: 400">{{ p.apiKeyMasked }}</span></span>
          </div>
        </div>
      </div>
      <div class="pv-models">
        <div v-for="m in p.models" :key="m.id" class="pm">
          <Sparkles class="pm-ico" />
          <span class="mono bold">{{ m.displayName || m.modelId }}</span>
          <span v-if="m.level" class="tag" :class="m.level === 'high' ? 'tag-purple' : m.level === 'trial' ? 'tag-cyan' : ''">{{ LEVEL[m.level] || m.level }}</span>
          <span class="tag">{{ kfmt(m.contextWindow).replace('.0', '') }} context</span>
          <span v-if="m.inputPrice != null" class="tiny muted">¥{{ m.inputPrice }}/{{ m.outputPrice }}</span>
          <div class="spacer" />
          <span v-if="m.isDefault" class="tag tag-blue"><Star /> 默认</span>
          <button v-else-if="canManage" class="link-btn tiny" @click="makeDefault(p, m)">设为默认</button>
        </div>
        <div v-if="!p.models.length" class="tiny muted">暂无模型</div>
      </div>
    </div>
    <div v-if="!providers.length && !loading" class="empty card">
      <div class="empty-icon"><Bot /></div>
      <div class="empty-title">还没有模型 Provider</div>
      <div class="small">添加 OpenAI 兼容 / Responses / Anthropic 协议的模型网关后，Agent 才能运行</div>
      <button v-if="canManage" class="btn btn-primary" style="margin-top: 10px" @click="edit()"><Plus /> 添加 Provider</button>
    </div>

    <ProviderModal :open="editOpen" :provider="editing" @close="editOpen = false" @saved="load" />
  </div>
</template>

<style scoped>
.h { font-size: 15px; font-weight: 650; }
.d { color: var(--text-3); font-size: 12.5px; margin-top: 4px; }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); margin-bottom: 16px; }
.st { padding: 16px 18px; border-right: 1px solid var(--divider); }
.st:last-child { border-right: none; }
.st-k { font-size: 12px; color: var(--text-3); }
.st-v { font-size: 22px; font-weight: 700; margin-top: 4px; }
.prov { display: grid; grid-template-columns: 1.3fr 1fr; margin-bottom: 12px; overflow: hidden; }
.pv-left { display: flex; gap: 12px; padding: 16px; }
.pv-ico { width: 38px; height: 38px; border-radius: 10px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.pv-ico svg { width: 19px; height: 19px; }
.pv-name { font-weight: 650; font-size: 15px; }
.ok { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--green-text); }
.pv-models { background: var(--bg-muted); border-left: 1px solid var(--divider); padding: 12px 16px; display: flex; flex-direction: column; gap: 8px; justify-content: center; }
.pm { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.pm-ico { width: 13px; height: 13px; color: var(--purple); }
.tag svg { width: 10px; height: 10px; }
</style>
