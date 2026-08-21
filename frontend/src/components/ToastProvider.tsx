import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import CloseButton from 'react-bootstrap/CloseButton'
import Toast from 'react-bootstrap/Toast'
import ToastContainer from 'react-bootstrap/ToastContainer'

export type ToastVariant = 'success' | 'danger'

export interface ToastInput {
  variant: ToastVariant
  text: string
}

interface ToastItem extends ToastInput {
  id: number
}

type PushToast = (toast: ToastInput) => void

const ToastContext = createContext<PushToast>(() => {})

/** Module-level sink so non-React code (query client defaults) can toast. */
let sink: PushToast | null = null

export function showToast(toast: ToastInput) {
  sink?.(toast)
}

export function useToast() {
  return useContext(ToastContext)
}

let nextId = 1

/**
 * App-wide notification stack (react-bootstrap toasts) in a fixed,
 * polite live region. Push via useToast() in components or showToast()
 * from plain modules such as the query client's mutation defaults.
 */
export default function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])

  const push = useCallback((toast: ToastInput) => {
    setToasts((current) => [...current, { ...toast, id: nextId++ }])
  }, [])

  useEffect(() => {
    sink = push
    return () => {
      if (sink === push) sink = null
    }
  }, [push])

  const dismiss = (id: number) => setToasts((current) => current.filter((t) => t.id !== id))

  return (
    <ToastContext.Provider value={push}>
      {children}
      <ToastContainer
        position="bottom-end"
        className="p-3 position-fixed"
        style={{ zIndex: 1090 }}
        aria-live="polite"
      >
        {toasts.map((toast) => (
          <Toast
            key={toast.id}
            bg={toast.variant}
            onClose={() => dismiss(toast.id)}
            autohide
            delay={toast.variant === 'danger' ? 8000 : 4000}
          >
            <Toast.Body className="text-white d-flex justify-content-between align-items-start gap-2">
              <span>{toast.text}</span>
              <CloseButton
                variant="white"
                aria-label="Dismiss notification"
                onClick={() => dismiss(toast.id)}
              />
            </Toast.Body>
          </Toast>
        ))}
      </ToastContainer>
    </ToastContext.Provider>
  )
}
