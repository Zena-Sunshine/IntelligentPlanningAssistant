import { expect, test, type Page } from '@playwright/test'

const credentials = { username: 'voyage', password: 'Voyage@2026' }

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('账号').fill(credentials.username)
  await page.getByLabel('密码').fill(credentials.password)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await expect(page.getByRole('button', { name: /新建对话/ })).toBeVisible()
}

test('登录成功后进入受保护的差旅工作台', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.getByLabel('密码').fill(credentials.password)
  await page.getByRole('button', { name: '进入工作台' }).click()

  await expect(page.getByRole('button', { name: /新建对话/ })).toBeVisible()
  await expect(page.getByText('差旅运营专员', { exact: true })).toBeVisible()
  await expect(page.getByTitle('退出登录')).toBeVisible()
})

test('会话可以新建、重命名、搜索并软删除', async ({ page }) => {
  await login(page)
  const title = `E2E会话-${Date.now()}`

  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/v1/conversations')
      && response.request().method() === 'POST' && response.status() === 201),
    page.getByRole('button', { name: /新建对话/ }).click(),
  ])
  const active = page.locator('.conversation-item').first()
  await expect(active).toBeVisible()
  await expect(active).toHaveClass(/active/)
  await active.hover()
  await active.getByTitle('重命名').click()
  await expect(active.getByTitle('保存重命名')).toBeVisible()
  await expect(active.getByTitle('取消重命名')).toBeVisible()
  await active.getByLabel('对话名称').fill(title)
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/v1/conversations/')
      && response.request().method() === 'PATCH' && response.status() === 200),
    active.getByTitle('保存重命名').click(),
  ])
  await expect(active.locator('strong')).toHaveText(title)

  await active.hover()
  await active.getByTitle('重命名').click()
  await active.getByLabel('对话名称').fill(`${title}-不保存`)
  await active.getByTitle('取消重命名').click()
  await expect(active.locator('strong')).toHaveText(title)

  await page.getByLabel('搜索对话').fill(title)
  await expect(page.locator('.conversation-item')).toHaveCount(1)
  await expect(page.locator('.conversation-item strong')).toHaveText(title)

  page.once('dialog', (dialog) => dialog.accept())
  await page.locator('.conversation-item').hover()
  await page.locator('.conversation-item').getByTitle('删除').click()
  await expect(page.locator('.conversation-item')).toHaveCount(0)
})

test('流式消息经过Agent后持久化，刷新页面仍可恢复', async ({ page }) => {
  await login(page)
  const query = `不要提交出差申请，只查审批进度 E2E-${Date.now()}`

  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/v1/conversations')
      && response.request().method() === 'POST' && response.status() === 201),
    page.getByRole('button', { name: /新建对话/ }).click(),
  ])
  await page.getByLabel('输入差旅事项').fill(query)
  await page.getByTitle('发送').click()

  await expect(page.locator('.message.user .message-text')).toContainText(query)
  const assistant = page.locator('.message.assistant .message-text').last()
  await expect(assistant).not.toBeEmpty({ timeout: 20_000 })
  await expect(page.getByText('语义调度', { exact: true })).toBeVisible()
  await expect(page.getByTitle('发送')).toBeVisible({ timeout: 20_000 })

  await page.reload()
  await expect(page.locator('.message.user .message-text')).toContainText(query)
  await expect(page.locator('.message.assistant .message-text').last()).not.toBeEmpty()
  await expect(page.getByText('公开判断依据', { exact: true })).toBeVisible()
  await expect(page.locator('.runtime-group summary').filter({ hasText: '执行计划' })).toBeVisible()
})

test('短消息气泡按内容收缩，通用问答不再返回同一句话', async ({ page }) => {
  await login(page)
  await page.getByRole('button', { name: /新建对话/ }).click()

  await page.getByLabel('输入差旅事项').fill('你是什么大模型')
  await page.getByTitle('发送').click()
  await expect(page.locator('.message.assistant .message-text').last()).toContainText('qwen-turbo', { timeout: 20_000 })
  await expect(page.getByLabel('当前模型：通义 qwen-turbo')).toBeVisible()
  const shortBubbleWidth = await page.locator('.message.user').last().evaluate((element) => element.getBoundingClientRect().width)
  expect(shortBubbleWidth).toBeLessThan(360)

  await page.getByLabel('输入差旅事项').fill('上海有哪些景点比较好玩')
  await page.getByTitle('发送').click()
  // The online model can legitimately take longer than Playwright's 5s default;
  // wait for the streamed answer instead of asserting against its empty shell.
  await expect(page.locator('.message.assistant .message-text').last()).toContainText('外滩', { timeout: 20_000 })
  await expect(page.locator('.message.assistant .message-text').last()).not.toContainText('我可以协助规划差旅行程、查询机酒天气')
})

