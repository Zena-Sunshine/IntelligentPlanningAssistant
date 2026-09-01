import type { AuthResponse, CardEnvelope, Conversation, Message, Page, RuntimeFrameEnvelope, StreamFrame } from './types'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
  if (!response.ok) {
    let message = `请求失败 (${response.status})`
    try {
      const body = await response.json()
      message = body.message ?? body.detail ?? message
    } catch {
      // Keep the stable fallback message.
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  login: (username: string, password: string) =>
    request<AuthResponse>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),

  conversations: (token: string, query = '') =>
    request<Page<Conversation>>(`/api/v1/conversations?query=${encodeURIComponent(query)}&size=50`, {}, token),

  createConversation: (token: string) =>
    request<Conversation>('/api/v1/conversations', { method: 'POST', body: JSON.stringify({ title: '新对话' }) }, token),

  renameConversation: (token: string, id: string, title: string) =>
    request<Conversation>(`/api/v1/conversations/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) }, token),

  deleteConversation: (token: string, id: string) =>
    request<void>(`/api/v1/conversations/${id}`, { method: 'DELETE' }, token),

  messages: async (token: string, id: string): Promise<Message[]> => {
    const rows = await request<Array<Omit<Message, 'cards' | 'runtime'> & { cardsJson?: string, runtimeJson?: string }>>(
      `/api/v1/conversations/${id}/messages`, {}, token,
    )
    return rows.map((row) => {
      let cards: CardEnvelope[] = []
      if (row.cardsJson) {
        try { cards = JSON.parse(row.cardsJson) as CardEnvelope[] } catch { cards = [] }
      }
      let runtime: RuntimeFrameEnvelope[] = []
      if (row.runtimeJson) {
        try { runtime = JSON.parse(row.runtimeJson) as RuntimeFrameEnvelope[] } catch { runtime = [] }
      }
      return { ...row, cards, runtime }
    })
  },

  streamMessage: async (
    token: string,
    conversationId: string,
    content: string,
    state: Record<string, unknown>,
    onFrame: (frame: StreamFrame) => void,
    signal?: AbortSignal,
  ) => {
    const response = await fetch(`${API_BASE}/api/v1/conversations/${conversationId}/messages:stream`, {
      method: 'POST',
      signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ content, requestId: crypto.randomUUID(), state }),
    })
    if (!response.ok || !response.body) throw new ApiError(response.status, '无法建立智能服务连接')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const event = block.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim() ?? 'message'
        const data = block.split('\n').filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim()).join('\n')
        if (data) onFrame({ event, data })
        boundary = buffer.indexOf('\n\n')
      }
      if (done) break
    }
  },
}
