<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, MessagesSquare, Lock } from 'lucide-vue-next'
import { shareApi } from '@/api/session'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import MessageList from '@/components/chat/MessageList.vue'
import Composer from '@/components/chat/Composer.vue'
import { useSessionStream } from '@/components/chat/useSessionStream'
import { ago } from '@/utils/format'

const route = useRoute()
const agent = ref<any>(null)
const error = ref('')
const sid = ref<string | null>(null)
const { events, session } = useSessionStream(sid, shareApi.stream)
const running = computed(() => session.value?.status === 'running')

async function load() {
  try {
    agent.value = await shareApi.agent(route.params.id as string)
    if (agent.value.sessions?.length && !sid.value) sid.value = agent.value.sessions[0].id
  } catch (e: any) {
    error.value = e.message
  }
}
async function newSession() {
  const s = await shareApi.create(route.params.id as string)
  sid.value = s.id
  agent.value.sessions = [s, ...(agent.value.sessions || [])]
}
async function send(text: string) {
  if (!sid.value) await newSession()
  await shareApi.send(sid.value!, text)
}
async function stop() {
  if (sid.value) await shareApi.stop(sid.value)
}
onMounted(load)
</script>

<template>
  <div class="share">
    <div v-if="error" class="err">
      <div class="empty"><div class="empty-icon"><Lock /></div><div class="empty-title">无法访问</div><div class="small">{{ error }}</div></div>
    </div>
    <template v-else-if="agent">
      <aside class="s-side">
        <div class="s-agent">
          <AgentAvatar :name="agent.name" :avatar="agent.avatar" :size="40" :radius="12" />
          <div style="min-width: 0">
            <div class="bold ellipsis">{{ agent.name }}</div>
            <div class="tiny muted mono">{{ agent.identifier }} · v{{ agent.version }}</div>
          </div>
        </div>
        <p v-if="agent.description" class="s-desc">{{ agent.description }}</p>
        <button class="btn btn-block" @click="newSession"><Plus /> 新对话</button>
        <div class="s-list">
          <button v-for="s in agent.sessions" :key="s.id" class="s-item" :class="{ active: s.id === sid }" @click="sid = s.id">
            <MessagesSquare /><span class="ellipsis">{{ s.title }}</span><span class="faint tiny">{{ ago(s.updatedAt) }}</span>
          </button>
        </div>
      </aside>
      <main class="s-main">
        <MessageList :events="events" :running="running" :agent-name="agent.name" :agent-avatar="agent.avatar" @stop="stop">
          <template #empty>
            <div class="hello">
              <AgentAvatar :name="agent.name" :avatar="agent.avatar" :size="56" :radius="16" />
              <h2>你好，我是 {{ agent.name }}</h2>
              <p class="muted">{{ agent.description || '有什么可以帮你？' }}</p>
            </div>
          </template>
        </MessageList>
        <div class="s-input">
          <Composer :models="[]" :running="running" :allow-attach="false" :min-rows="1" placeholder="输入消息，Enter 发送" @send="send" @stop="stop" />
        </div>
      </main>
    </template>
  </div>
</template>

<style scoped>
.share { display: flex; height: 100vh; background: var(--bg); }
.err { flex: 1; display: flex; align-items: center; justify-content: center; }
.s-side { width: 260px; border-right: 1px solid var(--border); background: var(--bg-elev); padding: 16px 12px; display: flex; flex-direction: column; gap: 12px; }
.s-agent { display: flex; align-items: center; gap: 10px; }
.s-desc { font-size: 12.5px; color: var(--text-3); line-height: 1.6; }
.s-list { flex: 1; overflow: auto; }
.s-item { width: 100%; display: flex; align-items: center; gap: 8px; height: 32px; padding: 0 8px; border: none; border-radius: 8px; background: transparent; cursor: pointer; font-size: 12.5px; color: var(--text-2); text-align: left; }
.s-item svg { width: 14px; height: 14px; flex-shrink: 0; }
.s-item span:nth-child(2) { flex: 1; }
.s-item:hover { background: var(--bg-hover); }
.s-item.active { background: var(--bg-active); color: var(--text); font-weight: 600; }
.s-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.s-input { max-width: 908px; width: 100%; margin: 0 auto; padding: 8px 24px 20px; }
.hello { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 18vh 0 0; text-align: center; }
.hello h2 { font-size: 22px; }
</style>
