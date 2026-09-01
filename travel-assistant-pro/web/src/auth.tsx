import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { api } from './api'
import type { AuthResponse, User } from './types'

interface AuthState {
  token: string
  user: User
}

interface AuthContextValue extends Partial<AuthState> {
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const STORAGE_KEY = 'voyageiq.auth.v1'
const AuthContext = createContext<AuthContextValue | null>(null)

function restore(): AuthState | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as AuthResponse
    if (new Date(parsed.expiresAt).getTime() <= Date.now()) return null
    return { token: parsed.accessToken, user: parsed.user }
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState | null>(() => restore())
  const value = useMemo<AuthContextValue>(() => ({
    ...state,
    login: async (username, password) => {
      const response = await api.login(username, password)
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(response))
      setState({ token: response.accessToken, user: response.user })
    },
    logout: () => {
      sessionStorage.removeItem(STORAGE_KEY)
      setState(null)
    },
  }), [state])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

