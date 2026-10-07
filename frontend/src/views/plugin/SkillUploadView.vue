<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Folder, FolderArchive, Lightbulb, CheckCircle2, Loader2, FileText, ArrowLeft, Bold, Heading2, List, Code2, Image, Upload } from 'lucide-vue-next'
import { skillApi, SKILL_CATEGORIES } from '@/api/skill'
import { useAuth } from '@/stores/auth'
import Select from '@/components/ui/Select.vue'
import { toast } from '@/components/ui/toast'
import { bytes } from '@/utils/format'

const router = useRouter()
const auth = useAuth()
const dirEl = ref<HTMLInputElement | null>(null)
const zipEl = ref<HTMLInputElement | null>(null)
const descEl = ref<HTMLTextAreaElement | null>(null)

const mode = ref<'dir' | 'zip' | null>(null)
const dirFiles = ref<File[]>([])
const zipFile = ref<File | null>(null)
const parsed = ref<any>(null)
const parsing = ref(false)
const uploading = ref(false)
const meta = ref({ name: '', version: '', description: '', category: '', tags: '' })

function formData(withMeta: boolean) {
  const fd = new FormData()
  if (mode.value === 'zip' && zipFile.value) fd.append('zip', zipFile.value)
  else for (const f of dirFiles.value) {
    fd.append('files', f)
    fd.append('paths', (f as any).webkitRelativePath || f.name)
  }
  if (withMeta) Object.entries(meta.value).forEach(([k, v]) => v && fd.append(k, v))
  return fd
}
async function parse() {
  parsing.value = true
  parsed.value = null
  try {
    const r = await skillApi.parse(formData(false))
    parsed.value = r
    meta.value.name = meta.value.name || r.name || ''
    meta.value.version = meta.value.version || r.version || '1.0.0'
    meta.value.description = meta.value.description || r.description || ''
  } finally {
    parsing.value = false
  }
}
function onDir(e: Event) {
  const fs = Array.from((e.target as HTMLInputElement).files || []).filter((f) => !f.name.startsWith('.DS_Store'))
  if (!fs.length) return
  mode.value = 'dir'
  dirFiles.value = fs
  zipFile.value = null
  parse()
}
function onZip(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  mode.value = 'zip'
  zipFile.value = f
  dirFiles.value = []
  parse()
}
const hasSkillMd = computed(() => parsed.value?.files?.some((f: any) => f.path === 'SKILL.md'))
const selectedText = computed(() => {
  if (mode.value === 'zip' && zipFile.value) return `已选择压缩包 ${zipFile.value.name}（${bytes(zipFile.value.size)}）`
  if (mode.value === 'dir' && dirFiles.value.length) return `已选择文件夹 ${(dirFiles.value[0] as any).webkitRelativePath?.split('/')[0] || ''}，共 ${dirFiles.value.length} 个文件`
  return '尚未选择文件夹或压缩包'
})

function insert(before: string, after = '', placeholder = '') {
  const el = descEl.value
  if (!el) return
  const s = el.selectionStart, e = el.selectionEnd
  const v = meta.value.description
  const sel = v.slice(s, e) || placeholder
  meta.value.description = v.slice(0, s) + before + sel + after + v.slice(e)
  requestAnimationFrame(() => { el.focus(); el.setSelectionRange(s + before.length, s + before.length + sel.length) })
}

async function submit() {
  if (!parsed.value) return toast.warning('请先选择技能文件夹或压缩包')
  if (!hasSkillMd.value) return toast.warning('技能包根目录必须包含 SKILL.md')
  uploading.value = true
  try {
    const r = await skillApi.upload(formData(true))
    toast.success(`技能 ${r.name} v${r.version} 已上传`)
    router.push('/plugins/skills')
  } finally {
    uploading.value = false
  }
}
const tagList = computed(() => meta.value.tags.split(/[,，]/).map((t) => t.trim()).filter(Boolean))
</script>

