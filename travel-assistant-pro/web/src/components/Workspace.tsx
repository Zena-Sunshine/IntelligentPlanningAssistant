import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import {
  Bot, Check, CircleUserRound, Clock3, LogOut, Menu, MessageSquareText,
  MoreHorizontal, PanelLeftClose, Pencil, Plus, Route, Search, Send, Sparkles,
  Square, Trash2, X,
} from 'lucide-react'
import { api } from '../api'
import { useAuth } from '../auth'
import type { CardEnvelope, Conversation, Message, RuntimeEvent, RuntimeFrameEnvelope, StreamFrame } from '../types'
import { CardRenderer } from './CardRenderer'
import { RuntimePanel } from './RuntimePanel'

const QUICK_PROMPTS = [
  '帮我规划明天杭州到上海的差旅行程',
  '后天去北京，查机票酒店并核对住宿标准',
  '差旅报销需要准备哪些材料？',
  '帮我查询当前出差申请的审批进度',
]

export function Workspace() {
  const { token = '', user, logout } = useAuth()
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [messageLoading, setMessageLoading] = useState(false)
  const [query, setQuery] = useState('')
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [runtimeEvents, setRuntimeEvents] = useState<RuntimeEvent[]>([])
  const [modelChip, setModelChip] = useState('离线协作')
  const [rightCollapsed, setRightCollapsed] = useState(false)
  const [leftOpen, setLeftOpen] = useState(true)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [conversationActionId, setConversationActionId] = useState<string | null>(null)
  const [error, setError] = useState('')
  const abortRef = useRef<AbortController | null>(null)
  const selectedIdRef = useRef<string | null>(null)
  const skipHistoryLoadRef = useRef<string | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickToBottomRef = useRef(true)
  const currentConversation = conversations.find((item) => item.id === selectedId)

  const loadConversations = useCallback(async (search = '') => {
    const page = await api.conversations(token, search)
    setConversations(page.items)
    return page.items
  }, [token])

  useEffect(() => {
    let active = true
    setLoading(true)
    loadConversations('').then((items) => {
      if (!active) return
      if (items[0]) setSelectedId((current) => {
        const next = current ?? items[0].id
        selectedIdRef.current = next
        return next
      })
    }).catch((reason) => setError(reason.message)).finally(() => setLoading(false))
    return () => { active = false }
  }, [loadConversations])

  useEffect(() => {
    if (!selectedId) { setMessages([]); setRuntimeEvents([]); setMessageLoading(false); return }
    if (skipHistoryLoadRef.current === selectedId) {
      skipHistoryLoadRef.current = null
      setMessageLoading(false)
      return
    }
    let active = true
    setMessages([])
    setRuntimeEvents([])
    setMessageLoading(true)
    stickToBottomRef.current = true
    api.messages(token, selectedId).then((items) => {
      if (!active) return
      setMessages(items)
      const latestRuntime = [...items].reverse().find((message) => message.role === 'assistant' && message.runtime?.length)?.runtime ?? []
      setRuntimeEvents(replayRuntime(latestRuntime))
      const session = latestRuntime.find((frame) => frame.type === 'session')
      if (session?.data) setModelChip(providerLabel(String(session.data.provider ?? ''), String(session.data.model ?? '')))
    })
      .catch((reason) => setError(reason.message))
      .finally(() => { if (active) setMessageLoading(false) })
    return () => { active = false }
  }, [selectedId, token])

  useEffect(() => {
    if (!stickToBottomRef.current) return
    const frame = window.requestAnimationFrame(() => {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'auto' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [messages])

  useEffect(() => {
    const handle = window.setTimeout(() => loadConversations(query).catch(() => undefined), 250)
    return () => window.clearTimeout(handle)
  }, [query, loadConversations])

  async function createConversation() {
    if (conversationActionId) return
    abortRef.current?.abort()
    setConversationActionId('create')
    try {
      const created = await api.createConversation(token)
      skipHistoryLoadRef.current = created.id
      selectedIdRef.current = created.id
      setConversations((items) => [created, ...items])
      setSelectedId(created.id)
      setMessages([])
      setRuntimeEvents([])
      setError('')
      stickToBottomRef.current = true
      if (window.innerWidth < 900) setLeftOpen(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '新建对话失败，请稍后重试')
    } finally {
      setConversationActionId(null)
    }
  }

  async function deleteConversation(id: string) {
    if (!window.confirm('删除后该对话将从工作台移除，确认继续吗？')) return
    setConversationActionId(id)
    try {
      await api.deleteConversation(token, id)
      const next = conversations.filter((item) => item.id !== id)
      setConversations(next)
      setRenamingId((current) => current === id ? null : current)
      if (selectedId === id) {
        abortRef.current?.abort()
        const nextId = next[0]?.id ?? null
        selectedIdRef.current = nextId
        setSelectedId(nextId)
        setMessages([])
        setRuntimeEvents([])
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '删除对话失败，请稍后重试')
    } finally {
      setConversationActionId(null)
    }
  }

  async function submitRename(id: string) {
    if (!renameValue.trim()) return
    setConversationActionId(id)
    try {
      const updated = await api.renameConversation(token, id, renameValue.trim())
      setConversations((items) => items.map((item) => item.id === id ? updated : item))
      setRenamingId(null)
      setError('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '重命名失败，请稍后重试')
    } finally {
      setConversationActionId(null)
    }
  }

  async function send(event?: FormEvent) {
    event?.preventDefault()
    const content = input.trim()
    if (!content || sending || messageLoading) return
    setError('')
    setInput('')
    setRuntimeEvents([])
    let conversationId = selectedId
    if (!conversationId) {
      const created = await api.createConversation(token)
      skipHistoryLoadRef.current = created.id
      selectedIdRef.current = created.id
      setConversations((items) => [created, ...items])
      setSelectedId(created.id)
      conversationId = created.id
    }
    const userMessage: Message = {
      id: crypto.randomUUID(), role: 'user', content, cards: [], createdAt: new Date().toISOString(),
    }
    const assistantId = crypto.randomUUID()
    const assistantMessage: Message = {
      id: assistantId, role: 'assistant', agentKey: 'response_composer', content: '', cards: [],
      createdAt: new Date().toISOString(), pending: true,
    }
    setMessages((items) => [...items, userMessage, assistantMessage])
    stickToBottomRef.current = true
    setSending(true)
    const controller = new AbortController()
    abortRef.current = controller
    try {
      await api.streamMessage(token, conversationId, content, {},
        (frame) => handleFrame(frame, assistantId, conversationId), controller.signal)
      await loadConversations('')
    } catch (reason) {
      if ((reason as Error).name !== 'AbortError') {
        const message = reason instanceof Error ? reason.message : '处理失败，请稍后重试'
        setError(message)
        setMessages((items) => items.map((item) => item.id === assistantId
          ? { ...item, pending: false, content: item.content || '本次处理没有完成，请稍后重新发送。' } : item))
      }
    } finally {
      setSending(false)
      abortRef.current = null
    }
  }

  function handleFrame(frame: StreamFrame, assistantId: string, conversationId: string) {
    if (selectedIdRef.current !== conversationId) return
    let envelope: Record<string, any>
    try { envelope = JSON.parse(frame.data) } catch { return }
    const data = (envelope.data ?? {}) as Record<string, any>
    const eventType = envelope.type ?? frame.event
    if (eventType === 'session') {
      setModelChip(providerLabel(data.provider, data.model))
      return
    }
    if (eventType === 'text') {
      setMessages((items) => items.map((item) => item.id === assistantId
        ? { ...item, content: item.content + String(data.delta ?? '') } : item))
      return
    }
    if (eventType === 'card') {
      const card = data as CardEnvelope
      setMessages((items) => items.map((item) => item.id === assistantId
        ? { ...item, cards: [...item.cards, card] } : item))
      return
    }
    if (eventType === 'done') {
      setMessages((items) => items.map((item) => item.id === assistantId
        ? { ...item, pending: false, content: item.content || String(data.answer ?? ''), traceId: envelope.trace_id } : item))
      return
    }
    updateRuntime(eventType, data)
  }

  function updateRuntime(type: string, data: Record<string, any>) {
    setRuntimeEvents((items) => applyRuntimeFrame(items, type, data))
  }

  const empty = messages.length === 0
  const greetingName = user?.displayName ?? '你好'
  const sortedConversations = useMemo(() => conversations, [conversations])

  function selectConversation(id: string) {
    if (id === selectedIdRef.current) return
    abortRef.current?.abort()
    selectedIdRef.current = id
    setSelectedId(id)
    setMessages([])
    setRuntimeEvents([])
    setMessageLoading(true)
    stickToBottomRef.current = true
    if (window.innerWidth < 900) setLeftOpen(false)
  }

  return <main className={`workspace ${rightCollapsed ? 'right-collapsed' : ''} ${leftOpen ? '' : 'left-collapsed'}`}>
    <aside className={`conversation-rail ${leftOpen ? 'open' : ''}`}>
      <header className="workspace-brand"><div className="brand"><span className="brand-mark"><Route size={21}/></span><span>VoyageIQ<small>企业差旅智能中枢</small></span></div><button type="button" className="icon-button desktop-only" title="收起对话列表" onClick={() => setLeftOpen(false)}><PanelLeftClose size={18}/></button></header>
      <button type="button" className="new-chat" disabled={conversationActionId === 'create'} onClick={createConversation}><Plus size={18}/> {conversationActionId === 'create' ? '正在新建…' : '新建对话'}</button>
      <div className="search-box"><Search size={16}/><input aria-label="搜索对话" placeholder="搜索对话" value={query} onChange={(event) => setQuery(event.target.value)}/>{query && <button type="button" title="清空搜索" onClick={() => setQuery('')}><X size={14}/></button>}</div>
      <div className="conversation-label"><span>最近对话</span><small>{conversations.length}</small></div>
      <nav className="conversation-list" aria-label="对话列表">
        {loading ? <div className="sidebar-loading">正在载入…</div> : sortedConversations.length === 0 ? <div className="sidebar-empty"><MessageSquareText/><span>还没有对话</span></div> : sortedConversations.map((conversation) => <article className={`conversation-item ${selectedId === conversation.id ? 'active' : ''} ${renamingId === conversation.id ? 'renaming' : ''}`} key={conversation.id} onClick={() => { if (renamingId === conversation.id) return; selectConversation(conversation.id) }}>
          <MessageSquareText size={17}/><div className="conversation-copy">{renamingId === conversation.id ? <form onClick={(event) => event.stopPropagation()} onSubmit={(event) => { event.preventDefault(); submitRename(conversation.id) }}><input aria-label="对话名称" autoFocus maxLength={80} value={renameValue} onChange={(event) => setRenameValue(event.target.value)} onKeyDown={(event) => { if (event.key === 'Escape') setRenamingId(null) }}/><button type="submit" title="保存重命名" disabled={!renameValue.trim() || conversationActionId === conversation.id}><Check size={14}/></button><button type="button" title="取消重命名" onClick={() => setRenamingId(null)}><X size={14}/></button></form> : <><strong>{conversation.title}</strong><span>{conversation.lastMessagePreview || '准备开始新的差旅事项'}</span></>}</div>
          <div className="conversation-actions"><button type="button" title="重命名" disabled={conversationActionId === conversation.id} onClick={(event) => { event.stopPropagation(); setRenamingId(conversation.id); setRenameValue(conversation.title) }}><Pencil size={14}/></button><button type="button" title="删除" disabled={conversationActionId === conversation.id} onClick={(event) => { event.stopPropagation(); deleteConversation(conversation.id) }}><Trash2 size={14}/></button></div>
        </article>)}
      </nav>
      <footer className="account-card"><span className="account-avatar"><CircleUserRound size={20}/></span><div><strong>{user?.displayName}</strong><small>{user?.username}</small></div><button title="退出登录" onClick={logout}><LogOut size={17}/></button></footer>
    </aside>

    <section className="chat-stage">
      <header className="chat-header"><div className="header-left"><button type="button" className="icon-button" title={leftOpen ? '收起对话列表' : '展开对话列表'} onClick={() => setLeftOpen((value) => !value)}><Menu size={19}/></button><span className="assistant-avatar"><Sparkles size={19}/></span><div><strong>{currentConversation?.title ?? '差旅智能工作台'}</strong><small><span className="online-dot"/> 多智能体服务在线</small></div></div><span className="model-chip" aria-label={`当前模型：${modelChip}`}><Bot size={15}/> {modelChip}</span></header>
      <div className="message-viewport" ref={scrollRef} onScroll={(event) => { const element = event.currentTarget; stickToBottomRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 72 }}>
        {messageLoading ? <section className="welcome-state"><span>正在载入当前对话记录…</span></section> : empty ? <section className="welcome-state"><span className="welcome-icon"><Sparkles size={30}/></span><p>上午好，{greetingName}</p><h1>今天需要处理什么差旅事项？</h1><span>可以一次提出多个目标，我会拆分任务并完整汇总结果。</span></section> : <div className="message-list">{messages.map((message) => <MessageBubble message={message} key={message.id}/>)}</div>}
      </div>
      {error && <div className="workspace-error"><span>{error}</span><button onClick={() => setError('')}><X size={15}/></button></div>}
      <form className="composer-wrap" onSubmit={send}>
        <div className="composer-main"><textarea aria-label="输入差旅事项" rows={3} disabled={messageLoading} value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send() } }} placeholder={messageLoading ? '正在载入当前对话…' : '描述你的差旅目标，例如行程、政策或审批事项…'}/><div className="composer-footer"><span>Enter 发送 · Shift + Enter 换行</span>{sending ? <button type="button" className="send-button stop" onClick={() => abortRef.current?.abort()} title="停止生成"><Square size={16}/></button> : <button className="send-button" disabled={!input.trim() || messageLoading} title="发送"><Send size={17}/></button>}</div></div>
        <aside className="prompt-side"><strong><Sparkles size={14}/> 试试这样问</strong>{QUICK_PROMPTS.map((prompt) => <button type="button" key={prompt} onClick={() => setInput(prompt)}>{prompt}</button>)}</aside>
      </form>
    </section>
    <RuntimePanel events={runtimeEvents} collapsed={rightCollapsed} onToggle={() => setRightCollapsed((value) => !value)}/>
  </main>
}

function MessageBubble({ message }: { message: Message }) {
  const assistant = message.role === 'assistant'
  return <article className={`message ${message.role} ${message.cards.length > 0 ? 'has-cards' : ''}`}>
    <span className={`message-avatar ${message.role}`}>{assistant ? <Sparkles size={19}/> : <CircleUserRound size={20}/>}</span>
    <div className="message-body"><header><strong>{assistant ? 'VoyageIQ 助手' : '你'}</strong><time><Clock3 size={12}/>{new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time></header>
      <div className="message-text">{message.content || (message.pending ? <span className="typing"><i/><i/><i/></span> : '')}</div>
      {message.cards.map((card, index) => <CardRenderer envelope={card} key={`${card.card.type}-${index}`}/>)}
      {message.pending && message.content && <span className="streaming-cursor"/>}
    </div>
  </article>
}

function providerLabel(provider?: string, model?: string) {
  if (provider === 'dashscope') return model ? `通义 ${model}` : '通义千问'
  if (provider === 'openai') return model ? `OpenAI ${model}` : 'OpenAI'
  return '离线协作'
}

function formatTool(name: string) {
  const labels: Record<string, string> = {
    flight_search: '查询航班', hotel_search: '查询酒店', weather_query: '查询天气',
    policy_search: '检索企业政策', approval_create: '创建出差申请', approval_status: '查询审批状态',
  }
  return labels[name] ?? name
}

function replayRuntime(frames: RuntimeFrameEnvelope[]): RuntimeEvent[] {
  return frames.reduce((items, frame) => applyRuntimeFrame(
    items, frame.type, (frame.data ?? {}) as Record<string, any>,
    frame.timestamp ? Date.parse(frame.timestamp) : Date.now(),
  ), [] as RuntimeEvent[])
}

function applyRuntimeFrame(items: RuntimeEvent[], type: string, data: Record<string, any>, timestamp = Date.now()): RuntimeEvent[] {
  if (type === 'agent_end') {
    const key = `agent:${data.agent_key}`
    return items.map((item) => item.id === key
      ? { ...item, status: data.success ? 'done' : 'error', detail: `${Math.round(data.duration_ms ?? 0)} ms · ${data.success ? '执行完成' : '执行失败'}` } : item)
  }
  const event: RuntimeEvent | null = type === 'thinking_start' ? {
    id: 'thinking', type, label: data.displayName ?? '模型正在分析', status: 'running',
    detail: data.summary, timestamp,
  } : type === 'route' ? {
    id: 'route', type, label: `识别 ${data.intents?.length ?? 0} 项业务意图`, status: 'done',
    detail: `${data.lane === 'fast' ? '高置信快车道' : data.lane === 'context' ? '上下文续问路由' : data.lane === 'llm' ? '大模型语义分类' : '语义组合路由'} · 置信度 ${Math.round((data.confidence ?? 0) * 100)}%`, timestamp,
  } : type === 'thinking' ? {
    id: 'thinking', type, label: '公开判断依据', status: 'info',
    detail: [data.summary, data.evidence].filter(Boolean).join('；'), timestamp,
  } : type === 'plan' ? {
    id: 'plan', type, label: data.displayName ?? '执行计划', status: 'info',
    detail: `${data.summary ?? ''}${Array.isArray(data.steps) ? ` ${data.steps.map((step: any) => `${step.displayName}：${step.objective}`).join('；')}` : ''}`.trim(), timestamp,
  } : type === 'agent_start' ? {
    id: `agent:${data.key}`, type, label: data.display_name ?? '专业 Agent', status: 'running',
    detail: '任务已接收，正在处理', timestamp,
  } : type === 'tool_end' ? {
    id: `tool:${data.agentKey}:${data.toolName}`, type, label: formatTool(data.toolName),
    status: data.status === 'ok' ? 'done' : 'error', detail: `${data.agentKey} · ${data.status === 'ok' ? '调用成功' : '调用失败'}`, timestamp,
  } : type === 'composition_start' ? {
    id: 'composition', type, label: data.displayName ?? '回答生成', status: 'running',
    detail: data.summary, timestamp,
  } : type === 'composition' ? {
    id: 'composition', type, label: data.displayName ?? '结果汇总', status: 'done',
    detail: data.summary, timestamp,
  } : type === 'trace' ? {
    id: 'trace', type, label: '本轮执行完成', status: 'done',
    detail: `${data.agentCount ?? 0} 个 Agent · ${Math.round(data.elapsedMs ?? 0)} ms`, timestamp,
  } : type === 'error' ? {
    id: `error:${timestamp}`, type, label: '执行异常', status: 'error',
    detail: data.message ?? '服务暂时不可用', timestamp,
  } : null
  return event ? [...items.filter((item) => item.id !== event.id), event] : items
}
