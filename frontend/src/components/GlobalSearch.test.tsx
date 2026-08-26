import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import GlobalSearch from './GlobalSearch'

const results = {
  devices: [{ id: 'dev-1', hostname: 'kiosk-01', online: true, status: 'ACTIVE' }],
  groups: [{ id: 'group-3', name: 'Kiosks' }],
  roles: [{ id: 'role-7', name: 'kiosk', displayName: 'Kiosk browser', repository: 'fleet-config', missing: false }],
  jobs: [{ id: 'job-4242', hostname: 'kiosk-01', status: 'SUCCEEDED', type: 'APPLY_CONFIG' }],
}

afterEach(() => vi.unstubAllGlobals())

function renderSearch() {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(results), { status: 200 })))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <GlobalSearch />
        <Routes>
          <Route path="/" element={<p>home</p>} />
          <Route path="/groups/:id" element={<p>group page</p>} />
          <Route path="/sources" element={<p>sources page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('global search', () => {
  it('shows grouped results and navigates on selection', async () => {
    renderSearch()
    const input = screen.getByRole('combobox', { name: /search devices/i })

    fireEvent.change(input, { target: { value: 'kio' } })

    expect(await screen.findByText('kiosk-01')).toBeInTheDocument()
    expect(screen.getByText('Kiosks')).toBeInTheDocument()
    expect(screen.getByText('Kiosk browser')).toBeInTheDocument()
    expect(screen.getByText(/job #job-4242/i)).toBeInTheDocument()

    fireEvent.click(screen.getByText('Kiosks'))
    expect(await screen.findByText('group page')).toBeInTheDocument()
    expect(input).toHaveValue('')
  })

  it('opens the matching role on the sources page', async () => {
    renderSearch()
    fireEvent.change(screen.getByRole('combobox', { name: /search devices/i }), { target: { value: 'kio' } })
    fireEvent.click(await screen.findByText('Kiosk browser'))
    expect(await screen.findByText('sources page')).toBeInTheDocument()
  })

  it('focuses via Ctrl+K', () => {
    renderSearch()
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    expect(screen.getByRole('combobox', { name: /search devices/i })).toHaveFocus()
  })
})
