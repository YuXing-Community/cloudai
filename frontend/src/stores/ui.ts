import { ref, watch } from 'vue'

const KEY = 'cloudai.theme'
export const theme = ref<'light' | 'dark'>((localStorage.getItem(KEY) as any) || 'light')
export const sidebarCollapsed = ref(localStorage.getItem('cloudai.sidebar') === '1')

function apply() {
  document.documentElement.setAttribute('data-theme', theme.value)
}
apply()
watch(theme, (v) => { localStorage.setItem(KEY, v); apply() })
watch(sidebarCollapsed, (v) => localStorage.setItem('cloudai.sidebar', v ? '1' : '0'))

export function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}
