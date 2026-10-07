<script setup lang="ts">
import { ref, watch } from 'vue'
import { Pencil, Cpu, Zap, TrendingUp, Box, Check, X } from 'lucide-vue-next'
import { sessionApi } from '@/api/session'
import { fmtTime, kfmt, ago } from '@/utils/format'

const props = defineProps<{ sessionId: string; title: string; refreshKey?: number }>()
const emit = defineEmits<{ (e: 'renamed', t: string): void }>()

const info = ref<any>(null)
const editing = ref(false)
const name = ref('')

async function load() {
  info.value = await sessionApi.info(props.sessionId)
}
watch(() => [props.sessionId, props.refreshKey], load, { immediate: true })

async function save() {
  if (!name.value.trim()) return
  await sessionApi.rename(props.sessionId, name.value.trim())
  editing.value = false
  emit('renamed', name.value.trim())
}

function pct(n: number, total: number) {
  return total ? Math.round((n / total) * 1000) / 10 : 0
}
</script>

<template>
  <div class="info-wrap">
    <div class="title-row">
      <template v-if="!editing">
        <span class="t ellipsis">{{ title }}</span>
        <button class="icon-btn" @click="name = title; editing = true"><Pencil /></button>
      </template>
      <template v-else>
        <input v-model="name" class="input" @keydown.enter="save" />
        <button class="icon-btn" @click="save"><Check /></button>
        <button class="icon-btn" @click="editing = false"><X /></button>
      </template>
    </div>

    <template v-if="info">
      <section class="card blk">
        <div class="blk-head">用量统计</div>
        <div class="model-row">
          <div>
            <div class="bold">{{ info.model.name }}</div>
            <div class="mono tiny muted">{{ info.model.ref }}</div>
          </div>
          <div class="right">
            <div class="bold mono">{{ info.model.contextPercent }}%</div>
            <div class="mono tiny muted">{{ kfmt(info.model.contextWindow) }}</div>
          </div>
        </div>
        <div class="bar thin"><i :style="{ width: Math.min(100, info.model.contextPercent) + '%', background: 'var(--green)' }" /></div>
        <div class="minis">
          <div class="mini">
            <div class="mini-ico"><Cpu /></div>
            <div><div class="mini-k">CONTEXT</div><div class="mini-v">{{ kfmt(info.model.contextTokens) }}</div><div class="mini-s">tokens in prompt</div></div>
          </div>
          <div class="mini">
            <div class="mini-ico"><Zap /></div>
            <div><div class="mini-k">CACHE HIT</div><div class="mini-v">{{ info.usage.cacheHit }}%</div><div class="mini-s">{{ kfmt(info.usage.cacheRead) }} cached</div></div>
          </div>
        </div>
        <div class="dist">
          <div class="dist-head"><TrendingUp /> Token 分布 <span class="spacer" /><span class="muted">共 {{ kfmt(info.usage.total) }}</span></div>
          <div v-for="r in [
            { k: 'Input', v: info.usage.input, c: 'var(--primary)' },
            { k: 'Output', v: info.usage.output, c: 'var(--green)' },
            { k: 'Cache 读取', v: info.usage.cacheRead, c: 'var(--orange)', sep: true },
            { k: 'Cache 写入', v: info.usage.cacheWrite, c: 'var(--purple)' },
          ]" :key="r.k" class="dist-row" :class="{ sep: r.sep }">
            <div class="dr-top"><span>{{ r.k }}</span><span class="mono bold" :style="{ color: r.c }">{{ kfmt(r.v) }}</span></div>
            <div class="bar"><i :style="{ width: pct(r.v, info.usage.total) + '%', background: r.c }" /></div>
            <div class="dr-pct mono">{{ pct(r.v, info.usage.total) }}%</div>
          </div>
        </div>
      </section>

      <section class="card blk">
        <div class="blk-head">运行环境</div>
        <div class="env-row">
          <div class="env-ico"><Box /></div>
          <div style="flex: 1">
            <div class="bold">{{ info.runtime.name || '—' }}</div>
            <div class="mono tiny muted">{{ info.runtime.networkPolicy === 'default' ? 'unrestricted' : info.runtime.networkPolicy }}</div>
          </div>
          <span class="tag tag-mono">{{ info.runtime.kind }}</span>
          <span v-if="info.runtime.sandbox" class="tag tag-mono bold">sandbox</span>
        </div>
      </section>

      <section class="card blk">
        <div class="blk-head">时间</div>
        <div class="kv"><span>创建时间</span><span class="mono">{{ fmtTime(info.times.createdAt) }}</span></div>
        <div class="kv"><span>更新时间</span><span class="mono">{{ fmtTime(info.times.updatedAt) }}</span></div>
        <div class="kv"><span>最后事件</span><span class="mono">{{ ago(info.times.lastEventAt, false) }}</span></div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.info-wrap { max-width: 520px; padding: 16px 18px 40px; }
.title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.title-row .t { flex: 1; font-size: 15px; font-weight: 600; font-family: var(--mono); }
.blk { margin-bottom: 16px; overflow: hidden; }
.blk-head { padding: 12px 16px; font-weight: 600; font-size: 12.5px; border-bottom: 1px solid var(--divider); }
.model-row { display: flex; justify-content: space-between; padding: 12px 16px 6px; }
.model-row .right { text-align: right; }
.bar { height: 5px; border-radius: 999px; background: var(--bg-muted); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 999px; transition: width .3s; }
.bar.thin { margin: 0 16px 12px; }
.minis { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding: 12px 16px 0; border-top: 1px solid var(--divider); }
.mini { display: flex; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 12px; }
.mini-ico { width: 26px; height: 26px; border-radius: 8px; border: 1px solid var(--border); display: flex; align-items: center; justify-content: center; color: var(--text-3); }
.mini-ico svg { width: 14px; height: 14px; }
.mini-k { font-size: 10.5px; letter-spacing: .06em; color: var(--text-3); }
.mini-v { font-size: 15px; font-weight: 700; font-family: var(--mono); }
.mini-s { font-size: 11px; color: var(--text-4); font-family: var(--mono); }
.dist { margin: 12px 16px 16px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; }
.dist-head { display: flex; align-items: center; gap: 6px; font-weight: 600; font-size: 12.5px; margin-bottom: 10px; }
.dist-head svg { width: 14px; height: 14px; }
.dist-row { margin-bottom: 10px; }
.dist-row.sep { border-top: 1px dashed var(--border); padding-top: 12px; }
.dr-top { display: flex; justify-content: space-between; font-size: 12.5px; margin-bottom: 6px; }
.dr-pct { font-size: 10.5px; color: var(--text-4); text-align: right; margin-top: 3px; }
.env-row { display: flex; align-items: center; gap: 10px; padding: 12px 16px; }
.env-ico { width: 30px; height: 30px; border-radius: 9px; background: var(--primary-soft); color: var(--primary); display: flex; align-items: center; justify-content: center; }
.env-ico svg { width: 16px; height: 16px; }
.kv { display: flex; justify-content: space-between; padding: 10px 16px; border-top: 1px solid var(--divider); font-size: 12.5px; color: var(--text-2); }
.kv:first-of-type { border-top: none; }
</style>
