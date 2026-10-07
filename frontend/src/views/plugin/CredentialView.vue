<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { KeyRound, Plus, Pencil, Trash2, Eye, EyeOff, ArrowRight } from 'lucide-vue-next'
import { credentialApi, CRED_TYPES } from '@/api/plugin'
import { useAuth } from '@/stores/auth'
import SearchInput from '@/components/ui/SearchInput.vue'
import Select from '@/components/ui/Select.vue'
import Modal from '@/components/ui/Modal.vue'
import Empty from '@/components/ui/Empty.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import { confirm, toast } from '@/components/ui/toast'
import { debounce, fmtTime } from '@/utils/format'

const auth = useAuth()
const q = ref('')
const type = ref('')
const rows = ref<any[]>([])

async function load() {
  rows.value = await credentialApi.list(q.value, type.value)
}
onMounted(load)
watch(q, debounce(load, 300))
watch(type, load)

const open = ref(false)
const f = ref<any>({})
function edit(c?: any) {
  f.value = c
    ? { id: c.id, name: c.name, type: c.type, fields: (c.fields || []).map((x: any) => ({ name: x.name, envName: x.envName, value: '', show: false, existing: true })) }
    : { id: null, name: '', type: 'generic', fields: [{ name: '', envName: '', value: '', show: false }] }
  open.value = true
}
async function save() {
  const v = f.value
  if (!v.name.trim()) return toast.warning('请填写凭证名称')
  const fields = v.fields.filter((x: any) => x.name)
  if (!fields.length) return toast.warning('至少需要一个密钥字段')
  if (fields.some((x: any) => !x.existing && !x.value)) return toast.warning('新字段需要填写密钥值')
  const data = { name: v.name, type: v.type, fields: fields.map((x: any) => ({ name: x.name, envName: x.envName || x.name.toUpperCase(), value: x.value })) }
  if (v.id) await credentialApi.update(v.id, data)
  else await credentialApi.create(data)
  toast.success('凭证已保存')
  open.value = false
  load()
}
async function remove(c: any) {
  const msg = c.refAgents ? `有 ${c.refAgents} 个 Agent 引用了该凭证，删除后它们将无法读取对应环境变量。` : '删除后不可恢复。'
  if (!(await confirm({ title: `删除凭证「${c.name}」？`, message: msg, danger: true, okText: '删除' }))) return
  await credentialApi.remove(c.id)
  load()
}
const typeLabel = (t: string) => CRED_TYPES.find((x) => x.value === t)?.label || t
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title"><KeyRound style="width: 20px; color: var(--green)" /> 凭据</h1>
        <div class="page-sub">集中管理 API Key、AK/SK 等敏感凭证。密钥值加密存储，Agent 运行时以环境变量注入，页面不会回显原值。</div>
      </div>
      <button v-if="auth.can('plugin:edit')" class="btn btn-primary" @click="edit()"><Plus /> 新建凭据</button>
    </div>
    <div class="toolbar" style="margin-bottom: 12px">
      <SearchInput v-model="q" placeholder="搜索凭据名称" width="260px" />
      <Select v-model="type" :options="[{ label: '全部类型', value: '' }, ...CRED_TYPES]" size="sm" style="width: 140px" />
    </div>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>名称</th><th>类型</th><th>字段 → 环境变量</th><th>引用</th><th>创建人</th><th>更新时间</th><th class="actions">操作</th></tr></thead>
        <tbody>
          <tr v-for="c in rows" :key="c.id">
            <td><div class="row" style="gap: 8px"><div class="k-ico"><KeyRound /></div><span class="cell-main">{{ c.name }}</span></div></td>
            <td><span class="tag tag-round">{{ typeLabel(c.type) }}</span></td>
            <td>
              <div class="fields">
                <span v-for="x in c.fields" :key="x.name" class="fchip"><span>{{ x.name }}</span><ArrowRight /><span class="mono">{{ x.envName }}</span></span>
              </div>
            </td>
            <td><span class="tag">{{ c.refAgents || 0 }} 个 Agent</span></td>
            <td><UserAvatar :name="c.owner?.displayName" :size="20" show-name /></td>
            <td class="small muted">{{ fmtTime(c.updatedAt) }}</td>
            <td class="actions">
              <template v-if="auth.can('plugin:edit')">
                <button class="icon-btn" @click="edit(c)"><Pencil /></button>
                <button class="icon-btn danger" @click="remove(c)"><Trash2 /></button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <Empty v-if="!rows.length" :icon="KeyRound" title="还没有凭据" desc="新建凭据后，可在 Agent 的环境变量、CLI 插件、云日志插件中引用" />
    </div>

    <Modal :open="open" :title="f.id ? '编辑凭据' : '新建凭据'" width="720px" @close="open = false">
      <div class="notice notice-warn" style="margin-bottom: 16px"><KeyRound /> 密钥值保存后安全加密存储，页面不会再次显示原值。{{ f.id ? '编辑时留空表示不修改。' : '' }}</div>
      <div class="form-row">
        <div class="field"><label>凭证名称 <span class="req">*</span></label><input v-model="f.name" class="input" placeholder="例: OpenAI 生产环境" /></div>
        <div class="field"><label>类型</label><Select v-model="f.type" :options="CRED_TYPES" /></div>
      </div>
      <div class="row" style="justify-content: space-between; margin-bottom: 8px">
        <span class="label">密钥字段</span>
        <button class="btn" @click="f.fields.push({ name: '', envName: '', value: '', show: false })"><Plus /> 添加字段</button>
      </div>
      <div class="ftable">
        <div class="fhead"><span>字段名称</span><span>默认环境变量名</span><span>密钥值</span><span>操作</span></div>
        <div v-for="(x, i) in f.fields" :key="i" class="frow">
          <input v-model="x.name" class="input input-sm" placeholder="例: api_key" :disabled="x.existing" />
          <input v-model="x.envName" class="input input-sm mono" :placeholder="x.name ? x.name.toUpperCase() : '例: OPENAI_API_KEY'" />
          <div class="pw">
            <input v-model="x.value" class="input input-sm" :type="x.show ? 'text' : 'password'" :placeholder="x.existing ? '••••••（不修改）' : '请输入密钥值...'" autocomplete="new-password" />
            <button class="icon-btn sm" @click="x.show = !x.show"><EyeOff v-if="x.show" /><Eye v-else /></button>
          </div>
          <button class="icon-btn sm danger" :disabled="f.fields.length === 1" @click="f.fields.splice(i, 1)"><Trash2 /></button>
        </div>
      </div>
      <template #footer>
        <button class="btn" @click="open = false">取消</button>
        <button class="btn btn-primary" @click="save">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.k-ico { width: 28px; height: 28px; border-radius: 8px; background: var(--green-soft); color: var(--green-text); display: flex; align-items: center; justify-content: center; }
.k-ico svg { width: 14px; height: 14px; }
.fields { display: flex; flex-wrap: wrap; gap: 5px; }
.fchip { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 6px; background: var(--bg-muted); border: 1px solid var(--border); font-size: 11.5px; }
.fchip svg { width: 10px; height: 10px; color: var(--text-4); }
.ftable { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.fhead, .frow { display: grid; grid-template-columns: 1fr 1fr 1.2fr 40px; gap: 8px; padding: 8px 12px; align-items: center; }
.fhead { background: var(--bg-muted); font-size: 12px; color: var(--text-3); font-weight: 600; }
.pw { position: relative; display: flex; align-items: center; }
.pw .icon-btn { position: absolute; right: 2px; }
</style>
