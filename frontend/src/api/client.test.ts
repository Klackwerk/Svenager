import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './client'

function mockFetch(status: number, body: unknown) {
  const spy = vi.fn(
    async () =>
      new Response(body == null ? null : JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
  )
  vi.stubGlobal('fetch', spy)
  return spy
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('api', () => {
  it('parses JSON responses', async () => {
    mockFetch(200, { hello: 'world' })
    await expect(api('/api/v1/test')).resolves.toEqual({ hello: 'world' })
  })

  it('sends the XSRF cookie value as header on mutating requests', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123'
    const spy = mockFetch(200, {})
    await api('/api/v1/things', { method: 'POST', body: '{}' })
    const headers = new Headers((spy.mock.calls[0] as unknown as [string, RequestInit])[1].headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-123')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('does not attach the CSRF header to GET requests', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123'
    const spy = mockFetch(200, {})
    await api('/api/v1/things')
    const headers = new Headers((spy.mock.calls[0] as unknown as [string, RequestInit])[1].headers)
    expect(headers.get('X-XSRF-TOKEN')).toBeNull()
  })

  it('throws ApiError with the server-provided message', async () => {
    mockFetch(403, { error: 'nope' })
    const promise = api('/api/v1/denied')
    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(api('/api/v1/denied')).rejects.toMatchObject({ status: 403, message: 'nope' })
  })

  it('returns undefined for 204 responses', async () => {
    mockFetch(204, null)
    await expect(api('/api/v1/thing', { method: 'DELETE' })).resolves.toBeUndefined()
  })
})
