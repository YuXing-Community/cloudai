<script setup lang="ts">
import { ref, watch } from 'vue'
import { CheckCircle2, XCircle, Info, AlertTriangle } from 'lucide-vue-next'
import { toastState, confirmState } from './toast'
import Modal from './Modal.vue'

const typed = ref('')
watch(() => confirmState.open, (v) => { if (v) typed.value = '' })

function close(v: boolean) {
  confirmState.open = false
  confirmState.resolve?.(v)
  confirmState.resolve = null
}
</script>

<template>
  <div class="toast-stack">
    <TransitionGroup name="toast">
      <div v-for="t in toastState.items" :key="t.id" class="toast" :class="t.kind">
        <CheckCircle2 v-if="t.kind === 'success'" />
        <XCircle v-else-if="t.kind === 'error'" />
        <AlertTriangle v-else-if="t.kind === 'warning'" />
        <Info v-else />
        <span>{{ t.text }}</span>
      </div>
    </TransitionGroup>
  </div>

  <Modal :open="confirmState.open" :title="confirmState.opts?.title" width="420px" @close="close(false)">
    <p v-if="confirmState.opts?.message" class="confirm-msg">{{ confirmState.opts.message }}</p>
    <div v-if="confirmState.opts?.requireText" class="field" style="margin-top: 12px">
      <label>请输入 <b>{{ confirmState.opts.requireText }}</b> 以确认</label>
      <input v-model="typed" class="input" :placeholder="confirmState.opts.requireText" />
    </div>
    <template #footer>
      <button class="btn" @click="close(false)">{{ confirmState.opts?.cancelText || '取消' }}</button>
      <button
        class="btn"
        :class="confirmState.opts?.danger ? 'btn-danger' : 'btn-primary'"
        :disabled="!!confirmState.opts?.requireText && typed !== confirmState.opts.requireText"
        @click="close(true)"
      >
        {{ confirmState.opts?.okText || '确定' }}
      </button>
    </template>
  </Modal>
</template>

<style scoped>
.toast-stack { position: fixed; top: 16px; left: 50%; transform: translateX(-50%); z-index: 3000; display: flex; flex-direction: column; gap: 8px; align-items: center; pointer-events: none; }
.toast {
  display: flex; align-items: center; gap: 8px; padding: 9px 14px; border-radius: 10px;
  background: var(--bg-elev); border: 1px solid var(--border); box-shadow: var(--shadow-md);
  font-size: 13px; color: var(--text); max-width: 560px; pointer-events: auto;
}
.toast svg { width: 16px; height: 16px; flex-shrink: 0; }
.toast.success svg { color: var(--green); }
.toast.error svg { color: var(--red); }
.toast.warning svg { color: var(--orange); }
.toast.info svg { color: var(--primary); }
.toast-enter-active, .toast-leave-active { transition: all .2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-8px); }
.confirm-msg { color: var(--text-2); line-height: 1.6; white-space: pre-wrap; }
</style>
