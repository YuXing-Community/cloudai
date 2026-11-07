<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus, Trash2, ChevronDown, Zap, Loader2, CheckCircle2, XCircle } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'
import { settingsApi, API_TYPES, MODEL_LEVELS, LEVEL_TONE, levelLabel } from '@/api/settings'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ open: boolean; provider: any | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const f = ref<any>({})
const testing = ref(false)
const testRes = ref<any>(null)
const saving = ref(false)

function blankModel() {
  return { modelId: '', displayName: '', level: 'medium', contextWindow: 128000, maxTokens: 8192, inputPrice: null, outputPrice: null, cachePrice: null, isDefault: false, _open: false }
}
watch(() => props.open, (v) => {
  if (!v) return
  testRes.value = null
  const p = props.provider
  f.value = p
    ? { providerKey: p.providerKey, name: p.name, baseUrl: p.baseUrl, apiKey: '', apiType: p.apiType, tokenCompress: !!p.tokenCompress,
        models: (p.models || []).map((m: any) => ({ ...m, _open: false })) }
    : { providerKey: '', name: '', baseUrl: '', apiKey: '', apiType: 'openai-completions', tokenCompress: false, models: [blankModel()] }
})

function payload() {
  return { ...f.value, models: f.value.models.filter((m: any) => m.modelId).map(({ _open, ...m }: any) => m) }
}
async function test() {
  testing.value = true
  testRes.value = null
  try {
    testRes.value = await settingsApi.testProvider({ providerId: props.provider?.id, baseUrl: f.value.baseUrl, apiKey: f.value.apiKey, apiType: f.value.apiType, modelId: f.value.models.find((m: any) => m.modelId)?.modelId })
  } finally {
    testing.value = false
  }
}
async function save() {
  saving.value = true
  try {
    if (props.provider) await settingsApi.updateProvider(props.provider.id, payload())
    else await settingsApi.createProvider(payload())
    toast.success('Provider 已保存')
    emit('saved')
    emit('close')
  } finally {
    saving.value = false
  }
}
function setDefault(m: any) {
  for (const x of f.value.models) x.isDefault = false
  m.isDefault = true
}
</script>

<template>
  <Modal :open="open" :title="provider ? '编辑 Provider' : '新增 Provider'" width="640px" @close="emit('close')">
    <div class="form-row">
      <div class="field"><label>Provider Key</label><input v-model="f.providerKey" class="input mono" placeholder="my-provider" :disabled="!!provider" /></div>
      <div class="field"><label>名称</label><input v-model="f.name" class="input" placeholder="My Provider" /></div>
    </div>
    <div class="field"><label>Base URL</label><input v-model="f.baseUrl" class="input mono" placeholder="https://api.example.com/v1" /></div>
    <div class="field">
      <label>API Key</label>
      <input v-model="f.apiKey" class="input mono" type="password" :placeholder="provider ? `${provider.apiKeyMasked || '未设置'}（留空不修改）` : 'sk-...'" autocomplete="new-password" />
    </div>
    <div class="field"><label>API 类型</label><Select v-model="f.apiType" :options="API_TYPES" /></div>
    <div class="box row" style="justify-content: space-between">
      <div><div class="bold">Token 压缩</div><div class="small muted">上下文接近上限时自动压缩早期工具输出（当前默认关闭）</div></div>
      <Switch v-model="f.tokenCompress" />
    </div>
    <div class="box models">
      <div class="row" style="justify-content: space-between; margin-bottom: 8px">
        <span class="bold">模型</span>
        <button class="btn btn-ghost btn-sm" @click="f.models.push(blankModel())"><Plus /> 添加</button>
      </div>
      <div v-for="(m, i) in f.models" :key="i" class="m">
        <div class="row">
          <input v-model="m.modelId" class="input mono" placeholder="新模型 ID" style="flex: 1" />
          <span v-if="m.isDefault" class="tag tag-blue">默认</span>
          <span class="tag" :class="LEVEL_TONE[m.level]">{{ levelLabel(m.level) }}</span>
          <button class="btn btn-sm" @click="m._open = !m._open">高级配置 <ChevronDown :style="{ transform: m._open ? 'rotate(180deg)' : '' }" /></button>
          <button class="icon-btn danger" @click="f.models.splice(i, 1)"><Trash2 /></button>
        </div>
        <div v-if="m._open" class="adv">
          <div class="form-row">
            <div class="field"><label>显示名</label><input v-model="m.displayName" class="input" placeholder="可选" /></div>
            <div class="field"><label>模型等级</label><Select v-model="m.level" :options="MODEL_LEVELS" /></div>
          </div>
          <div class="form-row">
            <div class="field"><label>上下文窗口 (tokens)</label><input v-model.number="m.contextWindow" type="number" class="input" /></div>
            <div class="field"><label>最大输出 (tokens)</label><input v-model.number="m.maxTokens" type="number" class="input" /></div>
          </div>
          <div class="form-row-3">
            <div class="field"><label>输入价格 (元/百万)</label><input v-model.number="m.inputPrice" type="number" step="0.01" class="input" placeholder="未配置" /></div>
            <div class="field"><label>输出价格 (元/百万)</label><input v-model.number="m.outputPrice" type="number" step="0.01" class="input" placeholder="未配置" /></div>
            <div class="field"><label>缓存价格 (元/百万)</label><input v-model.number="m.cachePrice" type="number" step="0.01" class="input" placeholder="同输入" /></div>
          </div>
          <button class="btn btn-sm" :disabled="m.isDefault" @click="setDefault(m)">{{ m.isDefault ? '已是空间默认模型' : '设为空间默认模型' }}</button>
        </div>
      </div>
    </div>
    <div v-if="testRes" class="notice" :class="testRes.ok ? 'notice-info' : 'notice-danger'" style="margin-top: 12px">
      <CheckCircle2 v-if="testRes.ok" /><XCircle v-else />
      <span>{{ testRes.ok ? `连接成功 · ${testRes.model} · ${testRes.latencyMs} ms · 回复：${testRes.text}` : testRes.error }}</span>
    </div>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn" :disabled="testing" @click="test"><Loader2 v-if="testing" class="spin" /><Zap v-else /> 测试连接</button>
      <button class="btn btn-primary" :disabled="saving" @click="save">保存</button>
    </template>
  </Modal>
</template>

<style scoped>
.box { border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; margin-bottom: 12px; }
.m { padding: 8px 0; border-top: 1px solid var(--divider); }
.m:first-of-type { border-top: none; }
.adv { padding: 12px 0 4px; }
.btn svg { transition: transform .15s; }
</style>
