import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobDetail as JobDetailInfo } from '../api/types'
import ToastProvider from '../components/ToastProvider'
import { ExpertModeProvider } from '../lib/expertMode'
import JobDetail from './JobDetail'

function jobDetail(overrides: Partial<JobDetailInfo>): JobDetailInfo {
  return {
    id: 'job-99999',
    deviceId: 'dev-1',
    hostname: 'kiosk-01',
    type: 'APPLY_CONFIG',
    status: 'RUNNING',
    exitCode: null,
    error: null,
    triggeredBy: 'admin',
    attempt: 1,
    maxAttempts: 3,
    retriesExhausted: false,
    runAfter: null,
    queuedAt: new Date().toISOString(),
    startedAt: new Date().toISOString(),
    finishedAt: null,
    payload: null,
    log: 'PLAY [all]',
    ...overrides,
  }
}

afterEach(() => vi.unstubAllGlobals())

function renderJob(data: JobDetailInfo) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'POST')
      return new Response(JSON.stringify({ ...data, id: 'job-10', status: 'PENDING' }), { status: 201 })
    return new Response(JSON.stringify(data), { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/jobs/job-99999']}>
          <Routes>
            <Route path="/jobs/:id" element={<JobDetail />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
  return fetchMock
}

describe('expert mode', () => {
  const withPayload = jobDetail({
    status: 'SUCCEEDED',
    finishedAt: new Date().toISOString(),
    payload: {
      timeoutSeconds: 1800,
      plays: [{ repoId: 1, repoName: 'fleet-config', commit: 'abcdef123456', roles: ['kiosk'] }],
      extraVars: { kiosk_url: 'https://example.org' },
      secretVars: [],
    },
  })

  it('hides raw variables and commit hashes by default', async () => {
    renderJob(withPayload)
    expect(await screen.findByText(/fleet-config/)).toBeInTheDocument()
    expect(screen.queryByText(/kiosk_url/)).not.toBeInTheDocument()
    expect(screen.queryByText(/abcdef1234/)).not.toBeInTheDocument()
  })

  it('reveals them when expert mode is on', async () => {
    localStorage.setItem('svenager-expert', '1')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(withPayload), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <ToastProvider>
          <ExpertModeProvider>
            <MemoryRouter initialEntries={['/jobs/job-99999']}>
              <Routes>
                <Route path="/jobs/:id" element={<JobDetail />} />
              </Routes>
            </MemoryRouter>
          </ExpertModeProvider>
        </ToastProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/kiosk_url/)).toBeInTheDocument()
    expect(screen.getByText('abcdef1234')).toBeInTheDocument()
    localStorage.removeItem('svenager-expert')
  })
})

describe('job actions', () => {
  it('offers Cancel while the job is active', async () => {
    renderJob(jobDetail({ status: 'RUNNING' }))
    expect(await screen.findByRole('button', { name: 'Cancel job' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Re-run' })).not.toBeInTheDocument()
  })

  it('offers Re-run for finished applies and queues a new job', async () => {
    const fetchMock = renderJob(jobDetail({ status: 'FAILED', finishedAt: new Date().toISOString() }))
    fireEvent.click(await screen.findByRole('button', { name: 'Re-run' }))

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([input, init]) =>
            (init as RequestInit | undefined)?.method === 'POST' && String(input).includes('/jobs/job-99999/rerun'),
        ),
      ).toBe(true),
    )
    expect(screen.queryByRole('button', { name: 'Cancel job' })).not.toBeInTheDocument()
  })

  it('explains exhausted retries and points to Re-run', async () => {
    renderJob(
      jobDetail({
        status: 'FAILED',
        finishedAt: new Date().toISOString(),
        triggeredBy: 'auto (retry 3 of 3)',
        attempt: 3,
        retriesExhausted: true,
      }),
    )
    expect(await screen.findByText(/Automatic retries are exhausted/)).toBeInTheDocument()
    expect(screen.getByText(/attempt 3 of 3/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-run' })).toBeInTheDocument()
  })

  it('stays quiet about retries for a plain failure', async () => {
    renderJob(jobDetail({ status: 'FAILED', finishedAt: new Date().toISOString() }))
    await screen.findByRole('button', { name: 'Re-run' })
    expect(screen.queryByText(/Automatic retries are exhausted/)).not.toBeInTheDocument()
  })
})
