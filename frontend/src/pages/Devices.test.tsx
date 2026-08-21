import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeviceSummary } from '../api/types'
import ToastProvider from '../components/ToastProvider'
import Devices from './Devices'

function device(overrides: Partial<DeviceSummary>): DeviceSummary {
  return {
    id: 'id-' + overrides.hostname,
    hostname: 'host',
    status: 'ACTIVE',
    online: true,
    agentVersion: '1.0.0',
    lastContactAt: new Date().toISOString(),
    lastIp: '10.0.0.5',
    lastJobAt: null,
    enrolledAt: new Date().toISOString(),
    groups: [{ id: 'group-1', name: 'Terminals' }],
    ...overrides,
  }
}

const fleet = [
  device({ hostname: 'kiosk-01', online: true }),
  device({ hostname: 'kiosk-02', online: false }),
  device({ hostname: 'infoscreen', online: true, groups: [] }),
]

afterEach(() => vi.unstubAllGlobals())

function renderDevices() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (init?.method === 'POST') return new Response(null, { status: 204 })
    if (url.includes('/groups'))
      return new Response(
        JSON.stringify([{ id: 'group-1', name: 'Terminals', description: null, deviceCount: 2, roleCount: 1 }]),
        { status: 200 },
      )
    return new Response(
      JSON.stringify({ items: fleet, total: fleet.length, offset: 0, max: 50, online: 2, all: 3 }),
      { status: 200 },
    )
  })
  vi.stubGlobal('fetch', fetchMock)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ToastProvider>
        <MemoryRouter>
          <Devices />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
  return fetchMock
}

describe('Devices at scale', () => {
  it('renders the server page with groups column and fleet counts', async () => {
    renderDevices()
    await screen.findByText('kiosk-01')

    expect(screen.getByText('2 of 3 online')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('3 devices')
    expect(screen.getAllByText('Terminals').length).toBeGreaterThan(0)
  })

  it('sends the search to the server as a query parameter', async () => {
    const fetchMock = renderDevices()
    await screen.findByText('kiosk-01')

    fireEvent.change(screen.getByLabelText(/search hostname/i), { target: { value: 'kiosk' } })

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([input]) => String(input).includes('q=kiosk'))).toBe(true),
    )
  })

  it('bulk-adds the selected devices to a group with one success toast', async () => {
    const fetchMock = renderDevices()
    await screen.findByText('kiosk-01')

    fireEvent.click(screen.getByLabelText('Select kiosk-01'))
    fireEvent.click(screen.getByLabelText('Select kiosk-02'))
    expect(screen.getByText('2 selected')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Group for bulk action'), { target: { value: 'group-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add to group' }))

    expect(await screen.findByText('Added 2 devices to Terminals.')).toBeInTheDocument()
    const posts = fetchMock.mock.calls.filter(
      ([input, init]) =>
        (init as RequestInit | undefined)?.method === 'POST' &&
        String(input).includes('/api/v1/groups/group-1/devices'),
    )
    expect(posts).toHaveLength(2)
  })
})
