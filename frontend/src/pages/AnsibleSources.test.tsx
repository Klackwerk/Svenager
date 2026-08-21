import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RepositorySummary } from '../api/types'
import AnsibleSources from './AnsibleSources'

const repo: RepositorySummary = {
  id: 'repo-1',
  name: 'fleet-config',
  gitUrl: 'git@example.com:org/fleet-config.git',
  branch: 'main',
  deployKeyPublic: null,
  syncStatus: 'OK',
  syncError: null,
  lastCommit: 'abcdef1234',
  lastSyncedAt: new Date().toISOString(),
  roleCount: 2,
}

afterEach(() => vi.unstubAllGlobals())

function renderSources() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'DELETE') return new Response(null, { status: 204 })
    if (String(input).endsWith('/roles')) return new Response('[]', { status: 200 })
    return new Response(JSON.stringify([repo]), { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AnsibleSources />
    </QueryClientProvider>,
  )
  return fetchMock
}

const deleteCalls = (fetchMock: ReturnType<typeof vi.fn>) =>
  fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'DELETE')

describe('per-repository pending state', () => {
  it('keeps other Sync buttons enabled while one repo syncs', async () => {
    const second = { ...repo, id: 'repo-2', name: 'other-config' }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === 'POST') return new Promise<Response>(() => {})
        if (String(input).endsWith('/roles')) return new Response('[]', { status: 200 })
        return new Response(JSON.stringify([repo, second]), { status: 200 })
      }),
    )
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AnsibleSources />
      </QueryClientProvider>,
    )
    await screen.findByText('fleet-config')

    const [first, other] = screen.getAllByRole('button', { name: /sync now/i })
    fireEvent.click(first)

    expect(await screen.findByText('Syncing…')).toBeInTheDocument()
    expect(first).toBeDisabled()
    expect(other).toBeEnabled()
  })
})

describe('repository removal', () => {
  it('requires an explicit confirm and cancel leaves the repo untouched', async () => {
    const fetchMock = renderSources()
    await screen.findByText('fleet-config')

    fireEvent.click(screen.getByText('Remove'))
    expect(await screen.findByText('Remove repository?')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Cancel'))
    expect(deleteCalls(fetchMock)).toHaveLength(0)
    expect(screen.getByText('fleet-config')).toBeInTheDocument()
  })

  it('deletes the repository after confirming', async () => {
    const fetchMock = renderSources()
    await screen.findByText('fleet-config')

    fireEvent.click(screen.getByText('Remove'))
    fireEvent.click(await screen.findByText('Remove repository'))

    await waitFor(() => expect(deleteCalls(fetchMock)).toHaveLength(1))
    expect(String(deleteCalls(fetchMock)[0][0])).toContain('/api/v1/repositories/repo-1')
  })
})
