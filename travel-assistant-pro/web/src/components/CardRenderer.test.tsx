import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import { CardRenderer } from './CardRenderer'

test('renders flight candidates as a structured card', () => {
  render(<CardRenderer envelope={{ card: { type: 'flight', data: {
    route: '杭州 → 上海', date: '2026-09-01',
    items: [{ flightNo: 'MU1234', departureTime: '08:00', arrivalTime: '10:00', cabin: '经济舱', price: 520 }],
  } } }}/>)
  expect(screen.getByText('航班候选')).toBeInTheDocument()
  expect(screen.getByText('MU1234')).toBeInTheDocument()
  expect(screen.getByText('¥520')).toBeInTheDocument()
})

test('uses policy sources instead of flattening policy into chat text', () => {
  render(<CardRenderer envelope={{ card: { type: 'policy', data: {
    items: [{ title: '住宿标准', content: '一线城市 400 元/晚', source: '企业制度' }],
  } } }}/>)
  expect(screen.getByText('企业政策依据')).toBeInTheDocument()
  expect(screen.getByText('来源：企业制度')).toBeInTheDocument()
})

test('shows the immutable backend policy decision on an approval card', () => {
  render(<CardRenderer envelope={{ card: { type: 'approval', data: {
    approvalNo: 'VI-900001', status: 'PENDING_MANAGER', destination: '上海',
    travelDate: '2026-09-18', budget: 1600, policyVersion: 7,
    decisionTrace: '命中 L2-TIER1，预算超标转财务审批', requiresFinance: true,
    idempotentReplay: true,
  } } }}/>)
  expect(screen.getByText('政策版本 v7')).toBeInTheDocument()
  expect(screen.getByText('经理 + 财务审批')).toBeInTheDocument()
  expect(screen.getByText('幂等重放')).toBeInTheDocument()
  expect(screen.getByText('命中 L2-TIER1，预算超标转财务审批')).toBeInTheDocument()
})
