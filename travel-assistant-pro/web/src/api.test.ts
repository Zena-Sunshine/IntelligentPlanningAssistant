import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('surfaces the backend error message and status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ message: '用户名或密码错误' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } },
    )))

    await expect(api.login('voyage', 'wrong')).rejects.toMatchObject({
      status: 401,
      message: '用户名或密码错误',
    })
  })

  it('does not break history loading when one cards payload is malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: 'm1', role: 'assistant', content: '完成', cardsJson: '{bad json' },
    ]), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const messages = await api.messages('token', 'conversation-1')
    expect(messages[0].cards).toEqual([])
  })

  it('restores persisted Agent runtime frames with message history', async () => {
    const runtime = [{ type: 'plan', data: { displayName: '执行计划', summary: '单 Agent 处理' } }]
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([{
      id: 'm2', role: 'assistant', content: '完成', cardsJson: '[]', runtimeJson: JSON.stringify(runtime),
    }]), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const messages = await api.messages('token', 'conversation-1')
    expect(messages[0].runtime).toEqual(runtime)
  })

  it('parses SSE frames even when network chunks split event boundaries', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('event: route\r\ndata: {"intents":["travel_search"]}\r\n'))
        controller.enqueue(encoder.encode('\r\nevent: done\ndata: {"answer":"ok"}\n\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })))
    const frames: Array<{ event: string; data: string }> = []

    await api.streamMessage('token', 'conversation-1', '查酒店', {}, (frame) => frames.push(frame))

    expect(frames).toEqual([
      { event: 'route', data: '{"intents":["travel_search"]}' },
      { event: 'done', data: '{"answer":"ok"}' },
    ])
  })

  it('fails clearly when an SSE response has no readable body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body: null }))
    await expect(api.streamMessage('token', 'conversation-1', 'hello', {}, () => undefined))
      .rejects.toMatchObject({ status: 200, message: '无法建立智能服务连接' })
  })
})
