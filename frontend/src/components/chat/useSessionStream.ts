import { onBeforeUnmount, ref, Ref, watch } from 'vue'
import type { SessionEvent, SessionRow } from '@/api/session'

type Streamer = (id: string, after: number, handlers: Record<string, (d: any) => void>) => EventSource

/**
 * 订阅会话事件流：首次连接后端会补发全部历史事件，之后实时推送。
 * 同一工具调用会先后收到 running → ok/error 两次推送（同 id），这里按 id 合并。
 */
export function useSessionStream(sessionId: Ref<string | null | undefined>, streamer: Streamer) {
  const events = ref<SessionEvent[]>([])
  const session = ref<SessionRow | null>(null)
  const connected = ref(false)
  const loading = ref(false)
  let es: EventSource | null = null
  let retry: any = null

  function upsert(ev: SessionEvent) {
    const i = events.value.findIndex((e) => e.id === ev.id)
    if (i >= 0) events.value.splice(i, 1, ev)
    else {
      events.value.push(ev)
      if (events.value.length > 1 && events.value[events.value.length - 2].seq > ev.seq) {
        events.value.sort((a, b) => a.seq - b.seq)
      }
    }
  }

  function close() {
    clearTimeout(retry)
    es?.close()
    es = null
    connected.value = false
  }

  function connect(id: string, after = 0) {
    close()
    es = streamer(id, after, {
      event: (ev: SessionEvent) => upsert(ev),
      session: (s: SessionRow) => {
        session.value = { ...(session.value || {}), ...s } as SessionRow
        loading.value = false
      },
    })
    es.onopen = () => { connected.value = true }
    es.onerror = () => {
      connected.value = false
      loading.value = false
      // 断线重连：从最后一个 seq 继续
      if (es && es.readyState === EventSource.CLOSED) {
        retry = setTimeout(() => {
          if (sessionId.value === id) connect(id, lastSeq())
        }, 3000)
      }
    }
  }

  function lastSeq() {
    // 工具事件会原地更新，重连时从头补发更稳妥
    return 0
  }

  watch(sessionId, (id) => {
    events.value = []
    session.value = null
    if (id) {
      loading.value = true
      connect(id, 0)
    } else close()
  }, { immediate: true })

  onBeforeUnmount(close)

  return { events, session, connected, loading, reconnect: () => sessionId.value && connect(sessionId.value, 0), close }
}
