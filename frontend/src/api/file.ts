import { http, authedUrl, Page } from './http'

export const fileApi = {
  list: (q: Record<string, any>) => http.get<Page<any>>('/files', q),
  upload: (file: File, name?: string, source = 'manual') => {
    const fd = new FormData()
    fd.append('file', file)
    if (name) fd.append('name', name)
    fd.append('source', source)
    return http.upload<any>('/files', fd)
  },
  text: (id: string) => http.get<{ name: string; mimeType: string; content: string }>(`/files/${id}/text`),
  remove: (id: string) => http.del(`/files/${id}`),
  url: (id: string, inline = false) => authedUrl(`/files/${id}/download`, inline ? { inline: 1 } : {}),
}

export const SOURCE_TAG: Record<string, string> = { chat: '聊天附件', manual: '手动上传', agent: 'Agent 产出' }
