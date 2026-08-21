import { render, screen, fireEvent } from '@testing-library/react'
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom'
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { RoleInfo, VariableEntry } from '../api/types'
import RoleVariablesForm from './RoleVariablesForm'

const kioskRole: RoleInfo = {
  id: 'role-kiosk',
  name: 'kiosk',
  displayName: 'Web kiosk',
  description: null,
  userAssignable: true,
  missing: false,
  repositoryId: 'repo-1',
  repository: 'Reference config',
  argumentSpec: {
    kiosk_url: { type: 'str', required: true, description: 'The page shown fullscreen.' },
    kiosk_vnc_port: { type: 'int', default: 5900 },
  },
  defaults: { kiosk_url: 'https://example.org', kiosk_user: 'kiosk', kiosk_vnc_port: 5900 },
}

const bannerRole: RoleInfo = {
  ...kioskRole,
  id: 'role-banner',
  name: 'banner',
  displayName: 'Banner',
  argumentSpec: {},
  defaults: { kiosk_user: 'kiosk' },
}

// The form uses useBlocker for its unsaved-changes guard, so it must render
// inside a data router.
function renderInRouter(element: ReactElement) {
  const router = createMemoryRouter([
    { path: '/', element },
    { path: '/away', element: <p>somewhere else</p> },
  ])
  render(<RouterProvider router={router} />)
  return router
}

describe('RoleVariablesForm', () => {
  it('renders every role variable pre-filled with its default', () => {
    renderInRouter(
      <RoleVariablesForm roles={[kioskRole]} variables={[]} onSave={() => {}} saving={false} error={false} />,
    )

    expect(screen.getByText('Web kiosk')).toBeInTheDocument()
    expect(screen.getByLabelText(/kiosk_url/)).toHaveValue('https://example.org')
    expect(screen.getByLabelText(/kiosk_user/)).toHaveValue('kiosk')
    expect(screen.getAllByText('default')).toHaveLength(3)
  })

  it('saves only its own overrides — secrets and custom entries are page concerns', () => {
    const secret: VariableEntry = { name: 'psk', value: null, secret: true }
    const onSave = vi.fn()
    renderInRouter(
      <RoleVariablesForm
        roles={[kioskRole]}
        variables={[secret]}
        onSave={onSave}
        saving={false}
        error={false}
      />,
    )

    fireEvent.change(screen.getByLabelText(/kiosk_url/), { target: { value: 'https://kiosk.example' } })
    expect(screen.getByText('override')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /save role variables/i }))

    expect(onSave).toHaveBeenCalledWith([{ name: 'kiosk_url', secret: false, value: 'https://kiosk.example' }])
  })

  it('lets an override fall back to the default again', () => {
    const onSave = vi.fn()
    renderInRouter(
      <RoleVariablesForm
        roles={[kioskRole]}
        variables={[{ name: 'kiosk_user', value: 'operator', secret: false }]}
        onSave={onSave}
        saving={false}
        error={false}
      />,
    )

    expect(screen.getByDisplayValue('operator')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /use default/i }))
    expect(screen.getByDisplayValue('kiosk')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /save role variables/i }))
    expect(onSave).toHaveBeenCalledWith([])
  })

  it('blocks saving a required field emptied out, with an announced message', () => {
    const onSave = vi.fn()
    renderInRouter(
      <RoleVariablesForm roles={[kioskRole]} variables={[]} onSave={onSave} saving={false} error={false} />,
    )

    const input = screen.getByLabelText(/kiosk_url/)
    fireEvent.change(input, { target: { value: '' } })

    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('This value is required.')
    expect(screen.getByRole('button', { name: /save role variables/i })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /save role variables/i }))
    expect(onSave).not.toHaveBeenCalled()
  })

  it('a variable declared by two roles shows in both sections with a shared value', () => {
    renderInRouter(
      <RoleVariablesForm
        roles={[kioskRole, bannerRole]}
        variables={[]}
        onSave={() => {}}
        saving={false}
        error={false}
      />,
    )

    const inputs = screen.getAllByLabelText(/kiosk_user/)
    expect(inputs).toHaveLength(2)

    fireEvent.change(inputs[0], { target: { value: 'operator' } })
    expect(inputs[1]).toHaveValue('operator')
  })

  it('prompts before navigating away with unsaved edits', async () => {
    const router = createMemoryRouter([
      {
        path: '/',
        element: (
          <>
            <RoleVariablesForm roles={[kioskRole]} variables={[]} onSave={() => {}} saving={false} error={false} />
            <Link to="/away">leave</Link>
          </>
        ),
      },
      { path: '/away', element: <p>somewhere else</p> },
    ])
    render(<RouterProvider router={router} />)

    fireEvent.change(screen.getByLabelText(/kiosk_user/), { target: { value: 'operator' } })
    fireEvent.click(screen.getByText('leave'))

    expect(await screen.findByText('Unsaved changes')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /stay and keep editing/i }))
    expect(screen.getByLabelText(/kiosk_user/, { selector: 'input' })).toHaveValue('operator')

    fireEvent.click(screen.getByText('leave'))
    fireEvent.click(await screen.findByRole('button', { name: /discard changes/i }))
    expect(await screen.findByText('somewhere else')).toBeInTheDocument()
  })
})
