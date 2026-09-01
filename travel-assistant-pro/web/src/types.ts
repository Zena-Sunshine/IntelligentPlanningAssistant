export interface User {
  id: string
  username: string
  displayName: string
  tenantId: string
  role: string
}

export interface AuthResponse {
  accessToken: string
  expiresAt: string
  user: User
}

export interface Conversation {
  id: string
  title: string
  messageCount: number
  lastMessagePreview?: string
  lastMessageAt?: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AgentCard {
  type: string
  data: Record<string, unknown>
}

export interface CardEnvelope {
  agentKey?: string
  displayName?: string
  card: AgentCard
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  agentKey?: string
  content: string
  cards: CardEnvelope[]
  intents?: string
  traceId?: string
  runtime?: RuntimeFrameEnvelope[]
  createdAt: string
  pending?: boolean
}

export interface RuntimeFrameEnvelope {
  type: string
  timestamp?: string
  data?: Record<string, unknown>
}

export interface RuntimeEvent {
  id: string
  type: string
  label: string
  status: 'running' | 'done' | 'error' | 'info'
  detail?: string
  timestamp: number
}

export interface StreamFrame {
  event: string
  data: string
}
