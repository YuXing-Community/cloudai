<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, ShieldCheck, Boxes, Activity } from 'lucide-vue-next'
import { useAuth } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const isRegister = computed(() => !!route.meta.register)

const form = ref({ account: '', username: '', email: '', displayName: '', password: '', password2: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  const f = form.value
  if (isRegister.value) {
    if (!f.username || !f.email || !f.displayName || !f.password) return (error.value = '请完整填写注册信息')
    if (f.password !== f.password2) return (error.value = '两次输入的密码不一致')
  } else if (!f.account || !f.password) return (error.value = '请输入账号和密码')
  loading.value = true
  try {
    if (isRegister.value) await auth.register({ username: f.username, email: f.email, displayName: f.displayName, password: f.password })
    else await auth.login(f.account, f.password)
    router.replace((route.query.redirect as string) || '/')
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const features = [
  { icon: Bot, title: 'Agent 定义', desc: '模型、提示词、工具、Skills 标准化封装与版本管理' },
  { icon: Boxes, title: '容器沙箱', desc: '按需创建会话与沙箱，隔离·安全·可控' },
  { icon: Activity, title: 'Session 事件流', desc: '每一步执行可观测、可追溯、可恢复' },
  { icon: ShieldCheck, title: '权限审计', desc: '空间隔离、角色权限、凭据加密存储' },
]
</script>

<template>
  <div class="auth">
    <div class="auth-hero">
      <div class="hero-brand">
        <div class="hero-logo">
          <svg viewBox="0 0 32 32" fill="none"><path d="M10.5 22h11a4.5 4.5 0 0 0 .6-8.96A6 6 0 0 0 10.4 14.6 3.7 3.7 0 0 0 10.5 22Z" stroke="#fff" stroke-width="2.2" stroke-linejoin="round" /><circle cx="23.5" cy="8.5" r="1.6" fill="#fff" /><circle cx="26.6" cy="11.6" r="1" fill="#bae6fd" /></svg>
        </div>
        <div>
          <div class="hero-name">CloudAI</div>
          <div class="hero-sub">企业级 Agent 托管平台</div>
        </div>
      </div>
      <h1 class="hero-title">把个人助理升级为<br />可托管、可复用、可治理的生产级 Agent</h1>
      <div class="hero-feats">
        <div v-for="f in features" :key="f.title" class="feat">
          <div class="feat-ico"><component :is="f.icon" /></div>
          <div>
            <div class="feat-title">{{ f.title }}</div>
            <div class="feat-desc">{{ f.desc }}</div>
          </div>
        </div>
      </div>
      <div class="hero-foot">标准化 · 可托管 · 可治理 · 可复用</div>
    </div>

    <div class="auth-panel">
      <form class="auth-card" @submit.prevent="submit">
        <h2>{{ isRegister ? '注册账号' : '登录 CloudAI' }}</h2>
        <p class="muted" style="margin: 4px 0 20px">{{ isRegister ? '注册时请登记邮箱与用户名，首位注册用户将成为系统管理员' : '使用用户名或邮箱登录' }}</p>

        <template v-if="isRegister">
          <div class="form-row">
            <div class="field"><label>用户名 <span class="req">*</span></label><input v-model="form.username" class="input" placeholder="字母开头，如 zhangsan" autocomplete="username" /></div>
            <div class="field"><label>显示名 <span class="req">*</span></label><input v-model="form.displayName" class="input" placeholder="如 张三" /></div>
          </div>
          <div class="field"><label>邮箱 <span class="req">*</span></label><input v-model="form.email" class="input" type="email" placeholder="name@company.com" autocomplete="email" /></div>
          <div class="form-row">
            <div class="field"><label>密码 <span class="req">*</span></label><input v-model="form.password" class="input" type="password" placeholder="至少 6 位" autocomplete="new-password" /></div>
            <div class="field"><label>确认密码 <span class="req">*</span></label><input v-model="form.password2" class="input" type="password" autocomplete="new-password" /></div>
          </div>
        </template>
        <template v-else>
          <div class="field"><label>用户名 / 邮箱</label><input v-model="form.account" class="input" placeholder="用户名或邮箱" autocomplete="username" autofocus /></div>
          <div class="field"><label>密码</label><input v-model="form.password" class="input" type="password" placeholder="密码" autocomplete="current-password" /></div>
        </template>

        <div v-if="error" class="notice notice-danger" style="margin-bottom: 12px">{{ error }}</div>
        <button class="btn btn-primary btn-lg btn-block" :disabled="loading" type="submit">{{ loading ? '请稍候…' : isRegister ? '注册并进入' : '登录' }}</button>
        <div class="switch">
          <template v-if="isRegister">已有账号？<RouterLink to="/login">去登录</RouterLink></template>
          <template v-else>还没有账号？<RouterLink to="/register">注册</RouterLink></template>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.auth { display: flex; min-height: 100vh; background: var(--bg); }
.auth-hero {
  flex: 1.1; padding: 48px 56px; display: flex; flex-direction: column; color: #fff; position: relative; overflow: hidden;
  background: radial-gradient(1200px 600px at -10% -20%, #3b82f6 0%, transparent 60%), radial-gradient(900px 500px at 120% 120%, #06b6d4 0%, transparent 55%), linear-gradient(135deg, #0b1b3a, #0f2a5c 55%, #0b3b5c);
}
.hero-brand { display: flex; align-items: center; gap: 12px; }
.hero-logo { width: 40px; height: 40px; border-radius: 11px; background: rgba(255,255,255,.14); display: flex; align-items: center; justify-content: center; backdrop-filter: blur(4px); border: 1px solid rgba(255,255,255,.2); }
.hero-logo svg { width: 30px; height: 30px; }
.hero-name { font-size: 18px; font-weight: 700; }
.hero-sub { font-size: 12px; opacity: .75; }
.hero-title { margin-top: auto; font-size: 30px; line-height: 1.35; font-weight: 700; letter-spacing: -.01em; }
.hero-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 32px; }
.feat { display: flex; gap: 12px; padding: 14px; border-radius: 14px; background: rgba(255,255,255,.07); border: 1px solid rgba(255,255,255,.12); backdrop-filter: blur(6px); }
.feat-ico { width: 34px; height: 34px; border-radius: 10px; background: rgba(255,255,255,.14); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.feat-ico svg { width: 18px; height: 18px; }
.feat-title { font-weight: 600; font-size: 14px; }
.feat-desc { font-size: 12px; opacity: .75; margin-top: 2px; line-height: 1.5; }
.hero-foot { margin-top: 28px; margin-bottom: auto; font-size: 12.5px; opacity: .7; letter-spacing: .1em; }
.auth-panel { flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px; }
.auth-card { width: 420px; max-width: 100%; }
.auth-card h2 { font-size: 24px; font-weight: 700; }
.switch { text-align: center; margin-top: 16px; color: var(--text-3); font-size: 13px; }
@media (max-width: 900px) { .auth-hero { display: none; } }
</style>
