import { Building2, CheckCircle2, CloudSun, FileCheck2, Plane, ReceiptText } from 'lucide-react'
import type { CardEnvelope } from '../types'

function money(value: unknown) {
  const amount = Number(value)
  return Number.isFinite(amount) ? `¥${amount}` : '—'
}

export function CardRenderer({ envelope }: { envelope: CardEnvelope }) {
  const { type, data } = envelope.card
  if (type === 'flight') {
    const items = (data.items ?? []) as Array<Record<string, unknown>>
    return <section className="business-card"><header><Plane/><span>航班候选</span><small>{String(data.route ?? '')} · {String(data.date ?? '')}</small></header>
      <div className="data-table"><div className="data-row data-head"><span>航班</span><span>起飞</span><span>到达</span><span>舱位</span><span>价格</span></div>
        {items.map((item, index) => <div className="data-row" key={index}><strong>{String(item.flightNo)}</strong><span>{String(item.departureTime)}</span><span>{String(item.arrivalTime)}</span><span>{String(item.cabin)}</span><em>{money(item.price)}</em></div>)}
      </div></section>
  }
  if (type === 'hotel') {
    const items = (data.items ?? []) as Array<Record<string, unknown>>
    return <section className="business-card"><header><Building2/><span>住宿候选</span><small>{String(data.city ?? '')} · {String(data.checkIn ?? '')}</small></header>
      <div className="data-table"><div className="data-row hotel-row data-head"><span>酒店</span><span>星级</span><span>价格/晚</span><span>差标</span></div>
        {items.map((item, index) => <div className="data-row hotel-row" key={index}><strong>{String(item.name)}</strong><span>{'★'.repeat(Number(item.stars ?? 0))}</span><em>{money(item.nightlyPrice)}</em><span className="within"><CheckCircle2 size={15}/>{item.withinPolicy ? '符合' : '需审批'}</span></div>)}
      </div></section>
  }
  if (type === 'weather') {
    return <section className="business-card weather-card"><header><CloudSun/><span>目的地天气</span><small>{String(data.city ?? '')}</small></header>
      <div className="weather-content"><strong>{String(data.condition ?? '')}</strong><b>{String(data.low)}° — {String(data.high)}°</b><span>{String(data.advice ?? '')}</span></div></section>
  }
  if (type === 'policy') {
    const items = (data.items ?? []) as Array<Record<string, unknown>>
    return <section className="business-card"><header><ReceiptText/><span>企业政策依据</span><small>{data.degraded ? '容灾数据' : '企业知识库'}</small></header>
      <div className="policy-list">{items.map((item, index) => <article key={index}><strong>{String(item.title)}</strong><p>{String(item.content)}</p><small>来源：{String(item.source)}</small></article>)}</div></section>
  }
  if (type === 'approval') {
    const approval = ((data.items as Array<Record<string, unknown>> | undefined)?.[0] ?? data) as Record<string, unknown>
    return <section className="business-card approval-card"><header><FileCheck2/><span>出差审批</span><small>{String(approval.status ?? '')}</small></header>
      <div className="approval-grid"><span>审批编号<strong>{String(approval.approvalNo ?? '处理中')}</strong></span><span>目的地<strong>{String(approval.destination ?? '—')}</strong></span><span>出行日期<strong>{String(approval.travelDate ?? '—')}</strong></span><span>预算<strong>{approval.budget ? money(approval.budget) : '按差标'}</strong></span></div>
      {approval.policyVersion != null && <div className="approval-decision"><span>政策版本 v{String(approval.policyVersion)}</span><span>{approval.requiresFinance ? '经理 + 财务审批' : '经理审批'}</span>{Boolean(approval.idempotentReplay) && <span>幂等重放</span>}<p>{String(approval.decisionTrace ?? '已按生效政策完成决策')}</p></div>}
    </section>
  }
  return null
}
