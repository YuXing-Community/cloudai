<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ExternalLink, RotateCw, Sparkles, ThumbsUp, ThumbsDown, Trash2, ChevronRight, Loader2, AlertTriangle, Lightbulb, Wand2 } from 'lucide-vue-next'
import Drawer from '@/components/ui/Drawer.vue'
import MarkdownView from '@/components/ui/MarkdownView.vue'
import MessageList from '@/components/chat/MessageList.vue'
import ScoreBadge from './ScoreBadge.vue'
import { evalApi, annotationApi, LABELS, LABEL_MAP, ISSUE_TAGS, SEVERITY, RESULT_STATUS, scoreTone, fmtScore } from '@/api/eval'
import { sessionApi, type SessionEvent } from '@/api/session'
import { useAuth } from '@/stores/auth'
import { confirm, toast } from '@/components/ui/toast'
import { duration, fmtTime, shortId } from '@/utils/format'

const props = defineProps<{ open: boolean; mode: 'eval' | 'session'; evalResultId?: number | null; sessionId?: string | null; hasNext?: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void; (e: 'next'): void }>()

const auth = useAuth()
const router = useRouter()
const canAnnotate = computed(() => auth.can('eval:annotate'))
const canEdit = computed(() => auth.can('eval:edit'))

const result = ref<any>(null)
const events = ref<SessionEvent[]>([])
const view = ref<'original' | 'replay'>('original')
const loading = ref(false)
const others = ref<any[]>([])
const form = ref<any>({})
const saving = ref(false)
const customTag = ref('')
const rightTab = ref<'eval' | 'annotate'>('eval')

const sid = computed(() => (props.mode === 'eval' ? result.value?.sessionId : props.sessionId) || '')
const shownSession = computed(() => (view.value === 'replay' && result.value?.replaySessionId ? result.value.replaySessionId : sid.value))

function blankForm() {
  return { id: null, score: null as number | null, label: '', tags: [] as string[], agreeWithModel: null as boolean | null, comment: '', correctedReply: '' }
}

async function load() {
  loading.value = true
  result.value = null
  events.value = []
  view.value = 'original'
  form.value = blankForm()
  rightTab.value = props.mode === 'eval' ? 'eval' : 'annotate'
  try {
    if (props.mode === 'eval' && props.evalResultId) result.value = await evalApi.result(props.evalResultId)
    await Promise.all([loadEvents(), loadAnnotations()])
  } finally {
    loading.value = false
  }
}
async function loadEvents() {
  if (!shownSession.value) return
  events.value = await sessionApi.events(shownSession.value).catch(() => [])
}
async function loadAnnotations() {
  const r = await annotationApi.target(props.mode, props.evalResultId || undefined, props.mode === 'session' ? sid.value : undefined)
  others.value = r.all.filter((a: any) => !a.mine)
  const m = r.mine
  form.value = m
    ? { id: m.id, score: m.score, label: m.label || '', tags: [...(m.tags || [])], agreeWithModel: m.agreeWithModel, comment: m.comment || '', correctedReply: m.correctedReply || '' }
    : blankForm()
}
watch(() => [props.open, props.evalResultId, props.sessionId], () => props.open && load())
watch(view, loadEvents)

// 运行中的结果自动刷新
let poll: any
watch(() => result.value?.status, (s) => {
  clearTimeout(poll)
  if (props.open && (s === 'pending' || s === 'running')) poll = setTimeout(async () => { result.value = await evalApi.result(props.evalResultId!) ; if (result.value.status === 'success') loadEvents() }, 3000)
})

function toggleTag(t: string) {
  const i = form.value.tags.indexOf(t)
  i >= 0 ? form.value.tags.splice(i, 1) : form.value.tags.push(t)
}
function addCustomTag() {
  const t = customTag.value.trim()
  if (t && !form.value.tags.includes(t)) form.value.tags.push(t)
  customTag.value = ''
}
function useImproved() {
  form.value.correctedReply = result.value?.improvedReply || ''
}
function adoptModelScore() {
  if (result.value?.score != null) form.value.score = Math.round(Number(result.value.score))
}

