<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { KeyRound, Layers, Pencil, Plus, Trash2, Eye, EyeOff } from 'lucide-vue-next'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'
import { refApi } from '@/api/agent'
import { toast } from '@/components/ui/toast'

const props = defineProps<{ open: boolean; used: { credentialId: number; field?: string; scope?: string }[] }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'add', v: any): void }>()

const tab = ref<'pick' | 'new'>('pick')
const creds = ref<any[]>([])
const credId = ref<any>(null)
const scope = ref<'single' | 'all'>('single')
const field = ref('')
const envName = ref('')
const nc = ref({ name: '', fields: [{ name: '', envName: '', value: '', show: false }] })
const saving = ref(false)

watch(() => props.open, async (v) => {
  if (!v) return
  tab.value = 'pick'
  creds.value = await refApi.credentials()
  credId.value = creds.value[0]?.id ?? null
  nc.value = { name: '', fields: [{ name: '', envName: '', value: '', show: false }] }
})
const cred = computed(() => creds.value.find((c) => c.id === credId.value))
const fieldOptions = computed(() => (cred.value?.fields || []).map((f: any) => ({
  label: f.name, value: f.name,
  disabled: props.used.some((u) => u.credentialId === credId.value && (u.scope === 'all' || u.field === f.name)),
})))
watch([credId, cred], () => {
  const first = fieldOptions.value.find((o: any) => !o.disabled)
  field.value = first?.value || ''
})
watch(field, (f) => {
  envName.value = cred.value?.fields?.find((x: any) => x.name === f)?.envName || f.toUpperCase()
})

function addPicked() {
  if (!cred.value) return toast.warning('请选择凭证')
  if (props.used.some((u) => u.credentialId === credId.value && (u.scope === 'all' || scope.value === 'all'))) return toast.warning('已挂载的凭证或字段不可重复选择')
  if (scope.value === 'all') emit('add', { type: 'secret', credentialId: credId.value, scope: 'all', name: cred.value.name, label: cred.value.name })
  else {
    if (!field.value || !envName.value) return toast.warning('请选择密钥字段并填写环境变量名')
    emit('add', { type: 'secret', credentialId: credId.value, field: field.value, name: envName.value, label: cred.value.name })
  }
  emit('close')
}

async function createAndAdd() {
  const fs = nc.value.fields.filter((f) => f.name)
  if (!nc.value.name.trim()) return toast.warning('请填写凭证名称')
  if (!fs.length || fs.some((f) => !f.value)) return toast.warning('请完整填写密钥字段')
  saving.value = true
  try {
    const c = await refApi.createCredential({ name: nc.value.name, type: 'generic', fields: fs.map((f) => ({ name: f.name, envName: f.envName || f.name.toUpperCase(), value: f.value })) })
    emit('add', { type: 'secret', credentialId: c.id, scope: 'all', name: nc.value.name, label: nc.value.name })
    toast.success('凭证已创建并挂载')
    emit('close')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Modal :open="open" title="添加密钥环境变量" width="600px" @close="emit('close')">
    <div class="segmented block" style="margin-bottom: 16px">
      <button :class="{ active: tab === 'pick' }" @click="tab = 'pick'">选择凭证</button>
      <button :class="{ active: tab === 'new' }" @click="tab = 'new'">新建凭证</button>
    </div>
    <template v-if="tab === 'pick'">
      <div class="field">
        <label>凭证</label>
        <div class="row">
          <Select v-model="credId" :options="creds.map((c) => ({ label: c.name, value: c.id, sub: c.type }))" placeholder="选择凭证" style="flex: 1" searchable />
          <RouterLink to="/plugins/credentials" class="icon-btn bordered" target="_blank" title="管理凭据"><Pencil /></RouterLink>
        </div>
        <span class="hint">已挂载的凭证或字段不可重复选择。</span>
      </div>
      <hr class="divider" />
      <div class="field">
        <label>使用范围</label>
        <span class="hint" style="margin-top: -2px">选择一个字段作为环境变量，或一次添加凭证中的全部字段。</span>
        <div class="scope">
          <button :class="{ on: scope === 'single' }" @click="scope = 'single'"><KeyRound /> 单个字段</button>
          <button :class="{ on: scope === 'all' }" @click="scope = 'all'"><Layers /> 全部字段</button>
        </div>
      </div>
      <template v-if="scope === 'single'">
        <div class="field"><label>密钥字段</label><Select v-model="field" :options="fieldOptions" placeholder="选择字段" /></div>
        <div class="field"><label>环境变量名</label><input v-model="envName" class="input mono" /><span class="hint">Agent 在运行时通过这个名称读取密钥。</span></div>
      </template>
      <div v-else class="all-list">
        <div v-for="f in cred?.fields || []" :key="f.name" class="row small"><KeyRound style="width: 13px" /><span class="mono">{{ f.envName }}</span><span class="faint">← {{ f.name }}</span></div>
      </div>
    </template>
    <template v-else>
      <div class="notice notice-warn" style="margin-bottom: 16px"><KeyRound /> 密钥值保存后安全加密存储，页面不会再次显示原值。</div>
      <div class="field"><label>凭证名称</label><input v-model="nc.name" class="input" placeholder="例: OpenAI 生产环境" /></div>
      <div class="row" style="justify-content: space-between; margin-bottom: 8px">
        <span class="label">密钥字段</span>
        <button class="btn" @click="nc.fields.push({ name: '', envName: '', value: '', show: false })"><Plus /> 添加字段</button>
      </div>
      <div class="ftable">
        <div class="fhead"><span>字段名称</span><span>默认环境变量名</span><span>密钥值</span><span>操作</span></div>
        <div v-for="(f, i) in nc.fields" :key="i" class="frow">
          <input v-model="f.name" class="input input-sm" placeholder="例: api_key" />
          <input v-model="f.envName" class="input input-sm mono" :placeholder="f.name ? f.name.toUpperCase() : '例: OPENAI_API_KEY'" />
          <div class="pw">
            <input v-model="f.value" class="input input-sm" :type="f.show ? 'text' : 'password'" placeholder="请输入密钥值..." />
            <button class="icon-btn sm" @click="f.show = !f.show"><EyeOff v-if="f.show" /><Eye v-else /></button>
          </div>
          <button class="icon-btn sm danger" :disabled="nc.fields.length === 1" @click="nc.fields.splice(i, 1)"><Trash2 /></button>
        </div>
      </div>
    </template>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button v-if="tab === 'pick'" class="btn btn-primary" @click="addPicked">添加</button>
      <button v-else class="btn btn-primary" :disabled="saving" @click="createAndAdd">创建并添加</button>
    </template>
  </Modal>
</template>

<style scoped>
.scope { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.scope button { height: 44px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elev); cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; font-weight: 600; font-size: 13.5px; }
.scope button svg { width: 17px; height: 17px; }
.scope button.on { border-color: var(--primary-soft-2); background: var(--primary-soft); color: var(--primary-text); }
.all-list { display: flex; flex-direction: column; gap: 6px; padding: 10px 12px; background: var(--bg-muted); border-radius: 10px; }
.ftable { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.fhead, .frow { display: grid; grid-template-columns: 1fr 1fr 1fr 40px; gap: 8px; padding: 8px 12px; align-items: center; }
.fhead { background: var(--bg-muted); font-size: 12px; color: var(--text-3); font-weight: 600; }
.pw { position: relative; display: flex; align-items: center; }
.pw .icon-btn { position: absolute; right: 2px; }
</style>
