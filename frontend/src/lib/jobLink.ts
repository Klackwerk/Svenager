import { useLocation, useNavigate } from 'react-router-dom'

export const JOB_PARAM = 'job'

/** Search string that opens the job dialog on the current page. */
export function jobSearch(search: string, id: string): string {
  const params = new URLSearchParams(search)
  params.set(JOB_PARAM, id)
  return `?${params}`
}

/** `to` target for a <Link> that opens a job dialog without leaving the page. */
export function useJobLink() {
  const location = useLocation()
  return (id: string) => ({ search: jobSearch(location.search, id) })
}

/** Imperative counterpart, e.g. after queuing a job. */
export function useOpenJob() {
  const navigate = useNavigate()
  const location = useLocation()
  return (id: string) => navigate({ search: jobSearch(location.search, id) })
}
