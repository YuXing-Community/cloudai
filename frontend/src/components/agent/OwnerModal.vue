<script setup lang="ts">
import { ref, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { agentApi, type AgentRow } from '@/api/agent'
import { toast } from '@/components/ui/toast'
import { debounce } from '@/utils/format'

const props = defineProps<{ agent: AgentRow | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'done'): void }>()
const q = ref('')
const users = ref<any[]>([])

const search = debounce(async () => {
  users.value = q.value.trim() ? await agentApi.searchUsers(q.value.trim()) : []
}, 250)
watch(q, search)
watch(() => props.agent, () => { q.value = ''; users.value = [] })

async function pick(u: any) {
  await agentApi.owner(props.agent!.id, u.id)
  toast.success(`负责人已变更为 ${u.displayName}`)
  emit('done')
  emit('close')
}
</script>

<template>
  <Modal :open="!!agent" title="变更负责人" subtitle="负责人用于 Agent 列表归属展示，不影响版本内容。" width="420px" @close="emit('close')">
    <div class="cur">
      <div class="tiny muted">当前负责人</div>
      <UserAvatar :name="agent?.owner?.displayName" :avatar="agent?.owner?.avatar" :size="28" show-name />
    </div>
    <div class="search">
      <Search /><input v-model="q" placeholder="搜索用户名、姓名或邮箱" autofocus />
    </div>
    <div class="list">
      <button v-for="u in users" :key="u.id" class="u" @click="pick(u)">
        <UserAvatar :name="u.displayName" :avatar="u.avatar" :size="26" show-name :sub="`@${u.username} · ${u.email}`" />
      </button>
    </div>
  </Modal>
</template>

<style scoped>
.cur { padding: 10px 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-muted); display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.search { display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 12px; border: 1px solid var(--border); border-radius: 12px; }
.search:focus-within { border-color: var(--primary); box-shadow: var(--ring); }
.search svg { width: 15px; height: 15px; color: var(--text-4); }
.search input { flex: 1; border: none; outline: none; background: transparent; }
.list { margin-top: 8px; max-height: 260px; overflow: auto; }
.u { width: 100%; display: flex; padding: 8px; border-radius: 10px; border: none; background: transparent; cursor: pointer; text-align: left; }
.u:hover { background: var(--bg-hover); }
</style>
