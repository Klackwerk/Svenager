import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { createQueryClient } from './api/queryClient'
import ToastProvider from './components/ToastProvider'

afterEach(() => vi.unstubAllGlobals())

function renderApp(fetchStub: (input: RequestInfo | URL) => Promise<Response>, path = '/') {
  vi.stubGlobal('fetch', vi.fn(fetchStub))
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ToastProvider>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

const me = { username: 'admin', roles: ['ROLE_ADMIN'] }

describe('session expiry', () => {
  it('falls back to the login screen when an API call returns 401', async () => {
    renderApp(
      async (input) =>
        String(input).includes('/auth/me')
          ? new Response(JSON.stringify(me), { status: 200 })
          : new Response(JSON.stringify({ error: 'unauthorized' }), { status: 401 }),
      '/devices',
    )
    expect(await screen.findByText('Sign in to manage your devices')).toBeInTheDocument()
  })

  it('shows a not-found page for unknown routes', async () => {
    renderApp(async () => new Response(JSON.stringify(me), { status: 200 }), '/nonsense')
    expect(await screen.findByText('Page not found')).toBeInTheDocument()
  })

  it('explains missing admin rights on /users instead of a blank page', async () => {
    renderApp(
      async (input) =>
        String(input).includes('/auth/me')
          ? new Response(JSON.stringify({ username: 'op', roles: ['ROLE_OPERATOR'] }), { status: 200 })
          : new Response('[]', { status: 200 }),
      '/users',
    )
    expect(await screen.findByText('Administrator rights required')).toBeInTheDocument()
  })

  it('keeps the unreachable-server notice on network errors', async () => {
    renderApp(async (input) => {
      if (String(input).includes('/auth/me')) throw new TypeError('Failed to fetch')
      return new Response('{}', { status: 200 })
    })
    expect(await screen.findByText(/server could not be reached/i)).toBeInTheDocument()
  })
})
