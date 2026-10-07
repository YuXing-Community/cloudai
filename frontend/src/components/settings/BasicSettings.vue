<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Trash2 } from 'lucide-vue-next'
import { settingsApi } from '@/api/settings'
import { useAuth } from '@/stores/auth'
import Switch from '@/components/ui/Switch.vue'
import { toast } from '@/components/ui/toast'

const auth = useAuth()
const router = useRouter()
const form = ref({ name: '', description: '' })
const space = ref<any>(null)
const confirmName = ref('')
const saving = ref(false)
const canEdit = computed(() => auth.can('space:edit'))

async function load() {
  const r = await settingsApi.space()
  space.value = r.space
  form.value = { name: r.space.name, description: r.space.description || '' }
}
onMounted(load)

async function save() {
  if (!form.value.name.trim()) return toast.warning('空间名称不能为空')
  saving.value = true
  try {
    await settingsApi.updateSpace(form.value)
    space.value.name = form.value.name
    space.value.description = form.value.description
    await auth.loadSpaces()
    toast.success('已保存')
  } finally {
    saving.value = false
  }
}
async function toggleEnv(v: boolean) {
  try {
    await settingsApi.toggleCustomEnv(v)
    space.value.customEnvEnabled = v
    toast.success(v ? '已允许自定义运行环境' : '已关闭自定义运行环境')
  } catch {
    space.value.customEnvEnabled = !v
  }
}
async function remove() {
  await settingsApi.deleteSpace(confirmName.value)
  toast.success('空间已删除')
  await auth.leaveCurrentSpace()
  router.replace('/sessions')
}
</script>

<template>
  <div v-if="space" class="sec">
    <h2 class="h">基础信息</h2>
    <p class="d">更新空间名称和描述，空间 ID 创建后不可修改。每个空间相互隔离，空间内的 Agent 不能调用其他空间的 Agent。</p>
    <div class="form-row">
      <div class="field"><label>空间名称</label><input v-model="form.name" class="input" :disabled="!canEdit" maxlength="64" /></div>
      <div class="field">
        <label>空间 ID</label>
        <input class="input mono" :value="space.id" disabled />
        <span class="hint">唯一标识，创建后不可修改</span>
      </div>
    </div>
    <div class="field"><label>描述</label><textarea v-model="form.description" class="textarea" placeholder="请输入空间描述（可选）" :disabled="!canEdit" /></div>
    <button v-if="canEdit" class="btn btn-primary" :disabled="saving" @click="save">保存</button>

    <hr class="divider" style="margin: 26px 0" />
    <div class="row" style="justify-content: space-between; align-items: flex-start">
      <div>
        <h2 class="h">运行环境</h2>
        <p class="d">开启后，允许在该空间创建自定义运行环境。</p>
        <p class="tiny muted">仅系统管理员可修改</p>
      </div>
      <Switch :model-value="!!space.customEnvEnabled" :disabled="!auth.isSystemAdmin" @change="toggleEnv" />
    </div>

    <template v-if="auth.can('space:delete')">
      <hr class="divider" style="margin: 26px 0" />
      <h2 class="h">删除空间</h2>
      <p class="d">永久移除该空间及其成员、配置和资源。此操作无法撤销。</p>
      <div class="field" style="max-width: 360px">
        <label>请输入空间名称 <b>{{ space.name }}</b> 以确认删除</label>
        <input v-model="confirmName" class="input" :placeholder="space.name" />
      </div>
      <button class="btn btn-danger" :disabled="confirmName !== space.name" @click="remove"><Trash2 /> 永久删除此空间</button>
    </template>
  </div>
</template>

<style scoped>
.h { font-size: 15px; font-weight: 650; }
.d { color: var(--text-3); font-size: 12.5px; margin: 4px 0 16px; }
</style>
