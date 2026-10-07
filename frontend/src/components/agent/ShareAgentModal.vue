<script setup lang="ts">
import { ref, watch } from 'vue'
import { Share2, ExternalLink, Copy, Users, Search, X, ArrowLeft } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { agentApi, type AgentRow } from '@/api/agent'
import { toast } from '@/components/ui/toast'
import { copyText, debounce } from '@/utils/format'

const props = defineProps<{ agent: AgentRow | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const url = ref('')
const grantMode = ref(false)
const grants = ref<any[]>([])
const q = ref('')
const users = ref<any[]>([])

watch(() => props.agent, async (a) => {
  grantMode.value = false
  url.value = ''
  if (a) url.value = location.origin + '/share/agent/' + a.id
}, { immediate: true })

async function loadGrants() {
  grants.value = await agentApi.grants(props.agent!.id)
}
async function openGrant() {
  grantMode.value = true
  await loadGrants()
}
const search = debounce(async () => {
  users.value = q.value.trim() ? await agentApi.searchUsers(q.value.trim()) : []
}, 250)
watch(q, search)

async function add(u: any) {
  await agentApi.grant(props.agent!.id, [u.id])
  q.value = ''
  users.value = []
  toast.success(`已授权 ${u.displayName}`)
  loadGrants()
}
async function revoke(g: any) {
  await agentApi.revoke(props.agent!.id, g.grantId)
  loadGrants()
}
function copy() {
  copyText(url.value)
  toast.success('对话链接已复制')
}
</script>

<template>
  <Modal :open="!!agent" width="440px" @close="emit('close')">
    <template #header>
      <div style="flex: 1">
        <h3 class="title"><button v-if="grantMode" class="icon-btn sm" @click="grantMode = false"><ArrowLeft /></button><Share2 v-else /> {{ grantMode ? '用户授权' : '分享智能体' }}</h3>
        <p class="sub" v-if="!grantMode">将 <b>{{ agent?.name }}</b> 的对话链接分享给其他人。</p>
        <p class="sub" v-else>空间成员可直接访问；非成员需要在此授权后才能通过链接对话。</p>
      </div>
    </template>
    <template v-if="!grantMode">
      <div class="label" style="margin-bottom: 6px">对话链接</div>
      <div class="row">
        <input class="input mono" :value="url" readonly @focus="($event.target as HTMLInputElement).select()" />
        <a class="icon-btn bordered" :href="url" target="_blank" title="打开"><ExternalLink /></a>
        <button class="icon-btn bordered" title="复制" @click="copy"><Copy /></button>
      </div>
      <div style="display: flex; justify-content: flex-end; margin-top: 14px">
        <button class="btn" @click="openGrant"><Users /> 用户授权</button>
      </div>
    </template>
    <template v-else>
      <div class="search"><Search /><input v-model="q" placeholder="搜索用户名、姓名或邮箱，添加授权" /></div>
      <div v-if="users.length" class="list">
        <button v-for="u in users" :key="u.id" class="u" @click="add(u)">
          <UserAvatar :name="u.displayName" :size="24" show-name :sub="u.email" />
        </button>
      </div>
      <div class="label" style="margin: 12px 0 6px">已授权 {{ grants.length }}</div>
      <div v-for="g in grants" :key="g.grantId" class="g">
        <UserAvatar :name="g.displayName" :avatar="g.avatar" :size="24" show-name :sub="g.email" />
        <button class="icon-btn sm danger" @click="revoke(g)"><X /></button>
      </div>
      <div v-if="!grants.length" class="empty-box">暂无单独授权的用户</div>
    </template>
  </Modal>
</template>

<style scoped>
.title { font-size: 16px; font-weight: 650; display: flex; align-items: center; gap: 8px; }
.title svg { width: 16px; height: 16px; }
.sub { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.search { display: flex; align-items: center; gap: 8px; height: 36px; padding: 0 12px; border: 1px solid var(--border); border-radius: 10px; }
.search svg { width: 15px; height: 15px; color: var(--text-4); }
.search input { flex: 1; border: none; outline: none; background: transparent; }
.list { border: 1px solid var(--border); border-radius: 10px; margin-top: 6px; max-height: 200px; overflow: auto; padding: 4px; }
.u { width: 100%; display: flex; padding: 6px 8px; border: none; background: transparent; border-radius: 8px; cursor: pointer; }
.u:hover { background: var(--bg-hover); }
.g { display: flex; align-items: center; justify-content: space-between; padding: 6px 4px; border-bottom: 1px solid var(--divider); }
</style>
