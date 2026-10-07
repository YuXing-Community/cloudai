<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import { refApi } from '@/api/agent'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'add', v: any): void }>()

const TYPES = [
  { key: 'gitlab', label: 'GitLab CLI', credType: 'gitlab', fields: [{ name: 'token', envName: 'GITLAB_TOKEN', label: 'Access Token' }, { name: 'host', envName: 'GITLAB_HOST', label: 'GitLab 地址' }] },
  { key: 'lark', label: 'lark-cli (飞书)', credType: 'feishu', fields: [{ name: 'app_id', envName: 'LARK_APP_ID', label: 'App ID', ph: 'cli_xxx' }, { name: 'app_secret', envName: 'LARK_APP_SECRET', label: 'App Secret' }] },
  { key: 'cloudai', label: 'cloudai-cli', credType: '', fields: [] },
]
const type = ref('gitlab')
const identity = ref('app')
const credId = ref<any>('__new__')
const creds = ref<any[]>([])
const values = ref<Record<string, string>>({})
const credName = ref('')
const saving = ref(false)

const t = computed(() => TYPES.find((x) => x.key === type.value)!)
const credOptions = computed(() => [{ label: '新建凭据', value: '__new__' }, ...creds.value.map((c) => ({ label: c.name, value: c.id }))])

async function loadCreds() {
  creds.value = t.value.credType ? await refApi.credentials(t.value.credType) : []
  credId.value = creds.value[0]?.id ?? '__new__'
  values.value = {}
  credName.value = ''
}
watch(() => props.open, (v) => v && loadCreds())
watch(type, loadCreds)

async function add() {
  if (type.value === 'cloudai') {
    emit('add', { type: 'cloudai', label: '当前平台访问能力' })
    return emit('close')
  }
  saving.value = true
  try {
    let id = credId.value
    let label = creds.value.find((c) => c.id === id)?.name
    if (id === '__new__') {
      for (const f of t.value.fields) if (!values.value[f.name]) return toast.warning(`请填写 ${f.label}`)
      label = credName.value || `${t.value.label} 凭据`
      const c = await refApi.createCredential({ name: label, type: t.value.credType, fields: t.value.fields.map((f) => ({ name: f.name, envName: f.envName, value: values.value[f.name] })) })
      id = c.id
    }
    emit('add', { type: type.value, credentialId: id, identity: type.value === 'lark' ? identity.value : undefined,
      label: type.value === 'lark' ? `${identity.value === 'app' ? '应用身份' : '个人身份'} · ${label}` : label })
    emit('close')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="添加 CLI 插件" subtitle="选择 CLI 类型。GitLab 和飞书可选择已有凭据，cloudai-cli 使用当前平台访问能力。" width="560px" @close="emit('close')">
    <div class="label" style="margin-bottom: 8px">插件类型</div>
    <div class="types">
      <button v-for="x in TYPES" :key="x.key" class="type" :class="{ on: type === x.key }" @click="type = x.key">
        <svg v-if="x.key === 'gitlab'" viewBox="0 0 24 24" class="logo"><path fill="#E24329" d="m12 21.4 3.7-11.3H8.3z" /><path fill="#FC6D26" d="M12 21.4 8.3 10.1H3.1z" /><path fill="#FCA326" d="M3.1 10.1 2 13.5a.8.8 0 0 0 .3.9l9.7 7z" /><path fill="#E24329" d="M3.1 10.1h5.2L6.1 3.2a.4.4 0 0 0-.7 0z" /><path fill="#FC6D26" d="m12 21.4 3.7-11.3h5.2z" /><path fill="#FCA326" d="m20.9 10.1 1.1 3.4a.8.8 0 0 1-.3.9L12 21.4z" /><path fill="#E24329" d="M20.9 10.1h-5.2l2.2-6.9a.4.4 0 0 1 .7 0z" /></svg>
        <svg v-else-if="x.key === 'lark'" viewBox="0 0 24 24" class="logo"><path fill="#00D6B9" d="M4 8.5c3 1.2 6.4 3.9 8.6 6.7L9.3 18C7.2 15.4 5.4 12.2 4 8.5z" /><path fill="#133C9A" d="M9.3 18c4.6 1 9.4-.8 11.7-5-2.4.9-5 .7-7.4-.4L9.3 18z" /><path fill="#3370FF" d="M12.6 15.2c2.6-1.8 5.6-4.3 8.4-2.2-.5-4-4.5-6.4-8.6-5.6 1.2 2.7.8 5.4.2 7.8z" /></svg>
        <svg v-else viewBox="0 0 24 24" class="logo"><rect x="3" y="3" width="18" height="18" rx="5" fill="#e0f2fe" /><path d="M8 15.5h8a3 3 0 0 0 .4-6A4 4 0 0 0 8.6 10 2.6 2.6 0 0 0 8 15.5Z" fill="none" stroke="#2563eb" stroke-width="1.6" /></svg>
        <span>{{ x.label }}</span>
      </button>
    </div>
    <template v-if="type === 'lark'">
      <div class="field" style="margin-top: 14px">
        <label>运行身份</label>
        <Select v-model="identity" :options="[{ label: '应用身份', value: 'app' }, { label: '个人身份（需授权）', value: 'user' }]" />
      </div>
    </template>
    <template v-if="type !== 'cloudai'">
      <div class="field" :style="type !== 'lark' ? 'margin-top: 14px' : ''">
        <label>已有凭据</label>
        <Select v-model="credId" :options="credOptions" />
      </div>
      <template v-if="credId === '__new__'">
        <div class="field"><label>凭据名称</label><input v-model="credName" class="input" :placeholder="`${t.label} 凭据`" /></div>
        <div v-for="f in t.fields" :key="f.name" class="field">
          <label>{{ f.label }} <span class="req">*</span></label>
          <input v-model="values[f.name]" class="input" :type="f.name.includes('secret') || f.name === 'token' ? 'password' : 'text'" :placeholder="(f as any).ph || ''" />
        </div>
      </template>
    </template>
    <p v-else class="notice notice-info" style="margin-top: 14px">cloudai-cli 使用当前平台访问能力，无需额外凭据。</p>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" :disabled="saving" @click="add">添加</button>
    </template>
  </Modal>
</template>

<style scoped>
.types { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.type { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px 8px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elev); cursor: pointer; font-weight: 600; font-size: 13.5px; }
.type:hover { border-color: var(--border-strong); }
.type.on { border-color: var(--primary); background: var(--primary-soft); box-shadow: 0 0 0 1px var(--primary); }
.logo { width: 30px; height: 30px; }
</style>
