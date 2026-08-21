import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Login from './Login'

function renderLogin() {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <Login />
    </QueryClientProvider>,
  )
  return queryClient
}

afterEach(() => vi.unstubAllGlobals())

describe('Login', () => {
  it('logs in and stores the user in the query cache', async () => {
    const user = { username: 'admin', roles: ['ROLE_ADMIN'] }
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(user), { status: 200 })),
    )
    const queryClient = renderLogin()

    await userEvent.type(screen.getByLabelText('Username'), 'admin')
    await userEvent.type(screen.getByLabelText('Password'), 'admin')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await vi.waitFor(() => expect(queryClient.getQueryData(['me'])).toEqual(user))
  })

  it('offers the SSO button when the server announces it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({ sso: true, ssoUrl: '/api/v1/auth/sso/oidc', ssoLabel: 'Authentik' }),
          { status: 200 },
        ),
      ),
    )
    renderLogin()

    const ssoButton = await screen.findByRole('button', { name: 'Continue with Authentik' })
    expect(ssoButton).toHaveAttribute('href', '/api/v1/auth/sso/oidc')
  })

  it('shows the SSO error the server redirected back with', async () => {
    window.history.pushState({}, '', '/?ssoError=no%20Svenager%20role%20is%20mapped')
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ sso: false }), { status: 200 })),
    )
    renderLogin()

    expect(await screen.findByText(/no Svenager role is mapped/)).toBeInTheDocument()
    window.history.pushState({}, '', '/')
  })

  it('shows a friendly message for wrong credentials', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ error: 'invalid credentials' }), { status: 401 })),
    )
    renderLogin()

    await userEvent.type(screen.getByLabelText('Username'), 'admin')
    await userEvent.type(screen.getByLabelText('Password'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText(/wrong username or password/i)).toBeInTheDocument()
  })
})