async function save(goNext = false) {
  const v = form.value
  if (v.score == null && !v.label) return toast.warning('请至少填写人工评分或结论')
  saving.value = true
  try {
    await annotationApi.save({
      mode: props.mode, evalResultId: props.mode === 'eval' ? props.evalResultId : null, sessionId: sid.value,
      score: v.score === '' ? null : v.score, label: v.label || null, tags: v.tags, agreeWithModel: props.mode === 'eval' ? v.agreeWithModel : null,
      comment: v.comment, correctedReply: v.correctedReply,
    })
    toast.success('标注已保存')
    emit('saved')
    if (goNext) emit('next')
    else loadAnnotations()
  } finally {
    saving.value = false
  }
}
async function removeMine() {
  if (!form.value.id || !(await confirm({ title: '删除我的标注？', danger: true, okText: '删除' }))) return
  await annotationApi.remove(form.value.id)
  toast.success('已删除')
  emit('saved')
  loadAnnotations()
}
async function rerun() {
  await evalApi.rerun(result.value.id)
  toast.success('已重新提交评测')
  result.value = await evalApi.result(result.value.id)
  emit('saved')
}
function openSession(id: string) {
  router.push(`/sessions/${id}`)
}
const title = computed(() => (props.mode === 'eval' ? result.value?.sessionTitle : '') || '会话详情')
</script>

