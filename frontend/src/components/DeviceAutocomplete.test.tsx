import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeviceSummary } from '../api/types'
import DeviceAutocomplete from './DeviceAutocomplete'

const matches: Partial<DeviceSummary>[] = [
  { id: 'a', hostname: 'kiosk-01', status: 'ACTIVE', online: true, groups: [] },
  { id: 'b', hostname: 'kiosk-02', status: 'ACTIVE', online: false, groups: [] },
]

afterEach(() => vi.unstubAllGlobals())

function Harness() {
  const [selected, setSelected] = useState<DeviceSummary | null>(null)
  return (
    <>
      <DeviceAutocomplete selected={selected} onSelect={setSelected} />
      <output aria-label="applied filter">{selected?.id ?? 'none'}</output>
    </>
  )
}

function renderAutocomplete() {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({ items: matches, total: 2, offset: 0, max: 8, online: 1, all: 2 }),
          { status: 200 },
        ),
    ),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <Harness />
    </QueryClientProvider>,
  )
}

describe('device autocomplete', () => {
  it('suggests matching devices while typing and applies the picked one', async () => {
    renderAutocomplete()
    const input = screen.getByRole('combobox', { name: 'Filter by device' })

    fireEvent.change(input, { target: { value: 'kiosk' } })
    fireEvent.click(await screen.findByText('kiosk-02'))

    expect(screen.getByLabelText('applied filter')).toHaveTextContent('b')
    expect(input).toHaveValue('kiosk-02')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('typing again clears the applied filter', async () => {
    renderAutocomplete()
    const input = screen.getByRole('combobox', { name: 'Filter by device' })

    fireEvent.change(input, { target: { value: 'kiosk' } })
    fireEvent.click(await screen.findByText('kiosk-01'))
    fireEvent.change(input, { target: { value: 'x' } })

    expect(screen.getByLabelText('applied filter')).toHaveTextContent('none')
  })
})
