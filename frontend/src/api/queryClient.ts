import { MutationCache, QueryClient } from '@tanstack/react-query'
import { showToast } from '../components/ToastProvider'
import { ApiError, setUnauthorizedHandler } from './client'

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** Toast shown when the mutation fails, naming the action. */
      errorMessage?: string
      /** Toast shown when the mutation succeeds. */
      successMessage?: string
      /** Set when the calling UI renders its own inline error. */
      silentError?: boolean
    }
  }
}

/**
 * Query client with app-wide mutation feedback: every failed mutation
 * raises an error toast unless its hook opts out via meta.silentError
 * (because the page shows the error inline).
 */
export function createQueryClient() {
  const queryClient = new QueryClient({
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.silentError) return
        const base = mutation.meta?.errorMessage ?? 'Something went wrong. The change was not saved.'
        const detail = error instanceof ApiError && error.message ? ` (${error.message})` : ''
        showToast({ variant: 'danger', text: `${base}${detail}` })
      },
      onSuccess: (_data, _variables, _context, mutation) => {
        if (mutation.meta?.successMessage) {
          showToast({ variant: 'success', text: mutation.meta.successMessage })
        }
      },
    }),
  })
  // Session expiry: any 401 drops the cached user, so the shell shows login.
  setUnauthorizedHandler(() => queryClient.setQueryData(['me'], null))
  return queryClient
}
