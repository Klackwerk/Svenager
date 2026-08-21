import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DashboardOverview } from '../api/types'
import Dashboard from './Dashboard'

const overview: DashboardOverview = {
  devices: { total: 4, online: 3, offline: 1, ungrouped: 1 },
  jobs: { succeeded: 8, failed: 2, active: 1 },
  repos: { total: 1, errors: 0, neverSynced: 0 },
  windowDays: 7,
  groups: [
    {
      id: 'group-1',
      name: 'Supporter selfservice terminals',
      description: 'Kiosks at the support desk',
      deviceCount: 3,
      onlineCount: 2,
      roleCount: 1,
      lastContactAt: new Date().toISOString(),
      lastJobAt: new Date().toISOString(),
      jobs: { succeeded: 5, failed: 1, active: 0 },
    },
  ],
}

afterEach(() => vi.unstubAllGlobals())

function renderDashboard(data: DashboardOverview) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(data), { status: 200 })),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Dashboard', () => {
  it('shows fleet statistics and group cards', async () => {
    renderDashboard(overview)

    expect(await screen.findByText('3 / 4')).toBeInTheDocument()
    expect(screen.getByText('80%')).toBeInTheDocument()
    expect(screen.getByText('Supporter selfservice terminals')).toBeInTheDocument()
    expect(screen.getByText('2/3 online')).toBeInTheDocument()
    expect(screen.getByText(/1 device is not in any group/)).toBeInTheDocument()
  })

  it('guides new installations toward enrollment', async () => {
    renderDashboard({
      devices: { total: 0, online: 0, offline: 0, ungrouped: 0 },
      jobs: { succeeded: 0, failed: 0, active: 0 },
      repos: { total: 0, errors: 0, neverSynced: 0 },
      windowDays: 7,
      groups: [],
    })

    expect(await screen.findByText(/no devices are enrolled yet/i)).toBeInTheDocument()
    expect(screen.getByText(/no groups yet/i)).toBeInTheDocument()
  })
})
