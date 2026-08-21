import { QueryClientProvider, useMutation } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { createQueryClient } from '../api/queryClient'
import ToastProvider from './ToastProvider'

function Actions() {
  const named = useMutation({
    mutationFn: async () => {
      throw new ApiError(500, 'database gone')
    },
    meta: { errorMessage: 'The group memberships could not be saved.' },
  })
  const generic = useMutation({
    mutationFn: async () => {
      throw new Error('boom')
    },
  })
  const silent = useMutation({
    mutationFn: async () => {
      throw new Error('boom')
    },
    meta: { silentError: true },
  })
  return (
    <>
      <button onClick={() => named.mutate()}>named</button>
      <button onClick={() => generic.mutate()}>generic</button>
      <button onClick={() => silent.mutate()}>silent</button>
    </>
  )
}

function renderActions() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <ToastProvider>
        <Actions />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('mutation error toasts', () => {
  it('shows a toast naming the failed action with the server message', async () => {
    renderActions()
    fireEvent.click(screen.getByText('named'))
    expect(
      await screen.findByText('The group memberships could not be saved. (database gone)'),
    ).toBeInTheDocument()
  })

  it('falls back to a generic message and can be dismissed', async () => {
    renderActions()
    fireEvent.click(screen.getByText('generic'))
    const toast = await screen.findByText('Something went wrong. The change was not saved.')
    fireEvent.click(screen.getByLabelText('Dismiss notification'))
    expect(toast).not.toBeInTheDocument()
  })

  it('stays quiet for mutations with their own inline error UI', async () => {
    renderActions()
    fireEvent.click(screen.getByText('silent'))
    fireEvent.click(screen.getByText('named'))
    await screen.findByText(/could not be saved/)
    expect(screen.queryByText(/Something went wrong/)).not.toBeInTheDocument()
  })
})
