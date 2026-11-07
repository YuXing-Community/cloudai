import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { session } from '@/api/http'
import MainLayout from '@/layouts/MainLayout.vue'

/** meta.perm：进入页面所需权限点；meta.title：顶栏面包屑 */
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true } },
  { path: '/register', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, register: true } },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', redirect: '/sessions' },
      // Agents
      { path: 'sessions', component: () => import('@/views/session/SessionView.vue'), meta: { title: '会话', perm: 'agent:view' } },
      { path: 'sessions/:id', component: () => import('@/views/session/SessionView.vue'), meta: { title: '会话', perm: 'agent:view' } },
      { path: 'workspace', component: () => import('@/views/sandbox/WorkspaceView.vue'), meta: { title: '工作区', perm: 'sandbox:view' } },
      { path: 'agents', component: () => import('@/views/agent/AgentListView.vue'), meta: { title: '智能体', perm: 'agent:view' } },
      { path: 'agents/:id', component: () => import('@/views/agent/AgentEditView.vue'), meta: { title: '智能体', perm: 'agent:view', full: true } },
      { path: 'teams', component: () => import('@/views/team/TeamListView.vue'), meta: { title: 'Agent小队', perm: 'team:view' } },
      { path: 'taskboard', component: () => import('@/views/team/TaskBoardListView.vue'), meta: { title: '任务看板', perm: 'team:view' } },
      { path: 'taskboard/:id', component: () => import('@/views/team/TaskBoardView.vue'), meta: { title: '任务看板', perm: 'team:view' } },
      { path: 'schedules', component: () => import('@/views/schedule/ScheduleView.vue'), meta: { title: '定时任务', perm: 'schedule:view' } },
      { path: 'channels', component: () => import('@/views/channel/ChannelView.vue'), meta: { title: '消息通道', perm: 'channel:view' } },
      { path: 'files', component: () => import('@/views/file/FileCenterView.vue'), meta: { title: '文件中心', perm: 'file:view' } },
      { path: 'memory', component: () => import('@/views/memory/MemoryListView.vue'), meta: { title: '记忆库', perm: 'memory:view' } },
      { path: 'memory/:id', component: () => import('@/views/memory/MemoryDetailView.vue'), meta: { title: '记忆库', perm: 'memory:view' } },
      { path: 'vector-search', component: () => import('@/views/memory/VectorSearchView.vue'), meta: { title: '向量库查询', perm: 'memory:view' } },
      { path: 'usage', component: () => import('@/views/usage/UsageView.vue'), meta: { title: '用量', perm: 'usage:view' } },
      // 评测
      { path: 'evals', component: () => import('@/views/eval/EvalTaskListView.vue'), meta: { title: 'Agent 评测', perm: 'eval:view' } },
      { path: 'evals/:id', component: () => import('@/views/eval/EvalTaskDetailView.vue'), meta: { title: 'Agent 评测', perm: 'eval:view' } },
      { path: 'annotations', component: () => import('@/views/eval/AnnotationView.vue'), meta: { title: '人工打标', perm: 'eval:view' } },
      // Plugins
      { path: 'plugins/tools', component: () => import('@/views/plugin/ToolsView.vue'), meta: { title: '工具集成', perm: 'plugin:view' } },
      { path: 'plugins/tools/new', component: () => import('@/views/plugin/ToolCreateView.vue'), meta: { title: '工具集成', perm: 'plugin:edit' } },
      { path: 'plugins/mcp', component: () => import('@/views/plugin/ToolsView.vue'), meta: { title: 'MCP 服务', perm: 'plugin:view', mcpOnly: true } },
      { path: 'plugins/credentials', component: () => import('@/views/plugin/CredentialView.vue'), meta: { title: '凭据', perm: 'plugin:view' } },
      { path: 'plugins/skills', component: () => import('@/views/plugin/SkillListView.vue'), meta: { title: 'Skills', perm: 'plugin:view' } },
      { path: 'plugins/skills/upload', component: () => import('@/views/plugin/SkillUploadView.vue'), meta: { title: 'Skills', perm: 'plugin:edit' } },
      { path: 'plugins/hooks', component: () => import('@/views/plugin/HooksView.vue'), meta: { title: 'Hooks', perm: 'plugin:view' } },
      // 沙箱
      { path: 'sandbox', component: () => import('@/views/sandbox/SandboxView.vue'), meta: { title: 'Sandbox', perm: 'sandbox:view' } },
      { path: 'runtime-envs', component: () => import('@/views/sandbox/RuntimeEnvView.vue'), meta: { title: '运行环境', perm: 'sandbox:view' } },
      // 空间配置
      { path: 'settings', redirect: '/settings/basic' },
      { path: 'settings/:tab', component: () => import('@/views/settings/SettingsView.vue'), meta: { title: '空间配置' } },
    ],
  },
  { path: '/share/agent/:id', component: () => import('@/views/session/ShareChatView.vue'), meta: { title: '对话' } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (!to.meta.public && !session.token) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.public && session.token) return '/'
  return true
})

export default router
