<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Upload, Check } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import Modal from '@/components/ui/Modal.vue'
import Select from '@/components/ui/Select.vue'

const props = defineProps<{ open: boolean; skills: any[]; selected: any[] }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'save', v: any[]): void }>()
const router = useRouter()

const q = ref('')
const scope = ref('')
const picked = ref<any[]>([])
const page = ref(1)
const size = 12
watch(() => props.open, (v) => { if (v) { picked.value = props.selected.map((s) => ({ ...s })); q.value = ''; page.value = 1 } })

const filtered = computed(() => props.skills.filter((s) =>
  (!scope.value || s.scope === scope.value) &&
  (!q.value || `${s.name} ${s.description || ''}`.toLowerCase().includes(q.value.toLowerCase()))))
const paged = computed(() => filtered.value.slice((page.value - 1) * size, page.value * size))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / size)))
watch([q, scope], () => (page.value = 1))

function isOn(id: string) { return picked.value.some((p) => p.skillId === id) }
function toggle(s: any) {
  if (isOn(s.id)) picked.value = picked.value.filter((p) => p.skillId !== s.id)
  else picked.value.push({ skillId: s.id, name: s.name, version: '' })
}
</script>

<template>
  <Modal :open="open" title="添加 Skills" subtitle="从目录中选择 Skill，配置页只展示已添加的条目。" width="860px" flush @close="emit('close')">
    <div class="pk">
      <div class="pk-left">
        <div class="pk-bar">
          <input v-model="q" class="input" placeholder="搜索 Skill" />
          <Select v-model="scope" :options="[{ label: '全部范围', value: '' }, { label: '工作空间', value: '工作空间' }, { label: '共享', value: '共享' }]" style="width: 140px" />
          <button class="btn" @click="router.push('/plugins/skills/upload')"><Upload /> 上传 Skill</button>
        </div>
        <div class="pk-list">
          <div v-for="s in paged" :key="s.id" class="sk" :class="{ on: isOn(s.id) }" @click="toggle(s)">
            <span class="radio"><Check v-if="isOn(s.id)" /></span>
            <div style="min-width: 0; flex: 1">
              <div class="row" style="gap: 6px">
                <span class="bold">{{ s.name }}</span>
                <span class="tag tag-round">{{ s.scope || '工作空间' }}</span>
                <span class="tag tag-round tag-mono">v{{ s.currentVersion }}</span>
              </div>
              <div class="mono tiny muted">{{ s.name }}</div>
              <div class="small muted clamp-2">{{ s.description }}</div>
            </div>
          </div>
          <div v-if="!paged.length" class="empty small">没有匹配的 Skill</div>
        </div>
        <div class="pk-foot">
          <span class="small muted">共 {{ filtered.length }} 条</span>
          <div class="row">
            <button class="btn btn-sm" :disabled="page <= 1" @click="page--">上一页</button>
            <span class="small">{{ page }} / {{ pages }}</span>
            <button class="btn btn-sm" :disabled="page >= pages" @click="page++">下一页</button>
          </div>
        </div>
      </div>
      <div class="pk-right">
        <div class="row" style="justify-content: space-between; padding: 14px 14px 10px"><span class="bold">已添加</span><button class="link-btn small" @click="picked = []">清空</button></div>
        <div v-for="p in picked" :key="p.skillId" class="pk-sel">
          <span class="ellipsis">{{ p.name }}</span>
          <button class="link-btn small danger-text" @click="picked = picked.filter((x) => x !== p)">移除</button>
        </div>
        <div v-if="!picked.length" class="empty small">暂未添加 Skill</div>
      </div>
    </div>
    <template #footer>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn btn-primary" @click="emit('save', picked); emit('close')">确定（{{ picked.length }}）</button>
    </template>
  </Modal>
</template>

<style scoped>
.pk { display: flex; height: 560px; border-top: 1px solid var(--divider); }
.pk-left { flex: 1; min-width: 0; display: flex; flex-direction: column; border-right: 1px solid var(--divider); }
.pk-bar { display: flex; gap: 8px; padding: 14px; border-bottom: 1px solid var(--divider); }
.pk-list { flex: 1; overflow: auto; }
.sk { display: flex; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--divider); cursor: pointer; }
.sk:hover { background: var(--bg-hover); }
.sk.on { background: var(--primary-soft); }
.radio { width: 16px; height: 16px; border-radius: 50%; border: 1.5px solid var(--primary); display: flex; align-items: center; justify-content: center; margin-top: 2px; flex-shrink: 0; }
.sk.on .radio { background: var(--primary); color: #fff; }
.radio svg { width: 11px; height: 11px; }
.pk-foot { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; border-top: 1px solid var(--divider); }
.pk-right { width: 240px; flex-shrink: 0; overflow: auto; background: var(--bg-muted); }
.pk-sel { display: flex; justify-content: space-between; gap: 8px; padding: 8px 14px; font-size: 12.5px; }
</style>
