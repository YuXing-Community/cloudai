<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { AlertTriangle, Brain, ChevronRight, Package, Square, Wrench } from 'lucide-vue-next'
import type { SessionEvent } from '@/api/session'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import ToolCallCard from './ToolCallCard.vue'
import { fmtTime } from '@/utils/format'

const props = withDefaults(defineProps<{
  events: SessionEvent[]
  running?: boolean
  agentName?: string
  agentAvatar?: string
  compact?: boolean
}>(), { running: false, agentName: 'Agent', compact: false })
const emit = defineEmits<{ (e: 'stop'): void; (e: 'open-artifact', fileId: string): void }>()

/** 把事件流组织成“轮”：用户输入 + Agent 的一组步骤（文本/工具/思考/错误） */
interface Step { kind: 'text' | 'tools' | 'thinking' | 'error' | 'artifact'; events: SessionEvent[] }
interface Turn { input?: SessionEvent; steps: Step[] }

const turns = computed<Turn[]>(() => {
  const out: Turn[] = []
  let cur: Turn | null = null
  for (const ev of props.events) {
    if (ev.type === 'model') continue
    if (ev.type === 'input') {
      cur = { input: ev, steps: [] }
      out.push(cur)
      continue
    }
    if (!cur) { cur = { steps: [] }; out.push(cur) }
    const last = cur.steps[cur.steps.length - 1]
    if (ev.type === 'tool') {
      if (last && last.kind === 'tools') last.events.push(ev)
      else cur.steps.push({ kind: 'tools', events: [ev] })
    } else if (ev.type === 'agent') cur.steps.push({ kind: 'text', events: [ev] })
    else if (ev.type === 'thinking') cur.steps.push({ kind: 'thinking', events: [ev] })
    else if (ev.type === 'error') cur.steps.push({ kind: 'error', events: [ev] })
    else if (ev.type === 'status' && ev.name === 'artifact') cur.steps.push({ kind: 'artifact', events: [ev] })
  }
  return out
})

const openGroups = ref<Record<string, boolean>>({})
function groupKey(s: Step) { return String(s.events[0].id) }
function isGroupOpen(s: Step, isLastStep: boolean) {
  const k = groupKey(s)
  if (k in openGroups.value) return openGroups.value[k]
  return isLastStep && props.running
}

function artifact(ev: SessionEvent) {
  try { return JSON.parse(ev.content || '{}') } catch { return {} }
}

const scroller = ref<HTMLElement | null>(null)
let stick = true
function onScroll() {
  const el = scroller.value
  if (!el) return
  stick = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}
