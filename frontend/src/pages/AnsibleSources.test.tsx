import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RepositorySummary } from '../api/types'
import AnsibleSources from './AnsibleSources'

const repo: RepositorySummary = {
  id: 'repo-1',
  name: 'fleet-config',
  gitUrl: 'git@example.com:org/fleet-config.git',
  branch: 'main',
  authType: 'NONE',
  authUsername: null,
  hasCredentials: false,
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

const bodiesOf = (fetchMock: ReturnType<typeof vi.fn>, method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([, init]) => JSON.parse(String((init as RequestInit).body)))

describe('repository authentication', () => {
  it('sends username and token for an HTTPS repository', async () => {
    const fetchMock = renderSources()
    await screen.findByText('fleet-config')

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'private' } })
    fireEvent.change(screen.getByLabelText('Git URL'), { target: { value: 'https://gitlab.example.com/ops/cfg.git' } })
    fireEvent.change(screen.getByLabelText('Authentication'), { target: { value: 'HTTPS_TOKEN' } })
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'oauth2' } })
    fireEvent.change(screen.getByLabelText('Token or password'), { target: { value: 'glpat-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(bodiesOf(fetchMock, 'POST')).toHaveLength(1))
    expect(bodiesOf(fetchMock, 'POST')[0]).toMatchObject({
      name: 'private',
      gitUrl: 'https://gitlab.example.com/ops/cfg.git',
      authType: 'HTTPS_TOKEN',
      authUsername: 'oauth2',
      authSecret: 'glpat-secret',
    })
  })

  it('sends a pasted private key for an SSH repository', async () => {
    const fetchMock = renderSources()
    await screen.findByText('fleet-config')

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'ssh-repo' } })
    fireEvent.change(screen.getByLabelText('Git URL'), { target: { value: 'git@gitlab.example.com:ops/cfg.git' } })
    fireEvent.change(screen.getByLabelText('Authentication'), { target: { value: 'SSH_KEY' } })
    fireEvent.click(screen.getByLabelText('Use my own private key'))
    fireEvent.change(screen.getByPlaceholderText(/BEGIN OPENSSH PRIVATE KEY/), {
      target: { value: '-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(bodiesOf(fetchMock, 'POST')).toHaveLength(1))
    expect(bodiesOf(fetchMock, 'POST')[0]).toMatchObject({
      authType: 'SSH_KEY',
      generateDeployKey: false,
      sshPrivateKey: '-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----',
    })
  })

  it('keeps the stored token when editing without entering a new one', async () => {
    const tokenRepo: RepositorySummary = {
      ...repo,
      authType: 'HTTPS_TOKEN',
      authUsername: 'oauth2',
      hasCredentials: true,
      gitUrl: 'https://gitlab.example.com/ops/cfg.git',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PUT') return new Response(JSON.stringify(tokenRepo), { status: 200 })
      if (init?.method === 'POST') return new Response(JSON.stringify(tokenRepo), { status: 200 })
      if (String(input).endsWith('/roles')) return new Response('[]', { status: 200 })
      return new Response(JSON.stringify([tokenRepo]), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AnsibleSources />
      </QueryClientProvider>,
    )
    await screen.findByText('fleet-config')
    expect(screen.getByText(/HTTPS token \(oauth2\)/)).toBeInTheDocument()

    fireEvent.click(screen.getByText('Edit'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByPlaceholderText(/keep the stored token/i)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save & sync' }))

    await waitFor(() => expect(bodiesOf(fetchMock, 'PUT')).toHaveLength(1))
    const body = bodiesOf(fetchMock, 'PUT')[0]
    expect(body).toMatchObject({ authType: 'HTTPS_TOKEN', authUsername: 'oauth2' })
    expect(body).not.toHaveProperty('authSecret')
  })
})

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
