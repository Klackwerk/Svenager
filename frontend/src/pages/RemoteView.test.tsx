import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RemoteSessionInfo } from '../api/types'
import RemoteView from './RemoteView'

const rfbInstances: Array<{ url: string; disconnect: () => void }> = []

vi.mock('@novnc/novnc', () => ({
  default: class FakeRFB extends EventTarget {
    scaleViewport = false
    viewOnly = false
    background = ''
    disconnect = vi.fn()
    constructor(_target: HTMLElement, url: string) {
      super()
      rfbInstances.push({ url, disconnect: this.disconnect })
    }
  },
}))

function session(status: RemoteSessionInfo['status']): RemoteSessionInfo {
  return {
    sessionId: 'sess-1',
    deviceId: 'dev-1',
    hostname: 'kiosk-01',
    status,
    requestedBy: 'admin',
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    agentConnectedAt: null,
    viewerConnectedAt: null,
    closedAt: null,
    closeReason: status === 'CLOSED' ? 'time limit reached' : null,
    wsPath: '/api/v1/ui/vnc/sess-1',
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  rfbInstances.length = 0
})

function renderRemoteView(sessionStatus: RemoteSessionInfo['status']) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/devices/') && url.endsWith('/remote-sessions')) {
        return new Response(JSON.stringify([session(sessionStatus)]), { status: 200 })
      }
      if (url.includes('/remote-session')) {
        return new Response(JSON.stringify(session(sessionStatus)), { status: 200 })
      }
      return new Response(JSON.stringify({}), { status: 404 })
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/devices/dev-1/remote']}>
        <Routes>
          <Route path="/devices/:id/remote" element={<RemoteView />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RemoteView', () => {
  it('explains the wait while the device has not picked up the tunnel', async () => {
    renderRemoteView('PENDING')

    expect(await screen.findByText(/waiting for the device to pick up the tunnel/i)).toBeInTheDocument()
    expect(rfbInstances).toHaveLength(0)
  })

  it('attaches noVNC to the session websocket once the agent is connected', async () => {
    renderRemoteView('AGENT_CONNECTED')

    await screen.findByText(/remote view: kiosk-01/i)
    await vi.waitFor(() => expect(rfbInstances).toHaveLength(1))
    expect(rfbInstances[0].url).toContain('/api/v1/ui/vnc/sess-1')
  })

  it('shows the audit reason when the session is over', async () => {
    renderRemoteView('CLOSED')

    expect(await screen.findByText(/session ended/i)).toBeInTheDocument()
    expect(screen.getByText(/time limit reached/i)).toBeInTheDocument()
  })
})
