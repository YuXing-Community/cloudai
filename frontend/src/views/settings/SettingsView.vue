<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Info, User, Cpu, Database, KeyRound, FileInput } from 'lucide-vue-next'
import { useAuth } from '@/stores/auth'
import BasicSettings from '@/components/settings/BasicSettings.vue'
import MembersSettings from '@/components/settings/MembersSettings.vue'
import ModelsSettings from '@/components/settings/ModelsSettings.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const tab = computed(() => (route.params.tab as string) || 'basic')

const nav = [
  { group: '空间', items: [{ key: 'basic', label: '基础信息', icon: Info }, { key: 'members', label: '成员管理', icon: User }] },
  { group: '能力', items: [{ key: 'models', label: '模型管理', icon: Cpu }, { key: 'memory-provider', label: '记忆 Provider', icon: Database, soon: true }] },
  { group: '访问与数据', items: [{ key: 'api-keys', label: 'API Keys', icon: KeyRound, soon: true }, { key: 'migrate', label: '迁移导入', icon: FileInput, soon: true }] },
]
</script>

<template>
  <div class="settings">
    <aside class="s-nav">
      <div class="row" style="gap: 8px; padding: 0 6px 14px">
        <h1 class="page-title" style="font-size: 18px">空间配置</h1>
        <span class="tag tag-round">{{ auth.space?.type === 'personal' ? '个人空间' : '团队空间' }}</span>
      </div>
      <div v-for="g in nav" :key="g.group" class="s-group">
        <div class="s-label">{{ g.group }}</div>
        <button
          v-for="it in g.items"
          :key="it.key"
          class="s-item"
          :class="{ active: tab === it.key, soon: it.soon }"
          :disabled="it.soon"
          :title="it.soon ? '即将上线' : ''"
          @click="router.push(`/settings/${it.key}`)"
        >
          <component :is="it.icon" /><span>{{ it.label }}</span><span v-if="it.soon" class="soon-tag">即将上线</span>
        </button>
      </div>
    </aside>
    <main class="s-main">
      <div class="s-inner">
        <BasicSettings v-if="tab === 'basic'" />
        <MembersSettings v-else-if="tab === 'members'" />
        <ModelsSettings v-else-if="tab === 'models'" />
        <div v-else class="empty">该功能即将上线</div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.settings { display: flex; height: 100%; min-height: 0; }
.s-nav { width: 220px; flex-shrink: 0; padding: 22px 12px; border-right: 1px solid var(--border); overflow-y: auto; }
.s-group { margin-bottom: 14px; }
.s-label { font-size: 11.5px; color: var(--text-3); padding: 4px 8px; }
.s-item { width: 100%; display: flex; align-items: center; gap: 9px; height: 32px; padding: 0 10px; border: none; border-radius: 8px; background: transparent; cursor: pointer; font-size: 13px; color: var(--text-2); position: relative; }
.s-item svg { width: 15px; height: 15px; color: var(--text-3); }
.s-item:hover:not(:disabled) { background: var(--bg-hover); }
.s-item.active { background: var(--bg-active); color: var(--primary-text); font-weight: 600; }
.s-item.active::before { content: ''; position: absolute; left: 0; top: 7px; bottom: 7px; width: 2.5px; border-radius: 2px; background: var(--primary); }
.s-item.active svg { color: var(--primary); }
.s-item.soon { opacity: .5; cursor: not-allowed; }
.soon-tag { margin-left: auto; font-size: 10px; color: var(--text-4); }
.s-main { flex: 1; min-width: 0; overflow-y: auto; }
.s-inner { max-width: 960px; margin: 0 auto; padding: 28px 32px 60px; }
</style>
