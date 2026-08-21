export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/**
 * Called on any 401 outside the auth endpoints — the session expired, so
 * the app shell must fall back to the login screen. Network errors never
 * trigger it (the "server unreachable" state must not log anyone out).
 */
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * Fetch wrapper for the Svenager API: same-origin session auth plus the
 * XSRF-TOKEN double-submit cookie required for mutating requests.
 */
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body != null) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.method && options.method !== 'GET') {
    const csrf = readCookie('XSRF-TOKEN')
    if (csrf) {
      headers.set('X-XSRF-TOKEN', csrf)
    }
  }
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin' })
  if (response.status === 401 && !path.startsWith('/api/v1/auth/')) {
    onUnauthorized?.()
  }
  if (!response.ok) {
    let message = response.statusText
    try {
      const body = await response.json()
      if (body?.error) message = body.error
    } catch {
      // non-JSON error body — keep the status text
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