<template>
  <div class="page up">
    <button class="btn btn-ghost btn-sm" style="margin-bottom: 10px" @click="router.push('/plugins/skills')"><ArrowLeft /> 返回技能列表</button>
    <h1 class="up-title">上传新技能</h1>
    <p class="up-sub">上传页会直接映射到技能详情：名称、版本、分类、描述、标签以及技能压缩包内容都会同步展示。用户只需要选择文件夹或压缩包，系统会自动识别并构建详情页里的文件树预览。</p>

    <div class="up-grid">
      <div class="up-main">
        <section class="step">
          <div class="step-head"><span class="num">1</span> 选择技能文件</div>
          <div class="picker">
            <div class="pick-row">
              <button class="pick" :class="{ on: mode === 'dir' }" @click="dirEl?.click()">
                <span class="pk-ico"><Folder /></span>
                <span class="pk-title">选择技能文件夹</span>
                <span class="pk-sub">支持读取目录结构与文本文件内容</span>
              </button>
              <button class="pick" :class="{ on: mode === 'zip' }" @click="zipEl?.click()">
                <span class="pk-ico"><FolderArchive /></span>
                <span class="pk-title">选择技能压缩包</span>
                <span class="pk-sub">支持 `.zip`，会解析内部目录与文本文件</span>
              </button>
              <input ref="dirEl" type="file" webkitdirectory directory multiple hidden @change="onDir" />
              <input ref="zipEl" type="file" accept=".zip" hidden @change="onZip" />
            </div>
            <div class="sel-text">{{ selectedText }}</div>
            <div v-if="parsing" class="row small muted" style="gap: 6px; padding: 8px 0"><Loader2 class="spin" style="width: 14px" /> 正在解析…</div>
            <div v-if="parsed" class="file-list">
              <div class="row small" style="gap: 6px; margin-bottom: 6px">
                <CheckCircle2 :style="{ width: '15px', color: hasSkillMd ? 'var(--green)' : 'var(--red)' }" />
                <span :class="hasSkillMd ? '' : 'danger-text'">{{ hasSkillMd ? '已识别 SKILL.md' : '未找到 SKILL.md' }}</span>
                <span class="muted">· {{ parsed.files.length }} 个文件</span>
              </div>
              <div v-for="f in parsed.files.slice(0, 40)" :key="f.path" class="fl"><FileText /><span class="mono ellipsis">{{ f.path }}</span><span class="faint">{{ bytes(f.size) }}</span></div>
              <div v-if="parsed.files.length > 40" class="tiny muted">… 还有 {{ parsed.files.length - 40 }} 个文件</div>
            </div>
          </div>
        </section>

        <section class="step">
          <div class="step-head"><span class="num">2</span> 技能元数据</div>
          <div class="form-row">
            <div class="field"><label>技能名称</label><input v-model="meta.name" class="input big" placeholder="自动从 SKILL.md 填充" /></div>
            <div class="field"><label>版本</label><input v-model="meta.version" class="input big" placeholder="例如：1.0.0" /></div>
          </div>
          <div class="field">
            <label>技能描述</label>
            <div class="editor">
              <div class="ed-bar">
                <button title="加粗" @click="insert('**', '**', '加粗')"><Bold /></button>
                <button title="标题" @click="insert('\n## ', '', '标题')"><Heading2 /></button>
                <button title="列表" @click="insert('\n- ', '', '列表项')"><List /></button>
                <button title="代码" @click="insert('\n```\n', '\n```\n', 'code')"><Code2 /></button>
                <button title="图片" @click="insert('![', '](https://)', '图片描述')"><Image /></button>
              </div>
              <textarea ref="descEl" v-model="meta.description" class="ed-ta" placeholder="请输入技能描述，支持标题、列表、代码块、图片。" />
            </div>
          </div>
          <div class="field" style="max-width: 50%"><label>类别</label><Select v-model="meta.category" :options="SKILL_CATEGORIES.map((c) => ({ label: c, value: c }))" placeholder="请选择类别" /></div>
          <div class="field" style="margin-bottom: 0"><label>标签</label><input v-model="meta.tags" class="input big" placeholder="例如：分析, SQL, 自动化" /></div>
        </section>
      </div>

      <aside class="up-side">
        <div class="guide">
          <div class="row bold" style="gap: 8px; font-size: 15px"><Lightbulb style="width: 18px" /> 说明指南</div>
          <ul>
            <li>支持直接选择技能文件夹，或点击选择 zip 压缩包，系统会自动判断并解析目录。</li>
            <li>技能 ID 将自动生成，不需要手动填写；同名技能上传会生成新版本。</li>
            <li>作者固定使用当前用户信息，上传后会直接映射到技能详情页。</li>
          </ul>
        </div>
        <div class="card preview">
          <div class="pv-name">{{ meta.name || '未命名技能' }}</div>
          <div class="small muted">{{ auth.user?.displayName }} · {{ meta.version ? 'v' + meta.version : '版本待定' }} · {{ meta.category || '未选分类' }}</div>
          <div class="pv-tags">
            <span v-for="t in tagList" :key="t" class="tag tag-blue">{{ t }}</span>
            <span v-if="!tagList.length" class="tag">等待填写标签</span>
          </div>
          <p v-if="meta.description" class="small clamp-3" style="color: var(--text-2); margin-top: 10px">{{ meta.description }}</p>
          <button class="btn btn-primary btn-block btn-lg" style="margin-top: 16px" :disabled="uploading || !parsed" @click="submit">
            <Loader2 v-if="uploading" class="spin" /><Upload v-else /> 上传技能
          </button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.up { max-width: 1240px; margin: 0 auto; }
