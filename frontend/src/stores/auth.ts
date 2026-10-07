import { defineStore } from 'pinia'
import { http, session } from '@/api/http'

export interface User { id: number; username: string; email: string; displayName: string; avatar?: string; isAdmin?: boolean }
export interface SpaceBrief { id: string; name: string; description?: string; type: string; roleCode: string; memberCount: number }

export const useAuth = defineStore('auth', {
  state: () => ({
    user: null as User | null,
    spaces: [] as SpaceBrief[],
    spaceId: session.spaceId,
    perms: [] as string[],
    roleCode: '' as string,
    isSystemAdmin: false,
    ready: false,
  }),
  getters: {
    loggedIn: () => !!session.token,
    space(state): SpaceBrief | undefined { return state.spaces.find((s) => s.id === state.spaceId) },
  },
  actions: {
    can(perm: string) { return this.perms.includes(perm) },
    async login(account: string, password: string) {
      const r = await http.post<{ token: string; user: User }>('/auth/login', { account, password })
      session.token = r.token
      this.user = r.user
      await this.bootstrap()
    },
    async register(data: { username: string; email: string; displayName: string; password: string }) {
      const r = await http.post<{ token: string; user: User }>('/auth/register', data)
      session.token = r.token
      this.user = r.user
      await this.bootstrap()
    },
    logout() {
      session.token = ''
      session.spaceId = ''
      this.user = null
      this.spaces = []
      this.spaceId = ''
      this.perms = []
      this.ready = false
    },
    /** 登录后加载用户、空间列表并确定当前空间 */
    async bootstrap() {
      if (!this.user) this.user = await http.get<User>('/auth/me')
      await this.loadSpaces()
      this.spaceId = session.spaceId
      if (!this.spaces.find((s) => s.id === this.spaceId)) this.spaceId = this.spaces[0]?.id || ''
      session.spaceId = this.spaceId
      await this.loadPerms()
      this.ready = true
    },
    async loadSpaces() {
      this.spaces = await http.get<SpaceBrief[]>('/spaces')
    },
    /** 当前空间被退出/删除后：重新加载空间列表并切到第一个可用空间 */
    async leaveCurrentSpace() {
      session.spaceId = ''
      this.spaceId = ''
      await this.loadSpaces()
      await this.switchSpace(this.spaces[0]?.id || '')
    },
    async loadPerms() {
      if (!this.spaceId) return
      const r = await http.get<{ perms: string[]; roleCode: string; isSystemAdmin: boolean }>('/space')
      this.perms = r.perms || []
      this.roleCode = r.roleCode
      this.isSystemAdmin = r.isSystemAdmin
    },
    async switchSpace(id: string) {
      this.spaceId = id
      session.spaceId = id
      await this.loadPerms()
    },
  },
})
