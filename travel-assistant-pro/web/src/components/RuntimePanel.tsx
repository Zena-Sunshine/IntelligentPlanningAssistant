import type { ReactNode } from 'react'
import { Activity, BrainCircuit, ChevronLeft, CircleCheck, CircleDot, FileCheck2, ListChecks, PanelRightClose, Route, Timer, Wrench } from 'lucide-react'
import type { RuntimeEvent } from '../types'

export function RuntimePanel({ events, collapsed, onToggle }: { events: RuntimeEvent[], collapsed: boolean, onToggle: () => void }) {
  if (collapsed) return <button type="button" className="rail-reopen" onClick={onToggle} title="展开运行详情"><ChevronLeft size={19}/><Activity size={19}/></button>
  const routeEvents = events.filter((event) => event.type === 'route' || event.type === 'thinking' || event.type === 'thinking_start')
  const plans = events.filter((event) => event.type === 'plan')
  const agents = events.filter((event) => event.type.startsWith('agent_'))
  const tools = events.filter((event) => event.type.startsWith('tool_'))
  const compositions = events.filter((event) => event.type === 'composition' || event.type === 'composition_start')
  const traces = events.filter((event) => event.type === 'trace' || event.type === 'error')
  return <aside className="runtime-rail">
    <header><div><span className="live-dot"/> Agent 运行详情</div><button type="button" onClick={onToggle} title="收起运行详情"><PanelRightClose size={18}/></button></header>
    {events.length === 0 ? <div className="runtime-empty"><BrainCircuit/><strong>等待任务</strong><p>路由、专业 Agent 和工具执行状态将在这里呈现。</p></div> : <div className="runtime-groups">
      <RuntimeGroup title="语义调度" icon={<Route size={16}/>} events={routeEvents} open/>
      <RuntimeGroup title="执行计划" icon={<ListChecks size={16}/>} events={plans} open/>
      <RuntimeGroup title="协作任务" icon={<BrainCircuit size={16}/>} events={agents} open/>
      <RuntimeGroup title="工具调用" icon={<Wrench size={16}/>} events={tools}/>
      <RuntimeGroup title="结果汇总" icon={<FileCheck2 size={16}/>} events={compositions}/>
      <RuntimeGroup title="链路指标" icon={<Timer size={16}/>} events={traces}/>
    </div>}
  </aside>
}

function RuntimeGroup({ title, icon, events, open = false }: { title: string, icon: ReactNode, events: RuntimeEvent[], open?: boolean }) {
  return <details className="runtime-group" open={open}><summary>{icon}<span>{title}</span><b>{events.length}</b></summary>
    <div className="runtime-items">{events.length === 0 ? <p className="muted-item">暂无记录</p> : events.map((event) => <article key={event.id} className={event.status}>
      {event.status === 'done' ? <CircleCheck size={15}/> : <CircleDot size={15}/>}<div><strong>{event.label}</strong>{event.detail && <p>{event.detail}</p>}</div>
    </article>)}</div>
  </details>
}
