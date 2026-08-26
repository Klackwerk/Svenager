import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RemoteSessionInfo } from '../api/types'
import DeviceShell from './DeviceShell'

vi.mock('@xterm/xterm/css/xterm.css', () => ({}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    open() {}
    loadAddon() {}
    focus() {}
    write() {}
    onData() {}
    dispose = vi.fn()
  },
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit() {} } }))

const sockets: FakeWebSocket[] = []

class FakeWebSocket extends EventTarget {
  static OPEN = 1
  static CLOSED = 3
  readyState = FakeWebSocket.OPEN
  binaryType = 'blob'
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onmessage: ((ev: { data: unknown }) => void) | null = null
  send = vi.fn()
  close = vi.fn(() => {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.()
  })
  url: string
  protocols?: string | string[]
  constructor(url: string, protocols?: string | string[]) {
    super()
    this.url = url
    this.protocols = protocols
    sockets.push(this)
  }
}

function session(status: RemoteSessionInfo['status']): RemoteSessionInfo {
  return {
    sessionId: 'sh-1',
    deviceId: 'dev-1',
    hostname: 'kiosk-01',
    status,
    kind: 'SHELL',
    requestedBy: 'admin',
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 600_000).toISOString(),
    agentConnectedAt: null,
    viewerConnectedAt: null,
    closedAt: null,
    closeReason: null,
    wsPath: '/api/v1/ui/vnc/sh-1',
  }
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.stubGlobal('WebSocket', FakeWebSocket)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  sockets.length = 0
})

/** GET session status advances AGENT_CONNECTED -> ACTIVE after the first poll. */
function renderShell() {
  let sessionPolls = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/shell-session') && init?.method === 'POST') {
        return new Response(JSON.stringify(session('AGENT_CONNECTED')), { status: 201 })
      }
      if (url.includes('/remote-sessions/sh-1')) {
        sessionPolls += 1
        return new Response(JSON.stringify(session(sessionPolls <= 1 ? 'AGENT_CONNECTED' : 'ACTIVE')), {
          status: 200,
        })
      }
      if (url.includes('/devices/dev-1') && !url.includes('/shell-session')) {
        return new Response(JSON.stringify({ id: 'dev-1', hostname: 'kiosk-01', online: true }), { status: 200 })
      }
      return new Response(JSON.stringify({}), { status: 404 })
    }),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/devices/dev-1/shell']}>
        <Routes>
          <Route path="/devices/:id/shell" element={<DeviceShell />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DeviceShell', () => {
  it('keeps the terminal socket open when the session flips AGENT_CONNECTED -> ACTIVE', async () => {
    renderShell()

    // The socket opens once the agent tunnel is up.
    await waitFor(() => expect(sockets).toHaveLength(1))
    const ws = sockets[0]
    expect(ws.protocols).toEqual(['binary'])
    ws.onopen?.()

    // Advance past the 2s status poll so it returns ACTIVE — the regression
    // was that this flip tore down the socket we just opened.
    await vi.advanceTimersByTimeAsync(2500)
    await vi.advanceTimersByTimeAsync(2500)

    expect(ws.close).not.toHaveBeenCalled()
    expect(sockets).toHaveLength(1)
    expect(await screen.findByText('Live')).toBeInTheDocument()
  })
})
