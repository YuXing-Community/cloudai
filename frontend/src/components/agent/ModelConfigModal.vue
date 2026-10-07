<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ExternalLink, Route, RefreshCw, Brain, Plus, Trash2, HelpCircle } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import Switch from '@/components/ui/Switch.vue'

const props = defineProps<{ open: boolean; model: any; options: any[] }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'save', v: any): void }>()

const m = ref<any>({})
watch(() => props.open, (v) => {
  if (v) {
    const src = props.model || {}
    m.value = {
      primary: src.primary || '',
      fallbacks: [...(src.fallbacks || [])],
      retry: { enabled: true, times: 3, baseDelay: 5, maxDelay: 60, ...(src.retry || {}) },
      showThinking: !!src.showThinking,
    }
  }
})

const opts = computed(() => props.options.flatMap((p: any) => p.models.map((x: any) => ({
  label: `${p.providerKey}/${x.modelId}`, value: x.ref, group: p.providerName || p.providerKey, extra: x.isDefault ? '默认' : undefined,
}))))
const candidates = computed(() => 1 + m.value.fallbacks?.filter((x: string) => x).length)

function save() {
  emit('save', { ...m.value, fallbacks: m.value.fallbacks.filter((x: string) => x && x !== m.value.primary) })
  emit('close')
}
</script>

<template>
  <Modal :open="open" title="模型调用配置" subtitle="配置主模型、备用容灾模型及异常自动重试策略" width="620px" @close="emit('close')">
    <template #head-extra>
      <RouterLink to="/settings/models" class="btn btn-ghost btn-sm" target="_blank">管理模型 <ExternalLink /></RouterLink>
    </template>
    <div class="blk-title"><Route class="bt-ico" /> 模型路由 <span class="spacer" /><span class="small muted">候选模型：<b>{{ candidates }}</b></span></div>
    <div class="field">
      <label>主模型</label>
      <Select v-model="m.primary" :options="opts" searchable placeholder="选择主模型" search-placeholder="搜索模型或 Provider…" />
    </div>
    <div class="field">
      <label>备用模型 <HelpCircle class="q" title="主模型失败（重试耗尽）时依次切换到备用模型" /></label>
      <div v-for="(f, i) in m.fallbacks" :key="i" class="fb-row">
        <Select v-model="m.fallbacks[i]" :options="opts" searchable placeholder="选择备用模型" />
        <button class="icon-btn danger" @click="m.fallbacks.splice(i, 1)"><Trash2 /></button>
      </div>
      <button class="dashed" @click="m.fallbacks.push('')"><Plus /> 添加备用模型</button>
    </div>
    <hr class="divider" />
    <div class="blk-title"><RefreshCw class="bt-ico" /> 模型自动重试 <HelpCircle class="q" title="模型接口返回 429/5xx/超时时按指数退避重试" /><span class="spacer" /><Switch v-model="m.retry.enabled" /></div>
    <div v-if="m.retry.enabled" class="form-row-3">
      <div class="field"><label>重试次数 (次)</label><input v-model.number="m.retry.times" type="number" min="0" max="20" class="input" /></div>
      <div class="field"><label>基础延迟 (秒)</label><input v-model.number="m.retry.baseDelay" type="number" min="1" class="input" /></div>
      <div class="field"><label>最大延迟 (秒)</label><input v-model.number="m.retry.maxDelay" type="number" min="1" class="input" /></div>
    </div>
    <hr class="divider" />
    <div class="blk-title"><Brain class="bt-ico" /> 展示思考过程 <HelpCircle class="q" title="模型返回推理内容时在会话中展示" /><span class="spacer" /><Switch v-model="m.showThinking" /></div>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" @click="save">保存应用</button>
    </template>
  </Modal>
</template>

<style scoped>
.blk-title { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; margin-bottom: 12px; }
.bt-ico { width: 17px; height: 17px; color: var(--purple); }
.q { width: 13px; height: 13px; color: var(--text-4); cursor: help; }
.fb-row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.fb-row > :first-child { flex: 1; }
.dashed { width: 100%; height: 40px; border: 1px dashed var(--border-strong); border-radius: 10px; background: transparent; color: var(--text-3); cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; }
.dashed:hover { border-color: var(--primary); color: var(--primary); }
.dashed svg { width: 14px; height: 14px; }
</style>
