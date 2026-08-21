import { useEffect, useState } from 'react'

export type ThemePreference = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'svenager-theme'

function darkPreferred(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** Stamps Bootstrap's data-bs-theme on <html> for the given preference. */
export function applyTheme(preference: ThemePreference) {
  const dark = preference === 'dark' || (preference === 'system' && darkPreferred())
  document.documentElement.setAttribute('data-bs-theme', dark ? 'dark' : 'light')
}

export function storedThemePreference(): ThemePreference {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system'
}

/**
 * Three-state theme (light/dark/system), persisted in localStorage and
 * following the OS preference live while on "system".
 */
export function useTheme() {
  const [preference, setPreference] = useState<ThemePreference>(storedThemePreference)

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, preference)
    applyTheme(preference)
    if (preference !== 'system') return
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme('system')
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [preference])

  return { preference, setPreference }
}