<template>
  <Drawer :open="open" width="min(1240px, 96vw)" @close="emit('close')">
    <template #header>
      <div style="flex: 1; min-width: 0">
        <div class="row" style="gap: 8px">
          <h3 class="drawer-title ellipsis">{{ mode === 'eval' ? title : '会话打标' }}</h3>
          <span class="tag" :class="mode === 'eval' ? 'tag-purple' : 'tag-cyan'">{{ mode === 'eval' ? '基于模型评测' : '直接基于会话' }}</span>
        </div>
        <div class="tiny muted row" style="gap: 10px; margin-top: 4px">
          <span class="mono">会话 {{ shortId(sid, 12) }}</span>
          <template v-if="result"><span>{{ result.agentName }} v{{ result.agentVersion }}</span><span>执行模型 {{ result.sessionModelRef }}</span><span>评测任务 {{ result.taskName }}</span></template>
        </div>
      </div>
    </template>

    <div class="layout">
      <!-- 左：会话轨迹 -->
      <section class="left">
        <div class="left-head">
          <div v-if="result?.replaySessionId" class="segmented">
            <button :class="{ active: view === 'original' }" @click="view = 'original'">原会话</button>
            <button :class="{ active: view === 'replay' }" @click="view = 'replay'">重放会话（被评测）</button>
          </div>
          <span v-else class="bold small">执行轨迹</span>
          <span class="spacer" />
          <button v-if="shownSession" class="btn btn-sm btn-ghost" @click="openSession(shownSession)"><ExternalLink /> 打开会话</button>
        </div>
        <div class="trace">
          <div v-if="loading && !events.length" class="empty-box">加载中…</div>
          <MessageList v-else-if="events.length" :events="events" :agent-name="result?.agentName || 'Agent'" compact />
          <div v-else class="empty-box">没有会话事件</div>
        </div>
      </section>

      <!-- 右：模型评测 + 人工打标 -->
      <section class="right">
        <div v-if="mode === 'eval'" class="tabs" style="margin-bottom: 14px">
          <button :class="{ active: rightTab === 'eval' }" @click="rightTab = 'eval'">模型评测</button>
          <button :class="{ active: rightTab === 'annotate' }" @click="rightTab = 'annotate'">人工打标 <span v-if="others.length || form.id" class="count">{{ others.length + (form.id ? 1 : 0) }}</span></button>
        </div>

        <!-- 模型评测 -->
        <template v-if="mode === 'eval' && rightTab === 'eval' && result">
          <div v-if="result.status !== 'success'" class="card card-pad">
            <div class="row" style="gap: 8px">
              <Loader2 v-if="result.status === 'pending' || result.status === 'running'" class="spin" style="width: 16px" />
              <span class="tag" :class="RESULT_STATUS[result.status]?.tone">{{ RESULT_STATUS[result.status]?.label }}</span>
              <span class="spacer" />
              <button v-if="canEdit && result.status === 'failed'" class="btn btn-sm" @click="rerun"><RotateCw /> 重新评测</button>
            </div>
            <pre v-if="result.error" class="code-soft" style="margin-top: 10px">{{ result.error }}</pre>
          </div>
          <template v-else>
            <div class="score-card card">
              <ScoreBadge :score="result.score" size="lg" />
              <div style="flex: 1; min-width: 0">
                <div class="row" style="gap: 6px">
                  <span class="tag tag-round" :class="result.passed ? 'tag-green' : 'tag-red'">{{ result.passed ? '及格' : '不及格' }}</span>
                  <span class="tiny muted">评测模型 {{ result.judgeModelRef }} · {{ duration(result.durationMs) }} · {{ result.judgeInputTokens + result.judgeOutputTokens }} tokens</span>
                </div>
                <div class="small" style="margin-top: 6px; line-height: 1.6">{{ result.summary }}</div>
              </div>
              <button v-if="canEdit" class="icon-btn" title="重新评测" @click="rerun"><RotateCw /></button>
            </div>

            <div class="sec-title">维度得分</div>
            <div class="dims">
              <div v-for="d in result.dimensions" :key="d.key" class="dim">
                <div class="row" style="gap: 8px">
                  <span class="bold small">{{ d.name }}</span><span class="tiny muted">权重 {{ d.weight }}%</span>
                  <span class="spacer" /><span class="bold small" :class="'t-' + scoreTone(d.score)">{{ fmtScore(d.score) }}</span>
                </div>
                <div class="bar"><div :class="scoreTone(d.score)" :style="{ width: d.score + '%' }" /></div>
                <div class="tiny muted" style="line-height: 1.5">{{ d.comment }}</div>
              </div>
            </div>

            <template v-if="result.issues?.length">
              <div class="sec-title"><AlertTriangle /> 发现的问题 <span class="count">{{ result.issues.length }}</span></div>
              <div v-for="(i, k) in result.issues" :key="k" class="issue">
                <span class="tag" :class="SEVERITY[i.severity]?.tone">{{ SEVERITY[i.severity]?.label || i.severity }}</span>
                <div><div v-if="i.step" class="tiny muted">{{ i.step }}</div><div class="small">{{ i.description }}</div></div>
              </div>
            </template>

            <template v-if="result.suggestions?.length">
              <div class="sec-title"><Lightbulb /> 回复内容修改意见</div>
              <ol class="sugg"><li v-for="(s, k) in result.suggestions" :key="k">{{ s }}</li></ol>
            </template>

            <template v-if="result.improvedReply">
              <div class="sec-title"><Wand2 /> 建议的改写回复</div>
              <div class="improved"><MarkdownView :source="result.improvedReply" compact /></div>
            </template>
            <button v-if="canAnnotate" class="btn btn-primary btn-block" style="margin-top: 16px" @click="rightTab = 'annotate'">人工复核打标 <ChevronRight /></button>
          </template>
        </template>

        <!-- 人工打标 -->
        <template v-if="mode === 'session' || rightTab === 'annotate'">
          <div v-if="!canAnnotate" class="notice notice-warn" style="margin-bottom: 12px">你没有「人工打标」权限，只能查看标注</div>
          <fieldset class="fieldset" :disabled="!canAnnotate">
            <legend>我的标注<span v-if="form.id" class="tag tag-green" style="margin-left: 8px">已标注</span></legend>
            <div v-if="mode === 'eval' && result?.status === 'success'" class="field">
              <label>是否认可模型评测结论（模型 {{ fmtScore(result.score) }} 分）</label>
              <div class="row" style="gap: 8px">
                <button class="opt" :class="{ on: form.agreeWithModel === true }" @click="form.agreeWithModel = form.agreeWithModel === true ? null : true"><ThumbsUp /> 认可</button>
                <button class="opt" :class="{ on: form.agreeWithModel === false, bad: form.agreeWithModel === false }" @click="form.agreeWithModel = form.agreeWithModel === false ? null : false"><ThumbsDown /> 不认可</button>
              </div>
            </div>
            <div class="field">
              <label>人工评分（0-100）<button v-if="mode === 'eval' && result?.score != null" class="link-btn tiny" style="margin-left: auto" @click="adoptModelScore">采用模型分</button></label>
              <div class="row" style="gap: 12px">
                <input v-model.number="form.score" type="range" min="0" max="100" step="1" style="flex: 1" :class="{ unset: form.score == null || form.score === '' }" />
                <input v-model.number="form.score" type="number" min="0" max="100" class="input" style="width: 76px" placeholder="—" />
              </div>
            </div>
            <div class="field">
              <label>结论</label>
              <div class="row" style="gap: 8px">
                <button v-for="l in LABELS" :key="l.value" class="opt" :class="[{ on: form.label === l.value }, l.value]" @click="form.label = form.label === l.value ? '' : l.value">{{ l.label }}</button>
              </div>
            </div>
            <div class="field">
              <label>问题标签</label>
              <div class="tagbox">
                <button v-for="t in ISSUE_TAGS" :key="t" class="chip" :class="{ on: form.tags.includes(t) }" @click="toggleTag(t)">{{ t }}</button>
                <button v-for="t in form.tags.filter((x: string) => !ISSUE_TAGS.includes(x))" :key="t" class="chip on" @click="toggleTag(t)">{{ t }} ×</button>
                <input v-model="customTag" class="input input-sm" placeholder="自定义标签，回车添加" style="width: 150px" @keydown.enter.prevent="addCustomTag" />
              </div>
            </div>
            <div class="field"><label>人工意见</label><textarea v-model="form.comment" class="textarea" rows="3" placeholder="说明打分依据、问题所在、期望的处理方式" /></div>
            <div class="field">
              <label>修正后的回复 <button v-if="result?.improvedReply" class="link-btn tiny" style="margin-left: auto" @click="useImproved"><Sparkles style="width: 12px" /> 填入模型建议改写</button></label>
              <textarea v-model="form.correctedReply" class="textarea" rows="5" placeholder="可选：期望 Agent 给出的回复，可作为后续优化提示词或训练数据" />
            </div>
            <div v-if="canAnnotate" class="row" style="gap: 8px">
              <button v-if="form.id" class="btn btn-danger-ghost" @click="removeMine"><Trash2 /> 删除</button>
              <span class="spacer" />
              <button class="btn" :disabled="saving" @click="save(false)">保存</button>
              <button v-if="hasNext" class="btn btn-primary" :disabled="saving" @click="save(true)">保存并下一条 <ChevronRight /></button>
            </div>
          </fieldset>

          <template v-if="others.length">
            <div class="sec-title">其他人的标注 <span class="count">{{ others.length }}</span></div>
            <div v-for="a in others" :key="a.id" class="other">
              <div class="row" style="gap: 8px">
                <span class="bold small">{{ a.annotator?.displayName || a.annotator?.username }}</span>
                <ScoreBadge :score="a.score" size="sm" />
                <span v-if="a.label" class="tag" :class="LABEL_MAP[a.label]?.tone">{{ LABEL_MAP[a.label]?.label }}</span>
                <span class="spacer" /><span class="tiny muted">{{ fmtTime(a.updatedAt) }}</span>
              </div>
              <div v-if="a.tags?.length" class="row" style="gap: 4px; margin-top: 6px; flex-wrap: wrap"><span v-for="t in a.tags" :key="t" class="tag">{{ t }}</span></div>
              <div v-if="a.comment" class="small" style="margin-top: 6px">{{ a.comment }}</div>
            </div>
          </template>
        </template>
      </section>
    </div>
  </Drawer>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(380px, 1fr); gap: 18px; height: 100%; min-height: 0; }
