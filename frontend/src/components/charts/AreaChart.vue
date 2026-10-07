<script setup lang="ts">
import { computed, ref } from 'vue'

/** 平滑面积图（纯 SVG），带网格、坐标轴与悬停提示 */
const props = withDefaults(defineProps<{ points: { label: string; value: number }[]; height?: number; format?: (v: number) => string; color?: string }>(), {
  height: 220, format: (v: number) => String(v), color: 'var(--primary)',
})

const W = 1000
const PAD = { l: 48, r: 12, t: 12, b: 28 }
const hover = ref<number | null>(null)

const max = computed(() => {
  const m = Math.max(0, ...props.points.map((p) => p.value))
  if (m === 0) return 1
  const p = Math.pow(10, Math.floor(Math.log10(m)))
  return Math.ceil(m / p) * p
})
const H = computed(() => props.height)
const xs = computed(() => {
  const n = props.points.length
  const iw = W - PAD.l - PAD.r
  return props.points.map((_, i) => PAD.l + (n <= 1 ? iw / 2 : (i * iw) / (n - 1)))
})
const ys = computed(() => props.points.map((p) => PAD.t + (1 - p.value / max.value) * (H.value - PAD.t - PAD.b)))

function smooth(xs: number[], ys: number[]) {
  if (!xs.length) return ''
  let d = `M ${xs[0]} ${ys[0]}`
  for (let i = 1; i < xs.length; i++) {
    const cx = (xs[i - 1] + xs[i]) / 2
    d += ` C ${cx} ${ys[i - 1]}, ${cx} ${ys[i]}, ${xs[i]} ${ys[i]}`
  }
  return d
}
const line = computed(() => smooth(xs.value, ys.value))
const area = computed(() => {
  if (!xs.value.length) return ''
  const base = H.value - PAD.b
  return `${line.value} L ${xs.value[xs.value.length - 1]} ${base} L ${xs.value[0]} ${base} Z`
})
const ticks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((r) => ({ y: PAD.t + (1 - r) * (H.value - PAD.t - PAD.b), v: max.value * r })))
const labelEvery = computed(() => Math.max(1, Math.ceil(props.points.length / 8)))

function onMove(e: MouseEvent) {
  const svg = e.currentTarget as SVGSVGElement
  const r = svg.getBoundingClientRect()
  const x = ((e.clientX - r.left) / r.width) * W
  let best = 0
  xs.value.forEach((v, i) => { if (Math.abs(v - x) < Math.abs(xs.value[best] - x)) best = i })
  hover.value = props.points.length ? best : null
}
const gid = `g${Math.random().toString(36).slice(2, 8)}`
</script>

<template>
  <div class="chart">
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" :style="{ height: H + 'px' }" @mousemove="onMove" @mouseleave="hover = null">
      <defs>
        <linearGradient :id="gid" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="color" stop-opacity="0.22" />
          <stop offset="100%" :stop-color="color" stop-opacity="0.02" />
        </linearGradient>
      </defs>
      <g v-for="t in ticks" :key="t.y">
        <line :x1="PAD.l" :x2="W - PAD.r" :y1="t.y" :y2="t.y" class="grid" />
      </g>
      <path :d="area" :fill="`url(#${gid})`" />
      <path :d="line" fill="none" :stroke="color" stroke-width="2.2" vector-effect="non-scaling-stroke" />
      <template v-if="hover !== null">
        <line :x1="xs[hover]" :x2="xs[hover]" :y1="PAD.t" :y2="H - PAD.b" class="cursor" />
        <circle :cx="xs[hover]" :cy="ys[hover]" r="4.5" :fill="color" stroke="var(--bg-elev)" stroke-width="2" vector-effect="non-scaling-stroke" />
      </template>
    </svg>
    <div class="y-labels" :style="{ height: H + 'px' }">
      <span v-for="t in ticks" :key="t.y" :style="{ top: (t.y / H) * 100 + '%' }">{{ format(t.v) }}</span>
    </div>
    <div class="x-labels">
      <span v-for="(p, i) in points" v-show="i % labelEvery === 0 || i === points.length - 1" :key="i" :style="{ left: (xs[i] / W) * 100 + '%' }">{{ p.label }}</span>
    </div>
    <div v-if="hover !== null" class="tip" :style="{ left: (xs[hover] / W) * 100 + '%', top: (ys[hover] / H) * 100 + '%' }">
      <div class="tiny muted">{{ points[hover].label }}</div>
      <div class="bold mono">{{ format(points[hover].value) }}</div>
    </div>
  </div>
</template>

<style scoped>
.chart { position: relative; width: 100%; padding-bottom: 22px; }
svg { width: 100%; display: block; }
.grid { stroke: var(--divider); stroke-width: 1; vector-effect: non-scaling-stroke; }
.cursor { stroke: var(--border-strong); stroke-dasharray: 3 3; vector-effect: non-scaling-stroke; }
.y-labels { position: absolute; left: 0; top: 0; width: 44px; pointer-events: none; }
.y-labels span { position: absolute; right: 4px; transform: translateY(-50%); font-size: 10.5px; color: var(--text-4); font-family: var(--mono); }
.x-labels { position: absolute; left: 0; right: 0; bottom: 0; height: 18px; }
.x-labels span { position: absolute; transform: translateX(-50%); font-size: 10.5px; color: var(--text-4); font-family: var(--mono); white-space: nowrap; }
.tip { position: absolute; transform: translate(-50%, calc(-100% - 12px)); background: var(--bg-elev); border: 1px solid var(--border); border-radius: 8px; padding: 5px 9px; box-shadow: var(--shadow-md); pointer-events: none; white-space: nowrap; }
</style>