watch(() => [props.events.length, props.events[props.events.length - 1]?.status, props.running], async () => {
  await nextTick()
  if (stick && scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}, { immediate: true })
defineExpose({ scrollToBottom: () => { stick = true; if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight } })
</script>

<template>
  <div ref="scroller" class="msgs" :class="{ compact }" @scroll="onScroll">
    <div class="msgs-inner">
      <slot name="empty" v-if="!turns.length" />
      <div v-for="(t, ti) in turns" :key="ti" class="turn">
        <div v-if="t.input" class="user-row">
          <div class="bubble">
            <div class="bubble-text">{{ t.input.content }}</div>
          </div>
          <div class="u-time">{{ fmtTime(t.input.createdAt) }}</div>
        </div>
        <div v-if="t.steps.length || (running && ti === turns.length - 1)" class="agent-row">
          <AgentAvatar v-if="!compact" :name="agentName" :avatar="agentAvatar" :size="26" :radius="8" class="a-av" />
          <div class="agent-body">
            <template v-for="(s, si) in t.steps" :key="groupKey(s)">
              <MarkdownView v-if="s.kind === 'text'" :source="s.events[0].content" compact class="a-text" />
              <details v-else-if="s.kind === 'thinking'" class="thinking">
                <summary><Brain /> 思考过程</summary>
                <div class="thinking-body">{{ s.events[0].content }}</div>
              </details>
              <div v-else-if="s.kind === 'error'" class="a-error">
                <AlertTriangle /><span>{{ s.events[0].content }}</span>
              </div>
              <button v-else-if="s.kind === 'artifact'" class="a-artifact" @click="emit('open-artifact', artifact(s.events[0]).fileId)">
                <Package /> <span>产物 · {{ artifact(s.events[0]).name }}</span><span class="tag tag-blue">v{{ artifact(s.events[0]).version }}</span>
              </button>
              <div v-else class="tool-group">
                <button
                  class="tg-head"
                  @click="openGroups[groupKey(s)] = !isGroupOpen(s, si === t.steps.length - 1)"
                >
                  <ChevronRight class="tg-chev" :class="{ rot: isGroupOpen(s, si === t.steps.length - 1) }" />
                  <Wrench class="tg-ico" />
                  <span>调用了 {{ s.events.length }} 个工具</span>
                  <span class="tg-names ellipsis">{{ [...new Set(s.events.map((e) => e.name))].join(' · ') }}</span>
                  <span v-if="s.events.some((e) => e.status === 'error')" class="tag tag-red">{{ s.events.filter((e) => e.status === 'error').length }} 失败</span>
                  <span v-if="s.events.some((e) => e.status === 'running')" class="tag tag-blue">执行中</span>
                </button>
                <div v-if="isGroupOpen(s, si === t.steps.length - 1)" class="tg-list">
                  <ToolCallCard v-for="e in s.events" :key="e.id" :ev="e" />
                </div>
              </div>
            </template>
            <div v-if="running && ti === turns.length - 1" class="thinking-live">
              <span class="dots"><i /><i /><i /></span>
              <span>Agent 正在处理…</span>
              <button class="btn btn-sm stop" @click="emit('stop')"><Square /> 停止</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.msgs { flex: 1; overflow-y: auto; min-height: 0; }
.msgs-inner { max-width: 860px; margin: 0 auto; padding: 24px 24px 12px; }
.compact .msgs-inner { padding: 14px 14px 8px; }
.turn { margin-bottom: 22px; }
.user-row { display: flex; flex-direction: column; align-items: flex-end; margin-bottom: 14px; }
.bubble { max-width: 78%; background: var(--primary-soft); color: var(--text); padding: 9px 14px; border-radius: 14px 14px 4px 14px; border: 1px solid var(--primary-soft-2); }
.bubble-text { white-space: pre-wrap; word-break: break-word; line-height: 1.6; font-size: 13.5px; }
.u-time { font-size: 11px; color: var(--text-4); margin-top: 4px; }
.agent-row { display: flex; gap: 10px; align-items: flex-start; }
.a-av { margin-top: 2px; }
.agent-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.a-text { font-size: 13.5px; }
.a-error { display: flex; gap: 8px; align-items: flex-start; padding: 9px 12px; border-radius: 10px; background: var(--red-soft); color: var(--red-text); font-size: 12.5px; white-space: pre-wrap; word-break: break-word; }
.a-error svg { width: 15px; height: 15px; flex-shrink: 0; margin-top: 1px; }
.a-artifact { align-self: flex-start; display: inline-flex; align-items: center; gap: 8px; padding: 7px 12px; border-radius: 10px; border: 1px solid var(--border); background: var(--bg-elev); cursor: pointer; font-size: 12.5px; }
.a-artifact:hover { border-color: var(--primary); }
.a-artifact svg { width: 15px; height: 15px; color: var(--primary); }
.thinking { border-left: 2px solid var(--border-strong); padding-left: 10px; color: var(--text-3); font-size: 12.5px; }
.thinking summary { cursor: pointer; display: flex; align-items: center; gap: 6px; list-style: none; }
.thinking summary svg { width: 13px; height: 13px; }
.thinking-body { white-space: pre-wrap; margin-top: 6px; line-height: 1.6; }
.tool-group { display: flex; flex-direction: column; gap: 6px; }
.tg-head { align-self: flex-start; max-width: 100%; display: inline-flex; align-items: center; gap: 7px; padding: 5px 10px 5px 6px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg-muted); cursor: pointer; font-size: 12px; color: var(--text-2); }
.tg-head:hover { background: var(--bg-hover); }
.tg-chev { width: 13px; height: 13px; color: var(--text-4); transition: transform .15s; }
.tg-chev.rot { transform: rotate(90deg); }
.tg-ico { width: 13px; height: 13px; color: var(--text-3); }
.tg-names { color: var(--text-4); font-family: var(--mono); font-size: 11.5px; max-width: 360px; }
.tg-list { display: flex; flex-direction: column; gap: 6px; }
.thinking-live { display: flex; align-items: center; gap: 10px; color: var(--text-3); font-size: 12.5px; }
.thinking-live .stop { margin-left: 4px; }
.dots { display: inline-flex; gap: 3px; }
.dots i { width: 5px; height: 5px; border-radius: 50%; background: var(--primary); animation: pulse 1.2s infinite ease-in-out; }
.dots i:nth-child(2) { animation-delay: .2s; }
.dots i:nth-child(3) { animation-delay: .4s; }
</style>