test('不同会话消息严格隔离，当前城市覆盖旧会话城市', async ({ page }) => {
  await login(page)
  const marker = Date.now()
  const shanghaiQuery = `上海天气 隔离A${marker}`
  const wuhanQuery = `武汉天气 隔离B${marker}`

  await page.getByRole('button', { name: /新建对话/ }).click()
  await page.getByLabel('输入差旅事项').fill(shanghaiQuery)
  await page.getByTitle('发送').click()
  await expect(page.locator('.message.assistant .message-text').last()).toContainText('上海')
  await expect(page.getByTitle('发送')).toBeVisible()

  await page.getByRole('button', { name: /新建对话/ }).click()
  await expect(page.locator('.message')).toHaveCount(0)
  await page.getByLabel('输入差旅事项').fill(wuhanQuery)
  await page.getByTitle('发送').click()
  await expect(page.locator('.message.assistant .message-text').last()).toContainText('武汉')
  await expect(page.locator('.message.user .message-text')).not.toContainText('上海天气')
  await expect(page.getByTitle('发送')).toBeVisible()

  await page.locator('.conversation-item').filter({ hasText: shanghaiQuery }).click()
  await expect(page.locator('.message.user .message-text')).toContainText(shanghaiQuery)
  await expect(page.locator('.message.user .message-text')).not.toContainText(wuhanQuery)

  await page.locator('.conversation-item').filter({ hasText: wuhanQuery }).click()
  await expect(page.locator('.message.user .message-text')).toContainText(wuhanQuery)
  await expect(page.locator('.message.user .message-text')).not.toContainText(shanghaiQuery)
})

test('长内容三栏可独立滚动，编辑按钮不再互相覆盖', async ({ page }) => {
  await login(page)

  const layout = await page.evaluate(() => {
    const workspace = document.querySelector('.workspace')!.getBoundingClientRect()
    const chat = document.querySelector('.chat-stage')!.getBoundingClientRect()
    const composer = document.querySelector('.composer-wrap')!.getBoundingClientRect()
    const viewport = document.querySelector('.message-viewport')!
    const conversationList = document.querySelector('.conversation-list')!
    // Inline padding survives React's async message/list reconciliation and
    // gives both production scroll containers deterministic overflow.
    ;(viewport as HTMLElement).style.paddingBottom = '1800px'
    ;(conversationList as HTMLElement).style.paddingBottom = '1000px'
    return {
      workspaceHeight: workspace.height,
      chatHeight: chat.height,
      composerBottom: composer.bottom,
      workspaceBottom: workspace.bottom,
      accent: getComputedStyle(document.documentElement).getPropertyValue('--blue').trim(),
    }
  })

  expect(layout.chatHeight).toBeLessThanOrEqual(layout.workspaceHeight)
  expect(layout.composerBottom).toBeLessThanOrEqual(layout.workspaceBottom)
  expect(layout.accent).toMatch(/^#[0-9a-f]{6}$/i)

  const messageViewport = page.locator('.message-viewport')
  await messageViewport.hover()
  await page.mouse.wheel(0, 600)
  await expect.poll(() => messageViewport.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  const messageDown = await messageViewport.evaluate((element) => element.scrollTop)
  await page.mouse.wheel(0, -260)
  await expect.poll(() => messageViewport.evaluate((element) => element.scrollTop)).toBeLessThan(messageDown)

  const conversationList = page.locator('.conversation-list')
  await conversationList.hover()
  await page.mouse.wheel(0, 500)
  await expect.poll(() => conversationList.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)

  await page.getByTitle('收起运行详情').click()
  await expect(page.getByTitle('展开运行详情')).toBeVisible()
  await page.getByTitle('展开运行详情').click()
  await expect(page.getByTitle('收起运行详情')).toBeVisible()

  await page.locator('.conversation-rail').getByTitle('收起对话列表').click()
  await expect(page.locator('.workspace')).toHaveClass(/left-collapsed/)
  await page.locator('.chat-header').getByTitle('展开对话列表').click()
  await expect(page.locator('.workspace')).not.toHaveClass(/left-collapsed/)

  await page.evaluate(() => {
    const viewport = document.querySelector('.message-viewport') as HTMLElement | null
    const conversationList = document.querySelector('.conversation-list') as HTMLElement | null
    if (viewport) viewport.style.paddingBottom = ''
    if (conversationList) conversationList.style.paddingBottom = ''
  })
})
