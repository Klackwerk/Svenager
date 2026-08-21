import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RoleInfo } from '../api/types'
import ApplyConfirmModal from './ApplyConfirmModal'

function role(id: string, displayName: string): RoleInfo {
  return {
    id,
    name: displayName.toLowerCase(),
    displayName,
    description: null,
    userAssignable: true,
    missing: false,
    repositoryId: 'repo-1',
    repository: 'fleet-config',
    argumentSpec: {},
    defaults: {},
  }
}

describe('apply blast-radius confirmation', () => {
  it('lists roles in run order, device and offline counts, and confirms explicitly', () => {
    const onConfirm = vi.fn()
    render(
      <ApplyConfirmModal
        show
        onHide={() => {}}
        onConfirm={onConfirm}
        pending={false}
        roles={[role('r1', 'Kiosk'), role('r2', 'Banner')]}
        deviceCount={12}
        offlineCount={3}
        triggeredBy="admin"
      />,
    )

    const items = screen.getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['Kiosk', 'Banner'])
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText(/currently offline/)).toBeInTheDocument()
    expect(screen.getByText(/triggered by admin/)).toBeInTheDocument()

    expect(onConfirm).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Apply configuration' }))
    expect(onConfirm).toHaveBeenCalledWith(null)
  })

  it('schedules the apply for a future time', () => {
    const onConfirm = vi.fn()
    render(
      <ApplyConfirmModal
        show
        onHide={() => {}}
        onConfirm={onConfirm}
        pending={false}
        roles={[role('r1', 'Kiosk')]}
        deviceCount={1}
        offlineCount={0}
      />,
    )

    fireEvent.click(screen.getByLabelText('Apply at…'))
    expect(screen.getByRole('button', { name: 'Schedule apply' })).toBeDisabled()

    const future = new Date(Date.now() + 3600_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const local = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T${pad(future.getHours())}:${pad(future.getMinutes())}`
    fireEvent.change(screen.getByLabelText('Apply at'), { target: { value: local } })
    fireEvent.click(screen.getByRole('button', { name: 'Schedule apply' }))

    expect(onConfirm).toHaveBeenCalledWith(new Date(local).toISOString())
  })

  it('blocks confirmation when nothing would run', () => {
    render(
      <ApplyConfirmModal
        show
        onHide={() => {}}
        onConfirm={() => {}}
        pending={false}
        roles={[]}
        deviceCount={5}
        offlineCount={0}
      />,
    )

    expect(screen.getByText(/no roles are assigned/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply configuration' })).toBeDisabled()
  })
})
