import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExpertModeProvider, useExpertMode } from '../lib/expertMode'
import AppSidebar from './AppSidebar'

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.removeItem('svenager-expert')
})

function ExpertProbe() {
  const { expert } = useExpertMode()
  return <p>expert: {expert ? 'on' : 'off'}</p>
}

function renderSidebar(roles: string[]) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 200 })))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <ExpertModeProvider>
          <AppSidebar user={{ username: 'op', roles }} theme={{ preference: 'system', setPreference: vi.fn() }} />
          <ExpertProbe />
        </ExpertModeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('sidebar navigation', () => {
  it('groups links into sections and hides administration from operators', () => {
    renderSidebar(['ROLE_OPERATOR'])
    const nav = screen.getByRole('navigation', { name: /main navigation/i })
    expect(within(nav).getByText('Fleet')).toBeInTheDocument()
    expect(within(nav).getByText('Automation')).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /devices/i })).toHaveAttribute('href', '/devices')
    expect(within(nav).getByRole('link', { name: /ansible sources/i })).toHaveAttribute('href', '/sources')
    expect(within(nav).queryByText('Administration')).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /users/i })).not.toBeInTheDocument()
  })

  it('shows the administration section to admins', () => {
    renderSidebar(['ROLE_ADMIN'])
    expect(screen.getByText('Administration')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /audit log/i })).toHaveAttribute('href', '/audit')
  })

  it('explains and toggles expert details from the account menu', () => {
    renderSidebar(['ROLE_ADMIN'])
    expect(screen.getByText('expert: off')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^op$/ }))
    expect(screen.getByText(/show raw variables, commit hashes/i)).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Expert details'))

    expect(screen.getByText('expert: on')).toBeInTheDocument()
    expect(screen.getByText('Expert', { selector: '.badge' })).toBeInTheDocument()
    expect(localStorage.getItem('svenager-expert')).toBe('1')
  })
})
