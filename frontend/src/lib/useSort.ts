import { useMemo, useState } from 'react'

export type SortValue = string | number | boolean | null
export type SortDirection = 'asc' | 'desc'

export interface SortState {
  key: string | null
  direction: SortDirection
  toggle: (key: string) => void
}

/**
 * Client-side column sorting: first click sorts ascending, the second flips
 * the direction, null values always sort last.
 */
export function useSort<T>(
  rows: T[],
  accessors: Record<string, (row: T) => SortValue>,
  initialKey?: string,
  initialDirection: SortDirection = 'asc',
): { sorted: T[] } & SortState {
  const [key, setKey] = useState<string | null>(initialKey ?? null)
  const [direction, setDirection] = useState<SortDirection>(initialDirection)

  const toggle = (next: string) => {
    if (key === next) {
      setDirection((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setKey(next)
      setDirection('asc')
    }
  }

  const sorted = useMemo(() => {
    const accessor = key ? accessors[key] : null
    if (!accessor) return rows
    return [...rows].sort((a, b) => {
      const va = accessor(a)
      const vb = accessor(b)
      if (va == null && vb == null) return 0
      if (va == null) return 1
      if (vb == null) return -1
      const cmp =
        typeof va === 'number' && typeof vb === 'number'
          ? va - vb
          : String(va).localeCompare(String(vb), undefined, { numeric: true, sensitivity: 'base' })
      return direction === 'asc' ? cmp : -cmp
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, key, direction])

  return { sorted, key, direction, toggle }
}

/** Timestamp accessor helper: ISO string → epoch millis, null stays null. */
export function timeValue(iso: string | null): number | null {
  return iso ? Date.parse(iso) : null
}
