import { describe, expect, it } from 'vitest'
import { relativeTime } from './time'

describe('relativeTime', () => {
  it('renders a dash for missing timestamps', () => {
    expect(relativeTime(null)).toBe('—')
  })

  it('renders minutes for recent timestamps', () => {
    const iso = new Date(Date.now() - 3 * 60_000).toISOString()
    expect(relativeTime(iso)).toMatch(/3 minutes ago/)
  })

  it('renders days for older timestamps', () => {
    const iso = new Date(Date.now() - 2 * 24 * 3600_000).toISOString()
    expect(relativeTime(iso)).toMatch(/2 days ago/)
  })

  it('handles just-now timestamps without crashing', () => {
    expect(relativeTime(new Date().toISOString())).toBeTruthy()
  })
})
