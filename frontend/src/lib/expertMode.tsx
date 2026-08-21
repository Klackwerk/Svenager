import { createContext, useContext, useState, type ReactNode } from 'react'

const STORAGE_KEY = 'svenager-expert'

interface ExpertMode {
  expert: boolean
  setExpert: (value: boolean) => void
}

const ExpertModeContext = createContext<ExpertMode>({ expert: false, setExpert: () => {} })

/**
 * "Expert details" toggle: reveals raw variables, commit hashes and other
 * plumbing that would confuse the non-technical operator persona.
 * Persisted per browser.
 */
export function ExpertModeProvider({ children }: { children: ReactNode }) {
  const [expert, setExpertState] = useState(() => localStorage.getItem(STORAGE_KEY) === '1')

  const setExpert = (value: boolean) => {
    localStorage.setItem(STORAGE_KEY, value ? '1' : '0')
    setExpertState(value)
  }

  return <ExpertModeContext.Provider value={{ expert, setExpert }}>{children}</ExpertModeContext.Provider>
}

export function useExpertMode() {
  return useContext(ExpertModeContext)
}
