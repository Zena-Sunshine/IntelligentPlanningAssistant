import { useState, type FormEvent } from 'react'
import { ArrowRight, Building2, KeyRound, LockKeyhole, Route, ShieldCheck, Sparkles, UserRound } from 'lucide-react'
import { useAuth } from '../auth'

export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('voyage')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setLoading(true)
    try {
      await login(username, password)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return <main className="login-shell">
    <section className="login-story">
      <div className="brand brand-large"><span className="brand-mark"><Route size={25}/></span><span>VoyageIQ</span></div>
      <div className="story-content">
        <p className="eyebrow"><Building2 size={15}/> 企业差旅智能中枢</p>
        <h1>让每一次出差决策<br/><span>更快、更准、更合规</span></h1>
        <p className="story-copy">把行程规划、企业政策、费用标准和审批协作汇入同一个智能工作台。</p>
        <div className="feature-row">
          <div><Sparkles/><strong>多智能体协作</strong><span>复杂任务自动拆分与汇总</span></div>
          <div><ShieldCheck/><strong>企业级边界</strong><span>身份、租户与业务数据隔离</span></div>
          <div><LockKeyhole/><strong>全链路可追踪</strong><span>路由、工具和结果清晰可见</span></div>
        </div>
      </div>
      <p className="login-footnote">VoyageIQ · Intelligent Business Travel Operations</p>
    </section>
    <section className="login-panel">
      <form className="login-card" onSubmit={submit}>
        <span className="secure-chip"><KeyRound size={14}/> 安全访问</span>
        <h2>欢迎回来</h2>
        <p>登录企业账号，继续处理差旅事项。</p>
        <label>账号<div className="input-with-icon"><UserRound size={18}/><input aria-label="账号" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username"/></div></label>
        <label>密码<div className="input-with-icon"><LockKeyhole size={18}/><input aria-label="密码" value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete="current-password" placeholder="请输入密码"/></div></label>
        {error && <div className="form-error" role="alert">{error}</div>}
        <button className="primary-button login-button" disabled={loading || !username || !password}>
          {loading ? '正在验证…' : <>进入工作台 <ArrowRight size={18}/></>}
        </button>
        <p className="privacy-note">账号由企业管理员统一开通和授权</p>
      </form>
    </section>
  </main>
}

