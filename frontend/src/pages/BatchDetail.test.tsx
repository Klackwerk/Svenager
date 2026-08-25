import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobBatchInfo, JobSummary } from '../api/types'
import BatchDetail from './BatchDetail'

function job(overrides: Partial<JobSummary>): JobSummary {
  return {
    id: 'job-1',
    deviceId: 'dev-1',
    hostname: 'kiosk-01',
    type: 'APPLY_CONFIG',
    status: 'SUCCEEDED',
    exitCode: 0,
    error: null,
    triggeredBy: 'admin',
    attempt: 1,
    maxAttempts: 3,
    retriesExhausted: false,
    runAfter: null,
    queuedAt: new Date().toISOString(),
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    ...overrides,
  }
}

function batch(overrides: Partial<JobBatchInfo>): JobBatchInfo {
  return {
    id: 'batch-77',
    groupId: 'group-3',
    groupName: 'Terminals',
    triggeredBy: 'admin',
    stage: null,
    createdAt: new Date().toISOString(),
    total: 12,
    counts: { SUCCEEDED: 11, FAILED: 1 },
    done: true,
    jobs: [
      job({ id: 'job-1', hostname: 'kiosk-01' }),
      job({ id: 'job-2', hostname: 'kiosk-02', status: 'FAILED', exitCode: 2 }),
    ],
    ...overrides,
  }
}

afterEach(() => vi.unstubAllGlobals())

function renderBatch(data: JobBatchInfo) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(data), { status: 200 })))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/batches/batch-77']}>
        <Routes>
          <Route path="/batches/:id" element={<BatchDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('batch roll-up', () => {
  it('shows the succeeded/failed headline, device rows and a retry button', async () => {
    renderBatch(batch({}))

    expect(await screen.findByText('11 of 12 devices succeeded, 1 did not.')).toBeInTheDocument()
    expect(screen.getByText('kiosk-01')).toBeInTheDocument()
    expect(screen.getByText('kiosk-02')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /retry 1 failed device/i })).toBeInTheDocument()
  })

  it('shows progress while jobs are still running and no retry button', async () => {
    renderBatch(
      batch({ counts: { SUCCEEDED: 4, RUNNING: 2, PENDING: 6 }, done: false, total: 12 }),
    )

    expect(
      await screen.findByText(/4 of 12 devices finished — the rest apply at their next check-in/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
  })

  it('offers to continue a successful canary and stops after a failed one', async () => {
    renderBatch(
      batch({
        stage: 'CANARY',
        total: 1,
        counts: { SUCCEEDED: 1 },
        done: true,
        jobs: [job({ id: 'job-1', hostname: 'kiosk-01' })],
      }),
    )

    expect(await screen.findByText(/canary device succeeded/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /continue rollout/i })).toBeInTheDocument()
  })

  it('stops a failed canary without retooling the rest of the group', async () => {
    renderBatch(
      batch({
        stage: 'CANARY',
        total: 1,
        counts: { FAILED: 1 },
        done: true,
        jobs: [job({ id: 'job-1', hostname: 'kiosk-01', status: 'FAILED', exitCode: 2 })],
      }),
    )

    expect(await screen.findByText(/canary device failed/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /continue rollout/i })).not.toBeInTheDocument()
  })

  it('celebrates a fully successful rollout', async () => {
    renderBatch(batch({ counts: { SUCCEEDED: 12 }, done: true }))

    expect(await screen.findByText('All 12 devices succeeded.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
  })
})
