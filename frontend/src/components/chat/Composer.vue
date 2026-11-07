<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ArrowUp, Check, ChevronDown, Paperclip, Plus, Search, Sparkles, Square, X, Loader2 } from 'lucide-vue-next'
import Popover from '@/components/ui/Popover.vue'
import type { ModelOption } from '@/api/session'
import { http } from '@/api/http'
import { LEVEL_TONE, levelLabel } from '@/api/settings'
import { bytes, kfmt } from '@/utils/format'
import { toast } from '@/components/ui/toast'

const props = withDefaults(defineProps<{
  models: ModelOption[]
  modelRef?: string | null
  running?: boolean
  disabled?: boolean
  placeholder?: string
  allowAttach?: boolean
  sessionId?: string | null
  minRows?: number
}>(), { running: false, disabled: false, placeholder: '描述你想完成的任务…', allowAttach: true, minRows: 2 })

const emit = defineEmits<{
  (e: 'send', text: string, fileIds: string[]): void
  (e: 'stop'): void
  (e: 'update:modelRef', v: string): void
}>()

const text = ref('')
const files = ref<{ id?: string; name: string; size: number; uploading: boolean }[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const ta = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)

// ---------- 模型选择 ----------
const mOpen = ref(false)
const mBtn = ref<HTMLElement | null>(null)
const mq = ref('')
const current = computed(() => {
  for (const p of props.models) for (const m of p.models) if (m.ref === props.modelRef) return m
  return null
})
const filteredModels = computed(() => {
  const k = mq.value.trim().toLowerCase()
  return props.models
    .map((p) => ({ ...p, models: p.models.filter((m) => !k || `${m.modelId} ${m.displayName || ''} ${p.providerKey} ${p.providerName}`.toLowerCase().includes(k)) }))
    .filter((p) => p.models.length)
})
function pickModel(ref: string) {
  emit('update:modelRef', ref)
  mOpen.value = false
}

// ---------- 附件 ----------
async function onFiles(e: Event) {
  const list = Array.from((e.target as HTMLInputElement).files || [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of list) {
    if (f.size > 100 * 1024 * 1024) { toast.error(`${f.name} 超过 100MB`); continue }
    const item = { name: f.name, size: f.size, uploading: true } as any
    files.value.push(item)
    const fd = new FormData()
    fd.append('file', f)
    fd.append('source', 'chat')
    if (props.sessionId) fd.append('sessionId', props.sessionId)
    try {
      const r = await http.upload<{ id: string }>('/files', fd)
      item.id = r.id
    } catch {
      files.value = files.value.filter((x) => x !== item)
    } finally {
      item.uploading = false
    }
  }
}

const canSend = computed(() => !props.disabled && !props.running && text.value.trim().length > 0 && !files.value.some((f) => f.uploading))

function send() {
  if (!canSend.value) return
  emit('send', text.value.trim(), files.value.filter((f) => f.id).map((f) => f.id!))
  text.value = ''
  files.value = []
  nextTick(autosize)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !composing.value) {
    e.preventDefault()
    send()
  }
}
function autosize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 260) + 'px'
}
defineExpose({ focus: () => ta.value?.focus(), setText: (t: string) => { text.value = t; nextTick(autosize) } })
</script>

<template>
  <div class="composer" :class="{ disabled }">
    <div v-if="files.length" class="files">
      <span v-for="(f, i) in files" :key="i" class="file-chip">
        <Loader2 v-if="f.uploading" class="spin" /><Paperclip v-else />
        <span class="ellipsis">{{ f.name }}</span><span class="faint">{{ bytes(f.size) }}</span>
        <button @click="files.splice(i, 1)"><X /></button>
      </span>
    </div>
    <textarea
      ref="ta"
      v-model="text"
      :rows="minRows"
      :placeholder="placeholder"
      :disabled="disabled"
      @keydown="onKey"
      @input="autosize"
      @compositionstart="composing = true"
      @compositionend="composing = false"
    />
    <div class="bar">
      <template v-if="allowAttach">
        <button class="icon-btn" title="上传附件" :disabled="disabled" @click="fileInput?.click()"><Plus /></button>
        <input ref="fileInput" type="file" multiple hidden @change="onFiles" />
      </template>
      <slot name="left" />
      <div class="spacer" />
      <button v-if="models.length" ref="mBtn" class="model-pill" @click="mOpen = !mOpen">
        <Sparkles class="mp-ico" />
        <span>{{ current ? (current.displayName || current.modelId) : modelRef ? modelRef.split('/').pop() : '选择模型' }}</span>
        <span v-if="current?.contextWindow" class="faint">· {{ kfmt(current.contextWindow).replace('.0', '') }}</span>
        <ChevronDown class="mp-arrow" />
      </button>
      <button v-if="running" class="send stop" title="停止" @click="emit('stop')"><Square /></button>
      <button v-else class="send" :disabled="!canSend" title="发送 (Enter)" @click="send"><ArrowUp /></button>
    </div>

    <Popover :open="mOpen" :anchor="mBtn" align="end" :min-width="300" @close="mOpen = false">
      <div class="m-search"><Search /><input v-model="mq" placeholder="搜索模型或 Provider…" /></div>
      <div class="m-list">
        <template v-for="p in filteredModels" :key="p.providerKey">
          <div class="m-group">{{ p.providerName || p.providerKey }}</div>
          <div v-for="m in p.models" :key="m.ref" class="m-item" :class="{ active: m.ref === modelRef }" @click="pickModel(m.ref)">
            <Sparkles class="m-ico" />
            <div class="m-main">
              <div>{{ m.displayName || m.modelId }}<span v-if="m.contextWindow" class="faint"> · {{ kfmt(m.contextWindow).replace('.0', '') }}</span></div>
              <div v-if="m.ref === modelRef" class="m-sub">{{ m.modelId }}</div>
            </div>
            <span v-if="m.level" class="tag" :class="LEVEL_TONE[m.level]">{{ levelLabel(m.level) }}</span>
            <span v-if="m.isDefault" class="m-def">默认</span>
            <Check v-if="m.ref === modelRef" class="m-check" />
          </div>
        </template>
        <div v-if="!filteredModels.length" class="m-empty">没有可用模型，请先在「空间配置 → 模型管理」中添加</div>
      </div>
    </Popover>
  </div>
