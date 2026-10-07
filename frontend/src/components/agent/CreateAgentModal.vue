<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bot, Sparkles, Upload, Wand2, Loader2 } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import AgentAvatar from '@/components/ui/AgentAvatar.vue'
import { PRESET_AVATARS } from '@/components/ui/avatars'
import { agentApi } from '@/api/agent'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ open: boolean; groups: string[] }>()
const emit = defineEmits<{ (e: 'close'): void }>()
const router = useRouter()

const mode = ref<'manual' | 'ai'>('manual')
const form = ref({ name: '', identifier: '', description: '', avatar: 'preset:0', groupName: '', prompt: '' })
const requirement = ref('')
const drafting = ref(false)
const saving = ref(false)
const fileEl = ref<HTMLInputElement | null>(null)
const newGroup = ref(false)

watch(() => props.open, (v) => {
  if (v) {
    mode.value = 'manual'
    form.value = { name: '', identifier: '', description: '', avatar: 'preset:0', groupName: '', prompt: '' }
    requirement.value = ''
    newGroup.value = false
  }
})

const identOk = computed(() => /^[a-zA-Z][a-zA-Z0-9_-]*$/.test(form.value.identifier))

function onAvatarFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  if (f.size > 512 * 1024) return toast.warning('头像图片请小于 512KB')
  const r = new FileReader()
  r.onload = () => { form.value.avatar = String(r.result) }
  r.readAsDataURL(f)
}

async function draft() {
  if (!requirement.value.trim()) return toast.warning('请先描述你想要的 Agent')
  drafting.value = true
  try {
    const d = await agentApi.aiDraft(requirement.value)
    form.value.name = d.name || form.value.name
    form.value.identifier = (d.identifier || '').replace(/[^a-zA-Z0-9_-]/g, '-')
    form.value.description = d.description || ''
    form.value.prompt = d.prompt || ''
    toast.success('已生成草稿，可继续修改后创建')
  } finally {
    drafting.value = false
  }
}

async function submit() {
  const f = form.value
  if (!f.name.trim()) return toast.warning('请填写显示名称')
  if (!identOk.value) return toast.warning('英文标识须以字母开头，只能包含字母、数字、下划线和短横线')
  saving.value = true
  try {
    const r = await agentApi.create({ ...f, prompt: mode.value === 'ai' ? f.prompt : undefined })
    toast.success('Agent 已创建，进入配置工作台')
    emit('close')
    router.push(`/agents/${r.id}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="创建 Agent" subtitle="填写基础信息快速创建 Agent，创建后进入配置工作台进一步调试。" width="560px" @close="emit('close')">
    <div class="label" style="margin-bottom: 6px">Agent 类型</div>
    <div class="type-card active">
      <div class="tc-ico"><Bot /></div>
      <div>
        <div class="bold">Agent</div>
        <div class="small muted">自定义模型、提示词、工具与环境。支持由 AI 协助快速生成草稿。</div>
      </div>
    </div>

    <div class="segmented block" style="margin: 12px 0 14px">
      <button :class="{ active: mode === 'manual' }" @click="mode = 'manual'">手动创建</button>
      <button :class="{ active: mode === 'ai' }" @click="mode = 'ai'"><Sparkles /> AI 创建</button>
    </div>

    <div v-if="mode === 'ai'" class="ai-box">
      <textarea v-model="requirement" class="textarea" placeholder="用一句话描述你想要的 Agent，例如：负责 K8s 集群告警的问题诊断，能查询 Pod 日志并给出处理建议" />
      <button class="btn btn-soft" :disabled="drafting" @click="draft">
        <Loader2 v-if="drafting" class="spin" /><Wand2 v-else /> {{ drafting ? '生成中…' : '生成草稿' }}
      </button>
    </div>

    <div class="avatar-box">
      <AgentAvatar :name="form.name || 'A'" :avatar="form.avatar" :size="48" :radius="12" />
      <div style="flex: 1">
        <div class="bold">头像</div>
        <div class="small muted">选择默认头像，或上传自定义图片。</div>
        <div class="presets">
          <button v-for="(p, i) in PRESET_AVATARS" :key="i" class="preset" :class="{ on: form.avatar === `preset:${i}` }" @click="form.avatar = `preset:${i}`">
            <AgentAvatar name="" :avatar="`preset:${i}`" :size="22" :radius="6" />
          </button>
          <button class="preset" :class="{ on: !form.avatar.startsWith('preset') && !form.avatar.startsWith('data') }" @click="form.avatar = ''">
            <AgentAvatar :name="form.name || 'A'" avatar="" :size="22" :radius="6" />
          </button>
        </div>
      </div>
      <button class="icon-btn bordered" title="上传图片" @click="fileEl?.click()"><Upload /></button>
      <input ref="fileEl" type="file" accept="image/*" hidden @change="onAvatarFile" />
    </div>

    <div class="field">
      <label>显示名称 <span class="req">*</span><span class="char-count">{{ form.name.length }}/60</span></label>
      <input v-model="form.name" class="input" maxlength="60" placeholder="k8s问题诊断" />
    </div>
    <div class="field">
      <label>英文标识 <span class="req">*</span><span class="char-count">{{ form.identifier.length }}/60</span></label>
      <input v-model="form.identifier" class="input mono" maxlength="60" placeholder="k8s-diagnosis（须以字母开头）" />
      <span v-if="form.identifier && !identOk" class="hint danger-text">须以字母开头，只能包含字母、数字、下划线和短横线</span>
    </div>
    <div class="field">
      <label>描述</label>
      <textarea v-model="form.description" class="textarea" placeholder="说明这个 Agent 负责处理的问题范围" />
    </div>
    <div class="field">
      <label>分组 <button class="link-btn small" style="margin-left: auto" @click="newGroup = !newGroup">{{ newGroup ? '选择已有分组' : '+ 新分组' }}</button></label>
      <input v-if="newGroup || !groups.length" v-model="form.groupName" class="input" placeholder="输入分组名称（可选）" />
      <select v-else v-model="form.groupName" class="input select-native">
        <option value="">未分组</option>
        <option v-for="g in groups" :key="g" :value="g">{{ g }}</option>
      </select>
    </div>

    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="submit">创建并配置</button>
    </template>
  </Modal>
</template>

<style scoped>
.type-card { display: flex; gap: 12px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; width: 50%; }
.type-card.active { border-color: var(--primary); background: var(--primary-soft); }
.tc-ico { width: 30px; height: 30px; border-radius: 8px; background: var(--primary); color: #fff; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.tc-ico svg { width: 17px; height: 17px; }
.ai-box { display: flex; flex-direction: column; gap: 8px; align-items: flex-end; margin-bottom: 14px; }
.avatar-box { display: flex; gap: 14px; align-items: flex-start; padding: 14px; border: 1px solid var(--border); border-radius: 12px; margin-bottom: 14px; background: var(--bg-muted); }
.presets { display: flex; gap: 6px; margin-top: 8px; }
.preset { padding: 2px; border-radius: 8px; border: 2px solid transparent; background: transparent; cursor: pointer; display: flex; }
.preset.on { border-color: var(--primary); }
.select-native { appearance: auto; }
</style>