.left { display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.left-head { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--divider); background: var(--bg-muted); }
.trace { flex: 1; overflow: auto; padding: 14px; max-height: calc(100vh - 150px); }
.right { overflow: auto; max-height: calc(100vh - 120px); padding-right: 2px; }
.score-card { display: flex; gap: 14px; align-items: flex-start; padding: 14px; margin-bottom: 14px; }
.sec-title { display: flex; align-items: center; gap: 6px; font-weight: 600; font-size: 13px; margin: 16px 0 8px; }
.sec-title svg { width: 14px; height: 14px; color: var(--text-3); }
.sec-title .count { font-size: 11px; padding: 0 6px; border-radius: 6px; background: var(--bg-muted); color: var(--text-3); }
.dims { display: grid; gap: 10px; }
.dim { padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; }
.bar { height: 6px; border-radius: 6px; background: var(--bg-muted); margin: 6px 0; overflow: hidden; }
.bar > div { height: 100%; border-radius: 6px; background: var(--text-4); }
.bar > .good { background: var(--green); }
.bar > .ok { background: var(--primary); }
.bar > .bad { background: var(--red); }
.t-good { color: var(--green-text); }
.t-ok { color: var(--primary-text); }
.t-bad { color: var(--red-text); }
.issue { display: flex; gap: 10px; align-items: flex-start; padding: 8px 10px; border: 1px solid var(--border); border-radius: 10px; margin-bottom: 6px; }
.issue .tag { flex-shrink: 0; }
.sugg { margin: 0; padding-left: 20px; font-size: 13px; line-height: 1.7; }
.improved { border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; background: var(--bg-muted); max-height: 360px; overflow: auto; }
.opt { display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 14px; border: 1px solid var(--border); border-radius: 9px; background: var(--bg-elev); cursor: pointer; color: var(--text-2); font-weight: 600; }
.opt svg { width: 14px; height: 14px; }
.opt.on { border-color: var(--primary); background: var(--primary-soft); color: var(--primary-text); }
.opt.on.good { border-color: var(--green); background: var(--green-soft); color: var(--green-text); }
.opt.on.bad { border-color: var(--red); background: var(--red-soft); color: var(--red-text); }
.tagbox { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.chip { height: 26px; padding: 0 10px; border: 1px solid var(--border); border-radius: 999px; background: var(--bg-elev); color: var(--text-2); cursor: pointer; font-size: 12px; }
.chip.on { border-color: var(--orange); background: var(--orange-soft); color: var(--orange-text); font-weight: 600; }
.other { padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; margin-bottom: 8px; }
input[type=range].unset { opacity: .35; }
fieldset:disabled .opt, fieldset:disabled .chip { cursor: not-allowed; opacity: .7; }
</style>