</template>

<style scoped>
.composer { background: var(--bg-elev); border: 1px solid var(--border); border-radius: 16px; box-shadow: 0 4px 18px rgba(16, 24, 40, .07); padding: 12px 12px 10px; transition: border-color .15s, box-shadow .15s; }
.composer:focus-within { border-color: var(--primary-soft-2); box-shadow: 0 4px 22px rgba(22, 119, 255, .1); }
.composer.disabled { opacity: .7; }
textarea { width: 100%; border: none; outline: none; resize: none; background: transparent; font-size: 14px; line-height: 1.6; padding: 2px 6px; color: var(--text); min-height: 44px; }
textarea::placeholder { color: var(--text-4); }
.bar { display: flex; align-items: center; gap: 6px; margin-top: 6px; }
.model-pill { display: inline-flex; align-items: center; gap: 5px; height: 28px; padding: 0 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; font-size: 12px; font-weight: 500; color: var(--text-2); }
.model-pill:hover { background: var(--bg-hover); }
.mp-ico { width: 12px; height: 12px; color: var(--purple); }
.mp-arrow { width: 12px; height: 12px; color: var(--text-4); }
.send { width: 32px; height: 32px; border-radius: 50%; border: none; background: var(--primary); color: #fff; display: inline-flex; align-items: center; justify-content: center; cursor: pointer; transition: opacity .15s, background .15s; }
.send svg { width: 16px; height: 16px; }
.send:disabled { background: var(--bg-muted); color: var(--text-4); cursor: not-allowed; }
.send.stop { background: var(--text); }
.send.stop svg { width: 12px; height: 12px; fill: currentColor; }
.files { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.file-chip { display: inline-flex; align-items: center; gap: 6px; max-width: 260px; padding: 4px 6px 4px 8px; border-radius: 8px; background: var(--bg-muted); border: 1px solid var(--border); font-size: 12px; }
.file-chip svg { width: 13px; height: 13px; color: var(--text-3); flex-shrink: 0; }
.file-chip button { border: none; background: transparent; cursor: pointer; display: flex; padding: 0; color: var(--text-4); }
.m-search { display: flex; align-items: center; gap: 6px; padding: 6px 8px 8px; border-bottom: 1px solid var(--divider); margin-bottom: 4px; }
.m-search svg { width: 14px; height: 14px; color: var(--text-4); }
.m-search input { flex: 1; border: none; outline: none; background: transparent; font-size: 13px; }
.m-list { max-height: 340px; overflow: auto; }
.m-group { padding: 8px 8px 4px; font-size: 11.5px; color: var(--text-3); }
.m-item { display: flex; align-items: center; gap: 9px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 500; position: relative; }
.m-item:hover { background: var(--bg-hover); }
.m-item.active { background: var(--primary-soft); }
.m-item.active::before { content: ""; position: absolute; left: -4px; top: 6px; bottom: 6px; width: 2px; border-radius: 2px; background: var(--primary); }
.m-ico { width: 13px; height: 13px; color: var(--purple); flex-shrink: 0; }
.m-main { flex: 1; min-width: 0; }
.m-sub { font-family: var(--mono); font-size: 11px; color: var(--text-3); }
.m-def { font-size: 11.5px; color: var(--text-3); }
.m-check { width: 14px; height: 14px; color: var(--primary); }
.m-empty { padding: 16px; text-align: center; color: var(--text-4); font-size: 12.5px; }
</style>
