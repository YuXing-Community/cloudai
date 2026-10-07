<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Play, Loader2, CheckCircle2, XCircle } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import { pluginApi } from '@/api/plugin'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ plugin: any; tool: any | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()
const args = ref('{}')
const running = ref(false)
const res = ref<any>(null)

/** 根据 input_schema 生成参数模板 */
function template(schema: any) {
  const out: any = {}
  for (const [k, v] of Object.entries<any>(schema?.properties || {})) {
    out[k] = v.default ?? (v.type === 'integer' || v.type === 'number' ? 0 : v.type === 'boolean' ? false : v.type === 'array' ? [] : v.type === 'object' ? {} : '')
  }
  return JSON.stringify(out, null, 2)
}
watch(() => props.tool, (t) => {
  res.value = null
  if (t) args.value = template(t.inputSchema)
})
const required = computed(() => (props.tool?.inputSchema?.required || []) as string[])

async function run() {
  let a: any
  try { a = JSON.parse(args.value || '{}') } catch { return toast.error('参数不是合法 JSON') }
  running.value = true
  res.value = null
  try {
    res.value = await pluginApi.invoke(props.plugin.id, props.tool.id, a)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <Modal :open="!!tool" :title="`调试 ${tool?.name || ''}`" :subtitle="tool?.description" width="680px" @close="emit('close')">
    <div class="label" style="margin-bottom: 6px">参数（JSON）<span v-if="required.length" class="hint" style="margin-left: 8px">必填：{{ required.join('、') }}</span></div>
    <textarea v-model="args" class="textarea mono" rows="8" spellcheck="false" />
    <details v-if="tool?.inputSchema" class="schema">
      <summary class="small muted">查看 input schema</summary>
      <pre class="code-soft">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
    </details>
    <div v-if="res" class="res">
      <div class="row small" style="margin-bottom: 6px">
        <CheckCircle2 v-if="res.ok" style="width: 15px; color: var(--green)" /><XCircle v-else style="width: 15px; color: var(--red)" />
        <span class="bold">{{ res.ok ? '调用成功' : '调用失败' }}</span><span class="muted">· {{ res.duration_ms }} ms</span>
      </div>
      <pre class="code-soft out">{{ res.result }}</pre>
    </div>
    <template #footer>
      <button class="btn" @click="emit('close')">关闭</button>
      <button class="btn btn-primary" :disabled="running" @click="run"><Loader2 v-if="running" class="spin" /><Play v-else /> 执行</button>
    </template>
  </Modal>
</template>

<style scoped>
.schema { margin-top: 8px; }
.schema pre { margin-top: 6px; max-height: 200px; }
.res { margin-top: 14px; }
.out { max-height: 320px; margin: 0; }
</style>
