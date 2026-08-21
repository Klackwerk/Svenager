import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { applyTheme, useTheme } from './theme'

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-bs-theme')
})

describe('theme', () => {
  it('stamps data-bs-theme for explicit preferences', () => {
    applyTheme('dark')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark')
    applyTheme('light')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
  })

  it('follows the OS on system (test stub reports light)', () => {
    applyTheme('system')
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('light')
  })

  it('persists the chosen preference across hook instances', () => {
    const first = renderHook(() => useTheme())
    expect(first.result.current.preference).toBe('system')

    act(() => first.result.current.setPreference('dark'))
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark')
    expect(localStorage.getItem('svenager-theme')).toBe('dark')
    first.unmount()

    const second = renderHook(() => useTheme())
    expect(second.result.current.preference).toBe('dark')
  })
})
