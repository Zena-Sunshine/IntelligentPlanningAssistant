import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'

const login = vi.fn()

vi.mock('../auth', () => ({
  useAuth: () => ({ login }),
}))

describe('LoginPage', () => {
  beforeEach(() => { login.mockReset() })
  afterEach(() => cleanup())

  it('keeps submit disabled until credentials are complete', () => {
    render(<LoginPage/>)
    expect(screen.getByRole('button', { name: /进入工作台/ })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Voyage@2026' } })
    expect(screen.getByRole('button', { name: /进入工作台/ })).toBeEnabled()
  })

  it('submits the entered credentials', async () => {
    login.mockResolvedValue(undefined)
    render(<LoginPage/>)
    fireEvent.change(screen.getByLabelText('账号'), { target: { value: 'operator' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: /进入工作台/ }))

    await waitFor(() => expect(login).toHaveBeenCalledWith('operator', 'secret'))
  })

  it('locks the form while authentication is pending', async () => {
    let finish!: () => void
    login.mockImplementation(() => new Promise<void>((resolve) => { finish = resolve }))
    render(<LoginPage/>)
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'Voyage@2026' } })
    fireEvent.click(screen.getByRole('button', { name: /进入工作台/ }))

    expect(await screen.findByRole('button', { name: '正在验证…' })).toBeDisabled()
    finish()
    await waitFor(() => expect(screen.getByRole('button', { name: /进入工作台/ })).toBeEnabled())
  })

  it('shows the authentication error and restores the button', async () => {
    login.mockRejectedValue(new Error('用户名或密码错误'))
    render(<LoginPage/>)
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: /进入工作台/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('用户名或密码错误')
    expect(screen.getByRole('button', { name: /进入工作台/ })).toBeEnabled()
  })
})
