<script setup lang="ts">
import { computed, ref } from 'vue'
import { MessageSquare, RotateCcw, Plus, Sparkles } from 'lucide-vue-next'
import { sessionApi } from '@/api/session'
import MessageList from './MessageList.vue'
import Composer from './Composer.vue'
import { useSessionStream } from './useSessionStream'
import { toast } from '@/components/ui/toast'

/** Agent 编辑页右侧的调试面板：独立临时 Session（source=debug），用已保存的最新版本对话 */
const props = defineProps<{ agentId: string; agentName: string; agentAvatar?: string; dirty: boolean; models: any[]; modelRef?: string }>()

const sid = ref<string | null>(null)
const { events, session } = useSessionStream(sid, sessionApi.stream)
const running = computed(() => session.value?.status === 'running')
const status = computed(() => {
  if (!sid.value) return '未开始'
  return { running: '运行中', completed: '已完成', failed: '失败', stopped: '已停止', idle: '就绪' }[session.value?.status || 'idle']
})
const model = ref(props.modelRef || '')

async function send(text: string, fileIds: string[]) {
  if (props.dirty) toast.warning('配置有未保存的修改，调试使用的是已保存的版本')
  if (!sid.value) {
    const s = await sessionApi.create({ agentId: props.agentId, source: 'debug', modelRef: model.value || undefined, title: `调试 · ${props.agentName}` })
    sid.value = s.id
  }
  await sessionApi.send(sid.value, text, fileIds, model.value || undefined)
}
function reset() {
  sid.value = null
}
async function stop() {
  if (sid.value) await sessionApi.stop(sid.value)
}
</script>

<template>
  <div class="dbg">
    <div class="dbg-head">
      <MessageSquare class="dh-ico" />
      <div>
        <div class="row" style="gap: 6px"><span class="bold">Agent 调试</span><span class="tag tag-round" :class="running ? 'tag-blue' : ''">{{ status }}</span></div>
        <div class="tiny muted">独立临时 Session</div>
      </div>
      <div class="spacer" />
      <button class="icon-btn" title="重置会话" @click="reset"><RotateCcw /></button>
      <button class="icon-btn" title="新会话" @click="reset"><Plus /></button>
    </div>
    <MessageList :events="events" :running="running" :agent-name="agentName" :agent-avatar="agentAvatar" compact @stop="stop">
      <template #empty>
        <div class="dbg-empty">
          <div class="de-ico"><Sparkles /></div>
          <div class="bold">开始调试</div>
          <div class="small muted">保存配置后，可在这里用最新 Agent 发起对话。</div>
        </div>
      </template>
    </MessageList>
    <div class="dbg-input">
      <Composer v-model:model-ref="model" :models="models" :running="running" :min-rows="1" :allow-attach="!!sid" :session-id="sid" placeholder="发送消息..." @send="send" @stop="stop" />
    </div>
  </div>
</template>

<style scoped>
.dbg { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.dbg-head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); }
.dh-ico { width: 16px; height: 16px; color: var(--text-3); }
.dbg-empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding-top: 30vh; text-align: center; }
.de-ico { width: 38px; height: 38px; border-radius: 10px; border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; color: var(--text-3); margin-bottom: 4px; }
.de-ico svg { width: 18px; height: 18px; }
.dbg-input { padding: 8px 10px 12px; }
.dbg-input :deep(.composer) { border-radius: 12px; padding: 8px; box-shadow: none; }
</style>