.up-title { font-size: 30px; font-weight: 800; letter-spacing: -.02em; }
.up-sub { color: var(--text-3); max-width: 720px; line-height: 1.7; margin: 6px 0 24px; }
.up-grid { display: grid; grid-template-columns: 1fr 380px; gap: 28px; align-items: start; }
.step { background: var(--bg-muted); border-radius: 18px; padding: 22px; margin-bottom: 22px; }
.step-head { display: flex; align-items: center; gap: 12px; font-size: 18px; font-weight: 700; margin-bottom: 16px; }
.num { width: 30px; height: 30px; border-radius: 50%; background: #0f766e; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 14px; }
.picker { background: var(--bg-elev); border-radius: 14px; padding: 14px; }
.pick-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.pick { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 30px 16px; border: 1.5px dashed #9cc5bc; border-radius: 14px; background: transparent; cursor: pointer; }
.pick:hover, .pick.on { background: #f0faf7; border-color: #0f766e; }
[data-theme="dark"] .pick:hover, [data-theme="dark"] .pick.on { background: #10261f; }
.pk-ico { width: 52px; height: 52px; border-radius: 50%; background: #e6f3ef; color: #0f766e; display: flex; align-items: center; justify-content: center; margin-bottom: 4px; }
.pk-ico svg { width: 22px; height: 22px; }
.pk-title { font-weight: 700; font-size: 15px; }
.pk-sub { font-size: 12.5px; color: var(--text-3); }
.sel-text { color: #0f766e; font-size: 12.5px; padding: 10px 2px 2px; font-weight: 500; }
.file-list { margin-top: 8px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; max-height: 260px; overflow: auto; }
.fl { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 2px 0; }
.fl svg { width: 12px; height: 12px; color: var(--text-4); flex-shrink: 0; }
.fl .ellipsis { flex: 1; }
.input.big { height: 44px; border-radius: 12px; font-size: 14px; }
.editor { border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: var(--bg-elev); }
.ed-bar { display: flex; gap: 4px; padding: 8px 10px; border-bottom: 1px solid var(--border); }
.ed-bar button { width: 30px; height: 28px; border: none; background: transparent; border-radius: 7px; cursor: pointer; color: var(--text-2); display: flex; align-items: center; justify-content: center; }
.ed-bar button:hover { background: var(--bg-hover); }
.ed-bar svg { width: 16px; height: 16px; }
.ed-ta { width: 100%; min-height: 220px; border: none; outline: none; resize: vertical; padding: 14px 16px; font-size: 14px; line-height: 1.7; background: transparent; color: var(--text); }
.up-side { position: sticky; top: 16px; display: flex; flex-direction: column; gap: 18px; }
.guide { padding: 20px 22px; border-radius: 16px; background: linear-gradient(135deg, #ffe9e1, #ffd5c6); color: #7a2e12; }
[data-theme="dark"] .guide { background: linear-gradient(135deg, #3a2018, #2b1912); color: #f5b9a3; }
.guide ul { margin: 12px 0 0; padding-left: 18px; font-size: 13px; line-height: 1.75; }
.guide li { margin-bottom: 6px; }
.preview { padding: 18px 20px; }
.pv-name { font-size: 17px; font-weight: 700; margin-bottom: 4px; }
.pv-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 12px; }
</style>
