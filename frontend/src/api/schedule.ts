import { http, Page } from './http'

export const scheduleApi = {
  list: (q?: string, status?: string) => http.get<any[]>('/schedules', { q, status }),
  create: (data: any) => http.post('/schedules', data),
  update: (id: number, data: any) => http.put(`/schedules/${id}`, data),
  remove: (id: number) => http.del(`/schedules/${id}`),
  enable: (id: number, enabled: boolean) => http.put(`/schedules/${id}/enabled`, { enabled }),
  trigger: (id: number) => http.post(`/schedules/${id}/trigger`),
  runs: (id: number, onlyUnfinished: boolean, page = 1) => http.get<Page<any>>(`/schedules/${id}/runs`, { onlyUnfinished, page, size: 20 }),
  preview: (cron: string, timezone = 'Asia/Shanghai') => http.post<string[]>('/schedules/preview', { cron, timezone }, { silent: true }),
}

export const channelApi = {
  list: (type: string) => http.get<{ records: any[]; counts: Record<string, number>; active: number }>('/channels', { type }),
  options: () => http.get<any[]>('/channels/options'),
  create: (data: any) => http.post('/channels', data),
  update: (id: string, data: any) => http.put(`/channels/${id}`, data),
  setStatus: (id: string, status: string) => http.put(`/channels/${id}/status`, { status }),
  remove: (id: string) => http.del(`/channels/${id}`),
}

export const RUN_STATUS: Record<string, { label: string; tone: string }> = {
  success: { label: '成功', tone: 'tag-green' },
  failed: { label: '失败', tone: 'tag-red' },
  skipped: { label: '已跳过', tone: 'tag-orange' },
  running: { label: '执行中', tone: 'tag-blue' },
}

export const FEISHU_SETTINGS = [
  { key: 'requireMention', label: '需要 @ 机器人', desc: '开启后，群聊消息须 @ 机器人才会触发回复' },
  { key: 'filterAtAll', label: '过滤 @所有人消息', desc: '开启后，仅 @所有人且未显式 @机器人的消息不会触发回复' },
  { key: 'topicSession', label: '话题独立会话', desc: '仅对飞书话题群生效；开启后每个话题会创建独立 Session' },
  { key: 'showFooter', label: '显示回复脚注', desc: '在回复卡片底部显示模型、耗时和 Token 用量' },
  { key: 'finalOnly', label: '仅发送最终回复', desc: '执行期间不发送中间内容，会话结束后一次性回复' },
  { key: 'showTyping', label: '显示处理中表情', desc: '收到消息后，在原消息上显示敲键盘表情' },
  { key: 'replyInThread', label: '以话题形式回复', desc: '开启后，普通群聊中的回复会在触发消息下创建话题' },
]
