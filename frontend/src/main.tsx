import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import 'bootstrap/dist/css/bootstrap.min.css'
import './index.css'
import App from './App.tsx'
import ToastProvider from './components/ToastProvider.tsx'
import { createQueryClient } from './api/queryClient.ts'

const queryClient = createQueryClient()

// Data router (single splat route, App keeps its own <Routes>) so
// useBlocker-based unsaved-changes guards work.
const router = createBrowserRouter([{ path: '*', element: <App /> }])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
)
